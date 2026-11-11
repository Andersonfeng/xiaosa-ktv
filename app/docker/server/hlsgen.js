const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn, execFile } = require('child_process');
const { promisify } = require('util');
// v1.2.1：原来的 execFileSync 会同步阻塞整个 Node 事件循环(最长 30s/次)，网盘读取一卡，
// 网页/接口/WebSocket 全部跟着卡死；统一改成异步 execFile，只挂起当前这一个调用。
const execFileAsync = promisify(execFile);
const log = require('./logger');
const sourceCache = require('./sourceCache');
// ffmpeg 子进程看守(进度看门狗/硬件熔断/并发池/按歌曲取消/孤儿清理)，详见 ffmpegGuard.js 顶部说明
const guard = require('./ffmpegGuard');
const { runFFmpeg, killAllFFmpeg, cancelSong, SELF_TEST_TIMEOUT_MS } = guard;

// Bug 根本修复（原唱/伴唱切换会从头播放 + 拖进度条失效）：
// 旧方案在切换音轨时用 ffmpeg 现场把整段视频重新封装成一个新的 MP4 流吐给
// <video>，这个新响应体没有 Content-Length/Range 支持（长度未知），所以：
//   1) 切音轨 = 换了一个全新的、不可寻址的流 => 只能从头播放；
//   2) 切完音轨后拖进度条 = 在一个不支持 Range 的流上 seek => 同样只能从头。
// 新方案：把每首歌预先/按需转成 HLS（m3u8 + .ts 分片）。视频轨和每一条音频轨
// 各自独立切片、独立成一份 media playlist，再通过一份 master.m3u8 用
// #EXT-X-MEDIA 把所有音频轨声明成同一个 AUDIO group，与视频轨绑定。
// 前端用 hls.js 加载 master.m3u8 后，切换音轨只是 hls.audioTrack = 0/1 —
// hls.js 只会重新拉取音频分片，视频分片、播放位置完全不受影响；HLS 的分片
// 天然可寻址，拖进度条对任意音轨都正常工作。
//
// 单音轨文件兼容：master.m3u8 里只声明 1 条音频轨，hls.js/播放器行为等价于
// 普通单音轨 HLS 播放，不影响现有的"声道型"(单音轨里左右声道分别是伴唱/原唱)
// Web Audio 分离逻辑——那部分是在解码后的音频节点上做的，跟视频用什么方式
// 加载无关，继续保留在前端。
//
// 本次改动（首次点歌卡顿修复）解决两个问题：
//   1) 转码是纯 CPU 软件编码(libx264)，一首歌要转好几十秒到几分钟，点歌人
//      在这段时间里点开播放器只会一直转圈；现在优先用 VAAPI 硬件编解码，
//      在有核显/独显的机器上大幅缩短转码耗时，且运行时自动探测硬件是否
//      可用、不可用就自动回退到 libx264，不会因为某台机器没有 GPU 而直接
//      放弃转码或者报错。
//   2) 就算硬件转码，一首完整 MV 转完也需要一点时间；旧实现是"整首歌转完
//      -> 一次性 rename 上线 -> 才响应 master.m3u8"，本质上是阻塞式的，
//      用户点下去之后必须等整首歌转码完成才能看到画面。现在改成渐进式：
//      master.m3u8 一旦知道音轨数量就立刻可以生成并返回（不依赖转码是否
//      完成）；video/audioN 的分片使用 HLS "event" 播放列表类型，让 ffmpeg
//      一边转码一边持续追加分片和刷新子播放列表；分片/子播放列表的 HTTP
//      请求如果撞上"这一片还没转出来"，服务端会短暂轮询等待它出现再响应，
//      而不是直接 404 或者等全曲转完，从而做到"随出随播"。

// 本次改动（新增 NVIDIA 显卡转码支持）：
//   在原有 VAAPI（Intel/AMD 核显）硬件加速的基础上，新增一套独立的 NVENC
//   （NVIDIA 独显/核显）探测与编码路径，两者互不影响、可以共存。优先级为
//   NVENC > VAAPI > libx264（软件编码）——某台机器上探测到哪个就用哪个，
//   都探测不到时始终能回退到纯软件编码保证转码不失败。NVIDIA 这条路径要求
//   宿主机已经安装好 nvidia-container-toolkit 并在 docker 里注册好 nvidia
//   runtime，这是 docker 层面的前提条件，不是本文件能控制的；如果宿主机
//   没有装好这些，nvidia-smi 在容器里就会不可用，探测函数会直接判定为
//   "NVIDIA 硬件加速不可用" 并回退，不会导致转码报错。
const HLS_DIR = process.env.HLS_DIR || '/data/hls';
const SEGMENT_TIME = 6; // 秒，只是切片建议值，ffmpeg 仍会在最近的关键帧处切割

// ---------- VAAPI 硬件加速探测 ----------
const VAAPI_DEVICE = process.env.VAAPI_DEVICE || '/dev/dri/renderD128';

// 三态缓存：null=还没测过，true/false=测过的结果。避免每首歌都重新探测一次
// （探测本身要真跑一次 ffmpeg，没必要每次都做）。
let vaapiState = null;

// 只做"设备文件存在"这一层检测还不够——文件存在但驱动装错/权限不对/容器没
// 映射成功时，真正调用 h264_vaapi 编码会直接失败。所以这里额外做一次最小
// 成本的真实编码自检（1x1 帧、编码 1 帧），确保后续所有歌曲都能直接复用
// 一个可靠的判断结果，而不是每首歌自己去试错。
async function detectVAAPI() {
  if (vaapiState !== null) return vaapiState && !guard.isHwTripped('vaapi');

  log.info('VAAPI', `开始探测核显硬件加速，渲染节点: ${VAAPI_DEVICE}`);

  if (!fs.existsSync(VAAPI_DEVICE)) {
    log.warn('VAAPI', `未检测到渲染节点 ${VAAPI_DEVICE}（宿主机可能没有核显，或安装/升级时 devices 段落被自动裁掉）—— 核显调用: 失败，本次运行全程使用软件编码 (libx264)`);
    vaapiState = false;
    return false;
  }
  log.info('VAAPI', `渲染节点 ${VAAPI_DEVICE} 存在，开始做最小成本的真实编码自检（1x1帧/1帧 h264_vaapi 编码）`);

  // 顺带把 vainfo 的驱动摘要打进日志，出问题时（比如装错驱动/权限不对）一眼就能看出来，
  // 这一步失败不影响后续判断，只是尽力而为的诊断信息。
  try {
    const info = String((await execFileAsync('vainfo', { timeout: 8000 })).stdout);
    const driverLine = info.split('\n').find(l => l.includes('Driver version')) || info.split('\n')[0];
    log.info('VAAPI', `vainfo 驱动信息: ${(driverLine || '').trim()}`);
  } catch (e) {
    log.warn('VAAPI', `vainfo 诊断执行失败（不影响后续判断）: ${e.message.split('\n')[0]}`);
  }

  const t0 = Date.now();
  try {
    await runFFmpeg([
      '-loglevel', 'error',
      '-init_hw_device', `vaapi=va:${VAAPI_DEVICE}`,
      '-filter_hw_device', 'va',
      '-f', 'lavfi', '-i', 'color=black:size=64x64:rate=1',
      '-frames:v', '1',
      '-vf', 'format=nv12,hwupload',
      '-c:v', 'h264_vaapi',
      '-f', 'null', '-',
    ]);
    log.info('VAAPI', `核显调用: 成功，自检耗时 ${Date.now() - t0}ms —— 后续转码将优先使用 h264_vaapi 硬件编解码`);
    vaapiState = true;
  } catch (e) {
    log.warn('VAAPI', `核显调用: 失败，自检未通过（耗时 ${Date.now() - t0}ms），原因: ${lastErrLine(e)} —— 回退到软件编码 (libx264)`);
    vaapiState = false;
  }
  return vaapiState;
}

// ---------- NVENC（NVIDIA 独显/核显）硬件加速探测 ----------
// 原理与 VAAPI 探测完全一致：宿主机把 NVIDIA 显卡直通进容器（通过
// nvidia-container-toolkit 提供的 nvidia runtime）之后，容器里能看到
// nvidia-smi、libnvidia-encode.so 等，但这些都只代表"驱动可见"，并不代表
// ffmpeg 真的能调用 h264_nvenc 编码器——同样需要一次最小成本的真实编码自检
// 才能确保后续歌曲都能直接复用一个可靠的判断结果。
//
// 注意：NVIDIA 显卡走的是完全独立于 VAAPI 的一套设备/驱动体系（/dev/nvidia0
// 等设备节点由 nvidia-container-toolkit 的运行时钩子自动挂载，不是
// /dev/dri），所以 VAAPI_DEVICE 这个环境变量对 NVIDIA 卡完全不起作用，
// 二者是两套独立的探测与编码路径，互不影响、可以共存（多显卡/混合部署时
// 优先用 NVENC，NVENC 不可用再退到 VAAPI，最后才是纯软件编码）。
let nvencState = null;

async function detectNVENC() {
  if (nvencState !== null) return nvencState && !guard.isHwTripped('nvenc');

  log.info('NVENC', '开始探测 NVIDIA 显卡硬件加速（h264_nvenc）...');

  // NVENC 专用的 ffmpeg-nvenc 二进制只在 amd64 镜像里安装（见 Dockerfile 里
  // 的架构判断），arm64 上这个文件不存在是正常情况，不代表哪里出了故障。
  if (!fs.existsSync('/usr/local/bin/ffmpeg-nvenc')) {
    log.warn('NVENC', 'NVENC 专用的 ffmpeg-nvenc 二进制不存在（预期情况：镜像只在 amd64 架构上安装这份二进制，NVIDIA 显卡目前也基本只出现在 x86_64 平台）—— NVIDIA 硬件加速: 失败');
    nvencState = false;
    return false;
  }

  // 顺带把 nvidia-smi 摘要打进日志，出问题时（比如宿主机没装
  // nvidia-container-toolkit / 没注册 nvidia runtime / 驱动版本不对）一眼
  // 就能看出来。nvidia-smi 本身由 nvidia-container-toolkit 在容器启动时
  // 自动挂载进来，镜像里不需要也不应该自己安装它。
  try {
    const info = String((await execFileAsync('nvidia-smi', { timeout: 8000 })).stdout);
    const line = info.split('\n').find(l => l.includes('Driver Version')) || info.split('\n')[0];
    log.info('NVENC', `nvidia-smi 驱动信息: ${(line || '').trim()}`);
  } catch (e) {
    log.warn('NVENC', `nvidia-smi 不可用（容器内看不到 NVIDIA 显卡：宿主机可能没装 nvidia-container-toolkit、没在 /etc/docker/daemon.json 注册 nvidia runtime，或者安装/升级时 NVIDIA 相关段落被自动裁掉）—— NVIDIA 硬件加速: 失败`);
    nvencState = false;
    return false;
  }

  const t0 = Date.now();
  try {
    await runFFmpeg([
      '-loglevel', 'error',
      '-f', 'lavfi', '-i', 'color=black:size=64x64:rate=1',
      '-frames:v', '1',
      '-c:v', 'h264_nvenc',
      '-f', 'null', '-',
    ], 'ffmpeg-nvenc');
    log.info('NVENC', `NVIDIA 显卡调用: 成功，自检耗时 ${Date.now() - t0}ms —— 后续转码将优先使用 h264_nvenc 硬件编解码`);
    nvencState = true;
  } catch (e) {
    log.warn('NVENC', `NVIDIA 显卡调用: 失败，自检未通过（耗时 ${Date.now() - t0}ms），原因: ${lastErrLine(e)} —— 回退到 VAAPI（如可用）或 libx264`);
    nvencState = false;
  }
  return nvencState;
}

// 同一首歌同一时间只允许一个生成任务在跑，避免并发请求把 ffmpeg 打架
const building = new Map();   // song_id -> Promise（整首歌全部轨道转码完成）
const buildErrors = new Map(); // song_id -> Error（最近一次转码失败原因）

// 需求(转码任务锁)：以 "songId + HLS 模式(真实音轨 tracks / 声道分离 stereo / 单声道 mono)"
// 作为任务的唯一 key。building 仍然保留 song_id -> Promise 给旧的 isBuilding/清理逻辑用；
// taskLocks 记录每个任务的 taskId/类型/等待者/超时定时器，用来保证：
//   - 同一首歌+同一模式只有一个 ffmpeg 任务；预热和正式播放共用同一把锁；
//   - 后来的 master 请求直接加入已有任务，不再新建；
//   - 任务崩溃/卡死超过 TASK_LOCK_TIMEOUT_MS 自动释放，避免死锁。
const taskLocks = new Map(); // taskKey -> task
const HLS_CACHE_VERSION = 2;
const TASK_LOCK_TIMEOUT_MS = Math.max(
  60 * 1000,
  Number(process.env.HLS_TASK_LOCK_TIMEOUT_MS) || 35 * 60 * 1000
);

function taskKeyFor(songId, variant) {
  return `${songId}:hls:${variant || 'unknown'}`;
}

/** 找这首歌当前正在跑/排队的任务(同一首歌只允许一个模式在跑)。 */
function activeTaskForSong(songId) {
  for (const task of taskLocks.values()) {
    if (String(task.songId) === String(songId) && !task.finished) return task;
  }
  return null;
}

// 供「曲库管理 - 清理缓存」(cacheCleaner.js) 判断/清理某首歌当前的转码状态，
// 不需要它自己再维护一份重复的 building/buildErrors。
function isBuilding(id) {
  return building.has(id) || building.has(Number(id)) || building.has(String(id));
}
function forgetBuildState(id) {
  cancelSong(id);
  for (const [key, task] of [...taskLocks.entries()]) {
    if (String(task.songId) !== String(id)) continue;
    task.finished = true;
    if (task.timer) clearTimeout(task.timer);
    try { task.resolveMaster && task.resolveMaster(); } catch (e) {}
    taskLocks.delete(key);
  }
  building.delete(id); building.delete(Number(id)); building.delete(String(id));
  buildErrors.delete(id); buildErrors.delete(Number(id)); buildErrors.delete(String(id));
}

// 「按存储空间限额清理缓存」需要在每次新缓存生成完成后就有机会立刻检查一次
// 总量是否超限（而不是干等每日一次的定时清理），这里提供一个轻量的订阅点：
// 谁关心"转码又完成了一首"，就注册一个回调，buildHLS 全部轨道转码成功后会
// 依次通知。回调本身出错不影响转码流程本身。
const buildCompleteListeners = [];
function onBuildComplete(fn) {
  if (typeof fn === 'function') buildCompleteListeners.push(fn);
}
function notifyBuildComplete(songId) {
  for (const fn of buildCompleteListeners) {
    try { fn(songId); } catch (e) { log.warn('TRANSCODE', `构建完成回调执行失败(id=${songId}): ${e.message}`); }
  }
}

function outDir(id) {
  return path.join(HLS_DIR, String(id));
}
function masterPath(id) {
  return path.join(outDir(id), 'master.m3u8');
}
function completeMarkerPath(id) {
  return path.join(outDir(id), '.complete');
}
function metaPath(id) {
  return path.join(outDir(id), '.meta.json');
}

// 需求(HLS 缓存)：缓存目录里记录这次产物对应的"参数组合"——音轨数 + 模式
// (tracks/stereo/mono)。只要参数没变、源文件没更新，就直接复用；参数变了
// (典型：点歌时探测成单音轨、后来重新探测成双音轨)就判定缓存失效、重新生成，
// 不会把旧的声道分离分片误当成真实双音轨继续用。
function readBuildMeta(id) {
  try {
    const raw = fs.readFileSync(metaPath(id), 'utf8');
    const meta = JSON.parse(raw);
    if (!meta || typeof meta !== 'object') return null;
    return meta;
  } catch (e) { return null; }
}
function writeBuildMeta(id, meta) {
  try {
    fs.mkdirSync(outDir(id), { recursive: true });
    fs.writeFileSync(metaPath(id), JSON.stringify(meta, null, 2));
  } catch (e) {
    log.warn('TRANSCODE', `[歌曲 id=${id}] 写缓存元数据失败(不影响播放): ${e.message}`);
  }
}

/**
 * 老版本缓存没有 .meta.json：根据现有目录里的 audioN.m3u8 反推模式，
 * 避免升级后把所有历史 HLS 缓存全部重转一遍。
 */
function inferVariantFromCache(id, audioTracks) {
  if ((audioTracks || 1) >= 2) return 'tracks';
  try {
    const has0 = fs.existsSync(path.join(outDir(id), 'audio0.m3u8'));
    const has1 = fs.existsSync(path.join(outDir(id), 'audio1.m3u8'));
    if (has0 && has1) return 'stereo';
    return 'mono';
  } catch (e) { return null; }
}

// "新鲜"指的是这首歌之前已经完整转码成功过，且源文件之后没有被替换过，
// 可以直接复用，不需要重新转码。用一个 .complete 标记文件的 mtime 来判断，
// 而不是 master.m3u8 —— 因为现在 master.m3u8 在转码刚开始时就会写出来，
// 它的存在不再代表"转码已完成"。
function isFresh(id, srcPath, meta) {
  const marker = completeMarkerPath(id);
  if (!fs.existsSync(marker)) return false;
  if (!fs.existsSync(masterPath(id))) return false;
  try {
    const srcStat = fs.statSync(srcPath);
    const outStat = fs.statSync(marker);
    // 源文件比已生成的 HLS 还新（比如换了同名文件），要求重新生成
    if (outStat.mtimeMs < srcStat.mtimeMs) return false;
    // 参数组合必须与当前探测结果一致；meta 缺失(老缓存)时由调用方先补齐/推断。
    if (meta) {
      if (Number(meta.version || 0) !== HLS_CACHE_VERSION) return false;
      if (String(meta.variant || '') === '') return false;
    }
    return true;
  } catch (e) {
    return false;
  }
}

// 判断源编码是否可以直接 -c copy 进 MPEG-TS 且能被浏览器/hls.js 解码。
// 注意：不能只靠 ffmpeg 的退出码判断"copy 是否安全"——实测对 VP8/Opus 这类
// 编码执行 -c copy 进 TS，ffmpeg 退出码是 0（不报错），但产出的是无法解码的
// 垃圾流(ffprobe 显示 codec_name=bin_data)，播放器只会一直黑屏/无声，且此时
// 完全没有异常可捕获来触发回退。所以必须提前用 ffprobe 探测编码，只有明确
// 在白名单里的编码才走 copy，其余一律直接转码，保证任何源格式最终都能出片。
const SAFE_VIDEO_CODECS = new Set(['h264']);
const SAFE_AUDIO_CODECS = new Set(['aac']);

// Bug修复（同一首歌在不同主机表现不一致的根源之一）：这里以前失败时是纯粹
// `catch(e){return '';}`——不管是 ffprobe 根本没装/PATH 找不到、文件在慢速
// NAS/网络共享上读取超时、还是这台机器本身负载太高导致连"起一个 ffprobe
// 子进程"都要排队超过 15s，日志里看到的都只是统一的"源编码=未知"，完全没法
//区分"这台机器就是探测失败了"还是"这台机器只是比另一台慢"。现在把真实的
// 失败原因打进日志（songTag 为空时说明是扫描阶段调用，不打歌曲前缀）；同时
// 把超时从 15s 放宽到 30s——观察到的现象是负载高的机器上，转码这类正常操作
// 都可能耗时几十秒，15s 对探测这一步来说太容易被"机器繁忙"误判成"探测失败"。
async function probeCodecName(filepath, selector, songTag) {
  try {
    const { stdout } = await execFileAsync('ffprobe', [
      '-v', 'error',
      '-select_streams', selector,
      '-show_entries', 'stream=codec_name',
      '-of', 'csv=p=0',
      filepath,
    ], { timeout: 30000 });
    const out = String(stdout).trim();
    return out.split('\n')[0].trim().toLowerCase();
  } catch (e) {
    log.error('TRANSCODE', `${songTag ? songTag + ' ' : ''}探测源编码失败(${selector})，按"未知编码"处理，走转码兜底: ${lastErrLine(e)}`);
    return ''; // 探测失败时按"未知编码"处理，走转码这条更保险的路径
  }
}

// 从 Error 对象里提取"值得写进日志"的那一段原因描述。
//
// 之前各处日志统一用的是 `lastErrLine(e)`——只取按换行拆分后的
// 最后一段。这在 ffmpeg 报错信息本身干净利落、且不以换行符结尾时没问题；
// 但实测发现 ffmpeg/vainfo 的 stderr 经常以一个尾随换行符结束，这时候
// split('\n') 出来的最后一个元素是空字符串——日志里"原因: "后面直接就是
// 空的，看起来像是"报错了但没有原因"，实际上真正有用的报错内容是倒数第二
// 行，被这个写法整个吞掉了。VAAPI 那次"核显调用: 失败...原因: "后面一片空白
// 就是这个 bug 的直接体现。
// 改成：按换行拆分后先过滤掉空行/纯空白行，再取最后几行（默认3行）拼起来，
// 这样即使 stderr 末尾有空行，也不会丢失真正有诊断价值的内容；多取几行还能
// 覆盖"报错信息分布在最后两三行"的情况（比如 ffmpeg 常见的
// "Unknown encoder ..." 和紧随其后的一行汇总信息）。
function lastErrLine(err, maxLines = 3) {
  const msg = (err && err.message) ? String(err.message) : String(err);
  const lines = msg.split('\n').map(l => l.trim()).filter(Boolean);
  if (lines.length === 0) return '(ffmpeg 未输出任何 stderr 内容，可能是启动阶段就失败，比如命令本身不存在或参数非法)';
  return lines.slice(-maxLines).join(' | ');
}

// bin 参数：默认用系统 ffmpeg（apt 装的，跟 va-driver-all 的驱动 ABI 一致，
// VAAPI/libx264 路径全部走这个默认值，不受影响）。NVENC 专用路径会显式传入
// 'ffmpeg-nvenc'（BtbN 静态编译版，见 Dockerfile 里的说明:两份二进制分开用，
// 避免 BtbN 内置的 libva 和系统驱动 ABI 不一致导致 VAAPI 失效的问题）。
// runFFmpeg 已迁移到 ffmpegGuard.js(按进度判断卡死 + 硬件熔断 + 取消 + 低优先级)

function hlsOutArgs(segPattern, playlistPath) {
  return [
    '-f', 'hls',
    '-hls_time', String(SEGMENT_TIME),
    '-hls_playlist_type', 'event',
    '-hls_flags', 'independent_segments+temp_file',
    '-hls_segment_filename', segPattern,
    playlistPath,
  ];
}

// 视频轨：只有源编码是 h264 时才 -c copy 直拷贝（零转码，几乎无延迟）。
// 其余编码需要重新编码为 H.264 时，优先尝试 VAAPI 硬件转码：
//   Tier 1：解码 + 编码整条链路都走硬件（-hwaccel vaapi），效率最高，
//           但要求源视频编码本身能被 VAAPI 硬件解码器支持；
//   Tier 2：源编码硬件解不了时，退一步用软件解码、只在编码这一步用
//           h264_vaapi 硬件编码（hwupload 把软解出来的帧送上 GPU），
//           兼容性更好，依然能吃到硬件编码的加速；
//   Tier 3：以上都不行（没有 VAAPI/驱动异常/这台机器压根没有核显独显）
//           时，回退到纯软件的 libx264，保证任何机器最终都能出片。
async function buildVideoRendition(filepath, dir, songTag) {
  const common = ['-loglevel', 'error', '-y', '-i', filepath, '-map', '0:v:0', '-an'];
  const out = hlsOutArgs(path.join(dir, 'video_%04d.ts'), path.join(dir, 'video.m3u8'));
  const codec = await probeCodecName(filepath, 'v:0', songTag);
  const t0 = Date.now();

  log.info('TRANSCODE', `${songTag} 视频轨: 源编码=${codec || '未知'}`);

  if (SAFE_VIDEO_CODECS.has(codec)) {
    try {
      await runFFmpeg([...common, '-c:v', 'copy', ...out]);
      log.info('TRANSCODE', `${songTag} 视频轨: 直接封装拷贝(copy)完成，耗时 ${Date.now() - t0}ms，未使用核显`);
      return;
    } catch (e) {
      log.warn('TRANSCODE', `${songTag} 视频轨: h264 -c copy 仍失败，改为重新编码: ${lastErrLine(e)}`);
    }
  }

  const useNvenc = await detectNVENC();
  if (useNvenc) {
    // Tier 0：NVIDIA 硬解(cuda) + 硬编(h264_nvenc)，两张显卡都可用时优先级
    // 最高——消费级 NVIDIA 显卡的 NVENC 编码效率通常明显好于核显 VAAPI。
    const t0b = Date.now();
    try {
      await runFFmpeg([
        '-loglevel', 'error', '-y',
        '-hwaccel', 'cuda', '-hwaccel_output_format', 'cuda',
        '-i', filepath, '-map', '0:v:0', '-an',
        '-c:v', 'h264_nvenc',
        ...out,
      ], 'ffmpeg-nvenc');
      log.info('TRANSCODE', `${songTag} 视频轨: NVIDIA 显卡调用成功(Tier0 硬解+硬编 h264_nvenc)，耗时 ${Date.now() - t0b}ms`);
      return;
    } catch (e) {
      log.warn('TRANSCODE', `${songTag} 视频轨: NVIDIA 硬解+硬编失败(Tier0)，尝试软解+NVENC硬编: ${lastErrLine(e)}`);
    }

    // Tier 0b：软件解码 + NVENC 硬件编码（兼容 CUDA 解不了的源编码，仍能
    // 吃到 NVENC 编码加速）。
    const t0c = Date.now();
    try {
      await runFFmpeg([...common, '-c:v', 'h264_nvenc', ...out], 'ffmpeg-nvenc');
      log.info('TRANSCODE', `${songTag} 视频轨: NVIDIA 显卡调用成功(Tier0b 软解+硬编 h264_nvenc)，耗时 ${Date.now() - t0c}ms`);
      return;
    } catch (e) {
      log.warn('TRANSCODE', `${songTag} 视频轨: NVENC 全部尝试失败，改试 VAAPI(如可用)/libx264: ${lastErrLine(e)}`);
    }
  }

  const useVaapi = await detectVAAPI();
  if (useVaapi) {
    // Tier 1：硬件解码 + 硬件编码
    const t1 = Date.now();
    try {
      await runFFmpeg([
        '-loglevel', 'error', '-y',
        '-hwaccel', 'vaapi', '-hwaccel_device', VAAPI_DEVICE, '-hwaccel_output_format', 'vaapi',
        '-i', filepath, '-map', '0:v:0', '-an',
        '-c:v', 'h264_vaapi',
        ...out,
      ]);
      log.info('TRANSCODE', `${songTag} 视频轨: 核显调用成功(Tier1 硬解+硬编 h264_vaapi)，耗时 ${Date.now() - t1}ms`);
      return;
    } catch (e) {
      log.warn('TRANSCODE', `${songTag} 视频轨: 核显硬解+硬编失败(Tier1)，尝试软解+硬编(Tier2): ${lastErrLine(e)}`);
    }

    // Tier 2：软件解码 + 硬件编码（兼容硬件解不了的源编码，仍用硬件编码加速）
    const t2 = Date.now();
    try {
      await runFFmpeg([
        ...common,
        '-vaapi_device', VAAPI_DEVICE,
        '-vf', 'format=nv12,hwupload',
        '-c:v', 'h264_vaapi',
        ...out,
      ]);
      log.info('TRANSCODE', `${songTag} 视频轨: 核显调用成功(Tier2 软解+硬编 h264_vaapi)，耗时 ${Date.now() - t2}ms`);
      return;
    } catch (e) {
      log.warn('TRANSCODE', `${songTag} 视频轨: 核显调用失败(Tier2 软解+硬编)，回退到纯软件编码(Tier3 libx264): ${lastErrLine(e)}`);
    }
  } else {
    log.info('TRANSCODE', `${songTag} 视频轨: NVENC/VAAPI 均不可用，直接使用软件编码(Tier3 libx264)`);
  }

  // Tier 3：纯软件编码兜底
  const t3 = Date.now();
  await runFFmpeg([...common, '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20', '-pix_fmt', 'yuv420p', ...out]);
  log.info('TRANSCODE', `${songTag} 视频轨: 软件编码(Tier3 libx264)完成，耗时 ${Date.now() - t3}ms，未使用核显`);
}

// 探测单音轨源文件的声道数：用于区分"真正的单声道(mono)"(没法做原/伴唱
// 分离，只能出1条音轨)和"单音轨但源是立体声"(老式声道型 MV，左右声道分别
// 是伴唱/原唱，能用 pan 滤镜虚拟出2条音轨)。只在 audio_tracks<2 时才需要
// 探测，真正多音轨的歌不会调用这个函数。探测失败(ffprobe 挂了/文件读取
// 异常)时保守返回 0，按"没法判定声道数"处理，退回到普通单音轨(不做声道
// 分离)，不冒险生成语义可能错误的虚拟音轨。
async function probeAudioChannels(filepath, songTag) {
  try {
    const { stdout } = await execFileAsync('ffprobe', [
      '-v', 'error',
      '-select_streams', 'a:0',
      '-show_entries', 'stream=channels',
      '-of', 'csv=p=0',
      filepath,
    ], { timeout: 30000 });
    const out = String(stdout).trim();
    const n = parseInt(out.split('\n')[0], 10);
    return Number.isFinite(n) ? n : 0;
  } catch (e) {
    log.warn('TRANSCODE', `${songTag ? songTag + ' ' : ''}探测源音频声道数失败，按"无法声道分离"保守处理: ${lastErrLine(e)}`);
    return 0;
  }
}

// 音频轨：只有源编码是 aac 时才 -c copy，其余编码（Opus/Vorbis/FLAC/AC3 等）
// 直接重新编码为 AAC。音频转码本身 CPU 消耗很低，没有必要也没有硬件通道，
// 继续用软件编码即可。
async function buildAudioRendition(filepath, dir, track, songTag) {
  const common = ['-loglevel', 'error', '-y', '-i', filepath, '-map', `0:a:${track}`, '-vn'];
  const out = hlsOutArgs(path.join(dir, `audio${track}_%04d.ts`), path.join(dir, `audio${track}.m3u8`));
  const codec = await probeCodecName(filepath, `a:${track}`, songTag);
  const trackName = track === 0 ? '原唱' : track === 1 ? '伴唱' : `音轨${track}`;
  const t0 = Date.now();
  log.info('TRANSCODE', `${songTag} 音轨${track}(${trackName}): 源编码=${codec || '未知'}`);
  if (SAFE_AUDIO_CODECS.has(codec)) {
    try {
      await runFFmpeg([...common, '-c:a', 'copy', ...out]);
      log.info('TRANSCODE', `${songTag} 音轨${track}(${trackName}): 直接封装拷贝(copy)完成，耗时 ${Date.now() - t0}ms`);
      return;
    } catch (e) {
      log.warn('TRANSCODE', `${songTag} 音轨${track}(${trackName}): aac -c copy 仍失败，改为重新编码: ${lastErrLine(e)}`);
    }
  }
  const t1 = Date.now();
  await runFFmpeg([...common, '-c:a', 'aac', '-b:a', '192k', ...out]);
  log.info('TRANSCODE', `${songTag} 音轨${track}(${trackName}): 软件编码(aac)完成，耗时 ${Date.now() - t1}ms`);
}

// 需求（单音轨自动检测+预解码，供客户端软解切原/伴唱）：
// 源文件其实只有 0:a:0 这一条真实音频流(audio_tracks<2)，但如果这条流本身
// 是立体声(2声道)，很可能是老式"声道型"编码——左右声道分别对应伴唱/原唱
// (跟 TV 端 web/index.html 里 VoiceManager.applyStereo() 处理的是同一类文件，
// 那边是在浏览器端用 Web Audio 的 ChannelSplitter/Merger 做声道复制)。
// Android 客户端的 ExoPlayer 没有等价的"运行时声道级处理"能力，只能通过
// TrackSelectionOverride 在"多条独立音轨"之间切换，所以这里把声道复制这一步
// 挪到服务端用 ffmpeg 的 pan 滤镜完成，虚拟出两条独立的 HLS 音频渲染，语义
// 与网页端完全对齐：track 0(原唱)=右声道(c1)复制到左右两声道，
// track 1(伴唱)=左声道(c0)复制到左右两声道。产出的目录结构(audio0.m3u8/
// audio1.m3u8)跟"真正的多音轨"完全一样，index.js 路由层和客户端都不需要
// 关心背后是真实的多音轨还是这里合成出来的。
async function buildStereoSplitAudioRendition(filepath, dir, track, songTag) {
  const common = ['-loglevel', 'error', '-y', '-i', filepath, '-map', '0:a:0', '-vn'];
  const panFilter = track === 0 ? 'pan=stereo|c0=c1|c1=c1' : 'pan=stereo|c0=c0|c1=c0';
  const out = hlsOutArgs(path.join(dir, `audio${track}_%04d.ts`), path.join(dir, `audio${track}.m3u8`));
  const trackName = track === 0 ? '原唱' : '伴唱';
  const t0 = Date.now();
  log.info('TRANSCODE', `${songTag} 音轨${track}(${trackName}·声道复制虚拟音轨): 源为单音轨立体声，用 pan 滤镜复制${track === 0 ? '右' : '左'}声道到双耳`);
  await runFFmpeg([...common, '-af', panFilter, '-c:a', 'aac', '-b:a', '192k', ...out]);
  log.info('TRANSCODE', `${songTag} 音轨${track}(${trackName}·声道复制虚拟音轨): 完成，耗时 ${Date.now() - t0}ms`);
}

// 立即写出 master.m3u8——它只依赖"这首歌有几条音轨"这个已经存在于数据库里
// 的信息，跟视频/音频具体转码进度无关，所以不需要等任何 ffmpeg 进程完成
// 就可以先生成并让播放器拿到。播放器随后请求 video.m3u8 / audioN.m3u8 时，
// 如果对应分片还没转出来，由路由层(index.js)负责短暂等待，而不是在这里
// 阻塞。
// 音轨校对(v1.4.0)：swap=true 表示这首歌的物理音轨顺序是颠倒的(物理 0 其实是伴唱)。
// 做法：不动转码产物(audio0/audio1 仍是物理 0/1)，只在 master.m3u8 里把两条音轨的
// 顺序、名称、DEFAULT 对调——第 1 条(客户端的"原唱"，hls.js 的 audioTrack=0、
// ExoPlayer 的第 0 个音频轨道组)指向 audio1.m3u8，第 2 条(伴唱)指向 audio0.m3u8。
// 所有按"第 0 条=原唱"选轨的软解客户端无需任何改动就拿到正确的原/伴唱，也不需要重新转码。
function buildMasterText(trackCount, swap) {
  const names = trackCount >= 2 ? ['原唱', '伴唱'] : ['原唱'];
  let m3u8 = '#EXTM3U\n#EXT-X-VERSION:6\n';
  for (let t = 0; t < trackCount; t++) {
    const name = names[t] || `音轨${t + 1}`;
    const isDefault = t === 0 ? 'YES' : 'NO';
    // 只有两条音轨时才可能互换；其它情况(单音轨、3条以上)保持原样
    const physical = (swap && trackCount === 2) ? 1 - t : t;
    m3u8 += `#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="${name}",DEFAULT=${isDefault},AUTOSELECT=${isDefault},URI="audio${physical}.m3u8"\n`;
  }
  m3u8 += '#EXT-X-STREAM-INF:BANDWIDTH=8000000,AUDIO="aud"\nvideo.m3u8\n';
  return m3u8;
}

function writeMasterPlaylist(dir, trackCount, swap) {
  fs.writeFileSync(path.join(dir, 'master.m3u8'), buildMasterText(trackCount, !!swap));
}

// 管理员/遥控端修改"互换"标记后，已经转码好(或正在转码)的 HLS 目录里的 master.m3u8 也要同步改。
// 轨道数直接从现有 master.m3u8 里数 #EXT-X-MEDIA 行得到，不依赖数据库。目录/文件不存在返回 false。
function refreshMasterSwap(masterFile, swap) {
  try {
    const old = fs.readFileSync(masterFile, 'utf8');
    const trackCount = (old.match(/#EXT-X-MEDIA:/g) || []).length;
    if (trackCount < 1) return false;
    const next = buildMasterText(trackCount, !!swap);
    if (next !== old) fs.writeFileSync(masterFile, next);
    return true;
  } catch (e) { return false; }
}

// 视频轨 + 每条音频轨的 ffmpeg 进程并发启动（Promise.all），而不是像旧版
// 那样排队一条条等——这样多条轨道是同时在产出分片的，进一步缩短"能看到第
// 一屏画面"所需的时间。全部轨道都转码成功后才写 .complete 标记；任何一条
// 失败都会被上层捕获记录，方便前端/路由层判断这首歌为什么迟迟出不了片。
// effectiveTrackCount 由调用方(ensureHLS)算好传进来，避免这里重复探测一次
// 声道数(ensureHLS 为了同步写出 master.m3u8 已经探测过一次了)。
async function _buildHLSInner(song, dir, effectiveTrackCount, filepath, taskId) {
  const declaredTracks = Math.max(1, song.audio_tracks || 1);
  // 需求(日志区分任务实例)：每次转码都有独立 taskId，预热/播放/客户端重试
  // 触发的不同实例在日志里能一眼区分，不会把同歌的两条任务日志混在一起。
  const songTag = `[task=${taskId || 'unknown'}] [歌曲 id=${song.id} "${song.title || song.filename}"]`;
  const t0 = Date.now();

  const tasks = [buildVideoRendition(filepath, dir, songTag)];
  if (declaredTracks >= 2) {
    log.info('TRANSCODE', `${songTag} 开始转码，共 ${declaredTracks} 条真实音轨（1=原唱, 2=伴唱）`);
    for (let t = 0; t < declaredTracks; t++) {
      tasks.push(buildAudioRendition(filepath, dir, t, songTag));
    }
  } else if (effectiveTrackCount >= 2) {
    log.info('TRANSCODE', `${songTag} 单音轨但源为立体声，按"声道型"虚拟出2条音轨(声道复制：0=原唱=右声道，1=伴唱=左声道)，供软解(HLS)模式下原/伴唱切换`);
    tasks.push(buildStereoSplitAudioRendition(filepath, dir, 0, songTag));
    tasks.push(buildStereoSplitAudioRendition(filepath, dir, 1, songTag));
  } else {
    log.info('TRANSCODE', `${songTag} 开始转码，共 1 条音轨（单声道，无法声道分离）`);
    tasks.push(buildAudioRendition(filepath, dir, 0, songTag));
  }
  try {
    await Promise.all(tasks);
  } catch (e) {
    log.error('TRANSCODE', `${songTag} 转码失败，总耗时 ${Date.now() - t0}ms，原因: ${lastErrLine(e)}`);
    throw e;
  }

  fs.writeFileSync(completeMarkerPath(song.id), String(Date.now()));
  log.info('TRANSCODE', `${songTag} 全部轨道转码完成，总耗时 ${Date.now() - t0}ms`);
  notifyBuildComplete(song.id);
}

// 转码入口：占全局并发槽位 + 建立"这首歌的取消上下文"(必须在 ensureHLS 里同步调用)。
function buildHLS(song, dir, effectiveTrackCount, filepath, opts = {}) {
  return guard.runBuild(song.id, opts, () => _buildHLSInner(song, dir, effectiveTrackCount, filepath, opts.taskId));
}

// opts.urgent=true：用户正在等这首歌播放(而不是队列预热)，排队时插队、可占额外槽位。
// 任务锁：同一首歌 + 同一个 HLS 模式只允许一个任务；后来的请求加入已有任务，
// 预热与正式播放共用同一把锁；任务超时自动释放，避免 ffmpeg 卡死把锁永久占住。
async function ensureHLSInner(song, opts = {}) {
  const { id } = song;
  const audioTracks = Math.max(1, song.audio_tracks || 1);
  const songTag = `[歌曲 id=${id} "${song.title || song.filename}"]`;
  // 需求(网盘先缓存到本地再探测/播放)：网络挂载曲库的歌，转码源文件优先用
  // 本地缓存副本(如果已经缓存好)，没缓存好就暂时用网络路径兜底(同时后台
  // 已经在悄悄补缓存了，见 sourceCache.resolvePlaybackPath())，本地曲库的歌
  // 完全不受影响、还是原来的 filepath。
  let { path: filepath } = sourceCache.resolvePlaybackPath(song);

  // 需求(网盘STRM支持)：STRM 曲目没有"网络路径兜底"可用(filepath 只是一个
  // 本地 .strm 文本指针文件，不是可以直接丢给 ffmpeg 的媒体文件)，
  // resolvePlaybackPath() 在这种情况下会返回 path: null，同时已经在后台
  // 触发了一次 ensureCached()。ensureHLS() 本来就是"这首歌现在要播放，等它
  // 转码好"的场景(调用方要么是后台预热、要么是真正的 /hls 请求)，这里选择
  // 直接 await 一次 ensureCached() 等缓存落地，而不是像硬解直传(/stream)那样
  // 直接 404——转码本来就要等，等的时候顺便把源文件缓存这一步也一起等掉，
  // 用户感知上只是"转码稍微多花了下载这首歌的时间"，不会多一次失败。
  // v1.2.1 修复(网盘歌曲播放后网页卡死/打不开)：网络挂载(115等)的歌以前在缓存没好时
  // 直接拿网络路径去跑 ffprobe/ffmpeg——FUSE 挂载一抖就 ETIMEDOUT/Input-output error，
  // 还和后台拷贝抢同一条网盘带宽。现在和 STRM 一样：网络歌曲一律等本地缓存落地后
  // 再探测、转码(前端加载界面本来就在轮询 cache-progress 展示下载进度)。
  if (!filepath || !sourceCache.getReadyPathOrNull(song.id) && (song.is_network || song.is_strm)) {
    filepath = await sourceCache.ensureCached(song.id, sourceCache.resolveSourceInput(song.filepath));
  }

  // 需求(HLS 转码结果缓存)：缓存是否有效由 .meta.json 里的"音轨数+模式"决定，
  // 音频/视频编码/分辨率变化不会影响 HLS 产物本身(只影响客户端选路)；
  // audio_swap 只改 master.m3u8 里的顺序，由 /hls 路由每次下发前 refreshMasterSwap。
  let meta = readBuildMeta(id);
  if (!meta && fs.existsSync(completeMarkerPath(id)) && fs.existsSync(masterPath(id))) {
    // 老版本缓存没有 meta：根据目录内容反推模式，补写 meta 后继续当缓存用，
    // 避免升级后把历史缓存全部重转一遍。
    const legacyVariant = inferVariantFromCache(id, audioTracks);
    if (legacyVariant) {
      meta = {
        version: HLS_CACHE_VERSION,
        audioTracks,
        variant: legacyVariant,
        effectiveTrackCount: legacyVariant === 'tracks' ? audioTracks : (legacyVariant === 'stereo' ? 2 : 1),
        createdAt: Date.now(),
        legacy: true,
      };
      writeBuildMeta(id, meta);
    }
  }
  if (meta && Number(meta.audioTracks || 1) !== audioTracks) {
    // 音轨数变了(典型：先探测成单音轨、后来重新探测成双音轨)：旧产物不再可信。
    log.info('TRANSCODE', `${songTag} HLS 缓存参数已变化(audioTracks ${meta.audioTracks || 1} -> ${audioTracks})，作废重建`);
    meta = null;
  }
  if (meta && isFresh(id, filepath, meta)) {
    buildErrors.delete(id);
    log.info('TRANSCODE', `[task=cache] ${songTag} 命中 HLS 缓存 variant=${meta.variant} audioTracks=${audioTracks}，直接复用，不启动 ffmpeg`);
    return masterPath(id);
  }

  // 决定本次产物模式：>=2 条真实音轨 -> tracks；单音轨立体声 -> stereo；单声道 -> mono。
  let variant = meta ? meta.variant : (audioTracks >= 2 ? 'tracks' : null);
  const cacheFresh = meta ? isFresh(id, filepath, meta) : false;
  if (!variant || (!cacheFresh && audioTracks < 2)) {
    const channels = await probeAudioChannels(filepath, songTag);
    variant = channels >= 2 ? 'stereo' : 'mono';
  }
  const effectiveTrackCount = variant === 'tracks' ? audioTracks : (variant === 'stereo' ? 2 : 1);
  const key = taskKeyFor(id, variant);

  // 同一首歌已经有任务：如果模式相同，直接加入(预热/播放共用)；模式不同，
  // 取消旧任务后按新参数重建，避免把声道分离产物当成真实双音轨继续用。
  const active = activeTaskForSong(id);
  if (active) {
    if (active.variant !== variant) {
      log.warn('TRANSCODE', `[task=${active.taskId}] ${songTag} 检测到模式变化(${active.variant} -> ${variant})，取消旧任务并重建`);
      cancelSong(id);
      active.finished = true;
      if (active.timer) clearTimeout(active.timer);
      if (taskLocks.get(active.key) === active) taskLocks.delete(active.key);
      building.delete(id);
    } else {
      active.joins = (active.joins || 0) + 1;
      if (opts.urgent) { active.type = 'playback'; guard.promote(id); }
      log.info('TRANSCODE', `[task=${active.taskId}] ${songTag} 加入已有转码任务(type=${active.type}, state=${active.state}, joins=${active.joins})，不新建 ffmpeg`);
      try { await active.masterReady; } catch (e) { /* 任务失败由 waitForFile 读取 buildErrors */ }
      return masterPath(id);
    }
  }

  const taskId = crypto.randomUUID();
  const dir = outDir(id);
  // 之前没有在跑的任务：清空旧目录，先写 meta + master.m3u8，再把真正耗时的
  // 转码丢到后台异步跑，不在这里 await —— 保持"随出随播"的渐进式体验。
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  writeBuildMeta(id, {
    version: HLS_CACHE_VERSION,
    audioTracks,
    variant,
    effectiveTrackCount,
    createdAt: Date.now(),
  });
  writeMasterPlaylist(dir, effectiveTrackCount, song.audio_swap);
  buildErrors.delete(id);

  const task = {
    taskId, key, songId: String(id), variant,
    type: opts.urgent ? 'playback' : 'preload',
    state: 'queued', startedAt: Date.now(), joins: 0,
    finished: false, timer: null,
  };
  let resolveMaster;
  task.masterReady = new Promise(resolve => { resolveMaster = resolve; });
  task.resolveMaster = resolveMaster;
  taskLocks.set(key, task);
  urgentSongs.delete(String(id));

  const p = buildHLS(song, dir, effectiveTrackCount, filepath, { urgent: !!opts.urgent, taskId })
    .then(() => {
      task.state = 'done';
      log.info('TRANSCODE', `[task=${taskId}] ${songTag} 转码任务完成 variant=${variant}`);
    })
    .catch(e => {
      if (!guard.isCancelledError(e)) buildErrors.set(id, e);
      task.error = e;
      throw e;
    })
    .finally(() => {
      task.finished = true;
      if (task.timer) clearTimeout(task.timer);
      if (taskLocks.get(key) === task) taskLocks.delete(key);
      if (building.get(id) === p) building.delete(id);
      try { resolveMaster(); } catch (e) {}
    });
  building.set(id, p);
  task.promise = p;
  task.state = 'active';
  // 同 ensureHLS 旧实现：让这个 Promise 被处理过，避免 Node 因 unhandledRejection 退出。
  p.catch(() => {});
  task.timer = setTimeout(() => {
    if (task.finished) return;
    task.timedOut = true;
    task.state = 'timeout';
    const msg = `转码任务锁超时(${Math.round(TASK_LOCK_TIMEOUT_MS / 60000)}分钟)，已强制取消并释放锁`;
    log.error('TRANSCODE', `[task=${taskId}] ${songTag} ${msg}`);
    buildErrors.set(id, new Error(msg));
    try { cancelSong(id); } catch (e) {}
    task.finished = true;
    if (taskLocks.get(key) === task) taskLocks.delete(key);
    if (building.get(id) === p) building.delete(id);
    try { resolveMaster(); } catch (e) {}
  }, TASK_LOCK_TIMEOUT_MS);
  task.timer.unref();

  log.info('TRANSCODE', `[task=${taskId}] ${songTag} 创建转码任务 type=${task.type} variant=${variant} audioTracks=${audioTracks} effectiveTracks=${effectiveTrackCount} key=${key}`);
  // master.m3u8 已经写好，后续重复请求可以立刻加入这个任务。
  try { resolveMaster(); } catch (e) {}
  return masterPath(id);
}

// 同一首歌的 ensureHLS 入口再做一层"探测/建任务"阶段的串行化：
// 单音轨歌曲在建任务前要 await 一次 probeAudioChannels，两个 master 请求
// 如果几乎同时到达，可能都通过 activeTaskForSong()==null，然后各自建一个
// 任务。这层 in-flight Promise 保证同一首歌同一时间只有一个 ensureHLS 在跑，
// 后来的请求等它返回后直接复用它的任务/缓存。
const ensureInflight = new Map();
const urgentSongs = new Set();
async function ensureHLS(song, opts = {}) {
  const id = String(song.id);
  if (opts.urgent) guard.promote(song.id);
  const running = ensureInflight.get(id);
  if (running) {
    if (opts.urgent) {
      urgentSongs.add(id);
      guard.promote(song.id);
      const t = activeTaskForSong(id);
      if (t) t.type = 'playback';
    }
    log.info('HLS', `[歌曲 id=${id}] ensureHLS 已有在跑的探测/建任务流程，本次等待复用(urgent=${!!opts.urgent})`);
    return running;
  }
  const effectiveOpts = { ...opts, urgent: !!opts.urgent || urgentSongs.has(id) };
  const p = ensureHLSInner(song, effectiveOpts);
  ensureInflight.set(id, p);
  try {
    return await p;
  } finally {
    if (ensureInflight.get(id) === p) ensureInflight.delete(id);
    urgentSongs.delete(id);
  }
}

// 等待某个具体文件（子播放列表或分片）出现，供路由层在文件"还在转码中、
// 暂时不存在"时短暂轮询，而不是立刻 404 或者反过来等整首歌转完。
// 一旦文件出现就立刻 resolve，做到"随出随响应"。
function waitForFile(filepath, songId, { timeoutMs = 60000, intervalMs = 200, noBuildGraceMs = 4000 } = {}) {
  // v1.4.2 Bug修复(网盘/转码失败时只看到"等待分片超时"，看不到真正失败原因)：
  // 路由层(/hls/:id/:file)传进来的 songId 是 req.params 里的【字符串】，而
  // ensureHLS() 写 building/buildErrors 用的是数据库里的【数字】song.id——Map 的
  // 键 '139' !== 139，buildErrors.has('139') 永远是 false，转码失败根本发现不了，
  // 请求只能干等满 60s 再 404，日志里只有一句"等待分片超时"。这里统一把几种键
  // 形态都查一遍。
  const keys = [songId, Number(songId), String(songId)];
  const findErr = () => { for (const k of keys) if (buildErrors.has(k)) return buildErrors.get(k); return undefined; };
  return new Promise((resolve, reject) => {
    const startedAt = Date.now();
    const deadline = startedAt + timeoutMs;
    (function poll() {
      if (fs.existsSync(filepath)) return resolve(filepath);
      const err = findErr();
      if (err) {
        return reject(Object.assign(new Error('转码失败'), { cause: err, code: 'BUILD_FAILED' }));
      }
      // 没有在转码、也没有失败记录、文件还不存在：任务可能被取消(cancelSong)或"悄悄"没起来，
      // 再等也等不到。宽限几秒(给"刚好在两次转码交接"的瞬间留余地)后直接上报，由路由层重新拉起转码。
      if (!isBuilding(songId) && Date.now() - startedAt > noBuildGraceMs) {
        return reject(Object.assign(new Error('没有进行中的转码任务'), { code: 'NO_BUILD' }));
      }
      if (Date.now() > deadline) {
        return reject(Object.assign(new Error('等待分片生成超时'), { code: 'TIMEOUT' }));
      }
      setTimeout(poll, intervalMs);
    })();
  });
}

function removeHLS(id) {
  cancelSong(id); // 先杀该歌所有 ffmpeg，再删目录，避免旧进程继续写、或被重复拉起
  for (const [key, task] of [...taskLocks.entries()]) {
    if (String(task.songId) !== String(id)) continue;
    task.finished = true;
    if (task.timer) clearTimeout(task.timer);
    try { task.resolveMaster && task.resolveMaster(); } catch (e) {}
    taskLocks.delete(key);
    log.info('TRANSCODE', `[task=${task.taskId}] [歌曲 id=${id}] 缓存清理，释放任务锁`);
  }
  fs.rmSync(outDir(id), { recursive: true, force: true });
  building.delete(id);
  buildErrors.delete(id);
  log.info('TRANSCODE', `[歌曲 id=${id}] 已清理 HLS 转码产物`);
}

// ---------- HLS 缓存每日清理 ----------
// HLS 转码产物（.ts 分片）只是一份可以随时重新生成的播放缓存，不是原始曲库
// 数据，但每首歌一旦转码过就会一直占着磁盘空间，曲库越大、播放过的歌越多，
// /data/hls 越滚越大，长期不清理会把 NAS 的存储空间越吃越紧。
// 之前唯一会清理这个目录的时机是 scanner.js 扫描时发现某首歌"源文件已不存在"
// 才触发的 removeHLS——也就是说只要源文件还在，哪怕这首歌几个月都没人点过，
// 它的 HLS 缓存也永远不会被回收。这里补一个独立于扫描的每日定时清理：
//   1) 孤儿目录：目录名（歌曲 id）在数据库里已经找不到对应记录——理论上
//      scanner.js 的清理逻辑会覆盖这种情况，但进程重启/异常退出等边界场景
//      可能让某次清理被跳过，这里作为兜底再兜一层。
//   2) 过期缓存：距离上一次转码完成（.complete 标记的 mtime）已经超过
//      HLS_CACHE_MAX_AGE_DAYS（默认 3 天）——这类歌曲源文件仍然存在、随时
//      可以点唱，只是最近点唱页面点这首歌就会照常触发 ensureHLS 重新生成，
//      对用户体验没有影响，只是把"不常被点的歌"占用的磁盘空间定期释放掉。
//      正在后台转码中的目录（building 里还有它，或者 .complete 还不存在）
//      一律跳过，避免清理逻辑跟正在写入的转码进程打架。
const HLS_CACHE_MAX_AGE_DAYS = Number(process.env.HLS_CACHE_MAX_AGE_DAYS) || 3;

function cleanupExpiredHLS(getValidSongIds) {
  if (!fs.existsSync(HLS_DIR)) return { scanned: 0, removed: 0 };

  let entries;
  try {
    entries = fs.readdirSync(HLS_DIR, { withFileTypes: true });
  } catch (e) {
    log.error('HLS_CLEAN', `读取 HLS 缓存目录失败，本次清理已跳过: ${e.message}`);
    return { scanned: 0, removed: 0 };
  }

  let validIds = null;
  try {
    validIds = getValidSongIds ? new Set(getValidSongIds()) : null;
  } catch (e) {
    log.warn('HLS_CLEAN', `获取有效曲目 id 列表失败，本次跳过孤儿目录清理，仅按过期时间清理: ${e.message}`);
  }

  const maxAgeMs = HLS_CACHE_MAX_AGE_DAYS * 24 * 60 * 60 * 1000;
  const now = Date.now();
  let scanned = 0, removed = 0;

  for (const entry of entries) {
    if (!entry.isDirectory() || !/^\d+$/.test(entry.name)) continue; // 只处理"数字目录名=歌曲id"这种规范产物，其它一律不动
    scanned++;
    const id = entry.name;

    if (building.has(id) || building.has(Number(id))) continue; // 正在转码中，跳过

    let reason = null;
    if (validIds && !validIds.has(Number(id)) && !validIds.has(id)) {
      reason = '对应歌曲已不在曲库中(孤儿缓存)';
    } else {
      const marker = completeMarkerPath(id);
      try {
        const st = fs.statSync(marker);
        if (now - st.mtimeMs > maxAgeMs) {
          reason = `距上次转码完成已超过 ${HLS_CACHE_MAX_AGE_DAYS} 天`;
        }
      } catch (e) {
        // .complete 不存在：要么转码还没完成过（不该在这里清），要么是一次
        // 半成品残留（上次转码中途异常退出），后者本身也该被清掉。用目录本身
        // 的 mtime 兜底判断是否"陈旧"，避免半成品目录永远留在磁盘上。
        try {
          const dirStat = fs.statSync(outDir(id));
          if (now - dirStat.mtimeMs > maxAgeMs) {
            reason = '半成品缓存目录长期未完成转码，判定为陈旧残留';
          }
        } catch (e2) { /* 目录本身都读不到，忽略，等下一轮再看 */ }
      }
    }

    if (reason) {
      try {
        fs.rmSync(outDir(id), { recursive: true, force: true });
        for (const [key, task] of [...taskLocks.entries()]) {
          if (String(task.songId) !== String(id)) continue;
          task.finished = true;
          if (task.timer) clearTimeout(task.timer);
          try { task.resolveMaster && task.resolveMaster(); } catch (e) {}
          taskLocks.delete(key);
        }
        building.delete(id);
        buildErrors.delete(id);
        removed++;
        log.info('HLS_CLEAN', `[歌曲 id=${id}] 缓存已清理，原因: ${reason}`);
      } catch (e) {
        log.error('HLS_CLEAN', `[歌曲 id=${id}] 缓存清理失败: ${e.message}`);
      }
    }
  }

  log.info('HLS_CLEAN', `本次清理完成：共检查 ${scanned} 个缓存目录，清理 ${removed} 个`);
  return { scanned, removed };
}

// 每日定时任务：容器可能一次运行好几天甚至更久不重启，只在启动时清理一次
// 不够，这里用 setInterval 每 24 小时自动跑一次；应用刚启动时也顺带跑一次
// （延迟 5 分钟，避开启动初期的曲库全量扫描高峰，避免两个都要读写磁盘的
// 后台任务互相抢 IO）。定时器用 unref()，避免它阻止 Node 进程正常退出。
function scheduleHLSCleanup(getValidSongIds) {
  const DAY_MS = 24 * 60 * 60 * 1000;
  setTimeout(() => cleanupExpiredHLS(getValidSongIds), 5 * 60 * 1000).unref();
  const timer = setInterval(() => cleanupExpiredHLS(getValidSongIds), DAY_MS);
  timer.unref();
  log.info('HLS_CLEAN', `HLS 缓存每日清理任务已注册，过期阈值 ${HLS_CACHE_MAX_AGE_DAYS} 天`);
}

if (!fs.existsSync(HLS_DIR)) fs.mkdirSync(HLS_DIR, { recursive: true });
// 启动时预热一次硬件加速自检，避免第一首点歌的用户额外多等这一次探测的耗时。
// 先探测 NVENC 再探测 VAAPI，与 buildVideoRendition 里的优先级顺序保持一致。
log.info('NVENC', '服务启动，开始预热 NVIDIA 显卡自检...');
detectNVENC().then(ok => {
  log.info('NVENC', `预热完成，NVIDIA 硬件加速当前${ok ? '可用' : '不可用'}`);
}).catch(() => {});
log.info('VAAPI', '服务启动，开始预热核显自检...');
detectVAAPI().then(ok => {
  log.info('VAAPI', `预热完成，核显硬件加速当前${ok ? '可用' : '不可用'}`);
}).catch(() => {});

/** 给 /hls 路由/日志用：当前这首歌正在跑的任务信息(没有则 null)。 */
function getActiveTaskInfo(songId) {
  const task = activeTaskForSong(songId);
  if (!task) return null;
  return { taskId: task.taskId, key: task.key, variant: task.variant, type: task.type, state: task.state, joins: task.joins || 0 };
}

module.exports = {
  cancelSong, guardStats: guard.stats,
  ensureHLS, removeHLS, outDir, masterPath, refreshMasterSwap, HLS_DIR, waitForFile, cleanupExpiredHLS, scheduleHLSCleanup,
  isBuilding, forgetBuildState, onBuildComplete, getActiveTaskInfo,
};
