// ============================================================================
// ffmpegGuard.js —— ffmpeg 子进程看守（防止"ffmpeg 越积越多 -> CPU 100% -> 主机宕机"）
// ----------------------------------------------------------------------------
// 症状：点歌时偶发 ffmpeg 视频进程长期驻留，越积越多，直到 CPU 打满、主机无响应。
//       现场 ps：同时有多个 `ffmpeg -i /data/source-cache/xxx.mkv ... -vaapi_device
//       ... -vf format=nv12,hwupload -c:v h264_vaapi -f hls ...`（即 Tier2 软解+
//       VAAPI 硬编）在跑，父进程都是 node，状态 S（不是 Z，所以不是真僵尸，而是
//       "活着但永远不结束/不被回收"的进程）。
//
// 根因（原 hlsgen.js 里有 5 个叠加的问题）：
//   1) runFFmpeg() 只等 close/error，没有任何超时：VAAPI/驱动卡住时进程既不退出
//      也不报错，Promise 永远 pending。
//   2) 转码没有并发上限：队列预热会同时拉起一大批 ffmpeg，本来就慢的机器直接被
//      打满，"慢"和"卡死"叠在一起，越积越多。
//   3) removeHLS()/forgetBuildState() 只删目录、清 building 记录，不杀 ffmpeg：
//      歌曲被删/重扫后，旧 ffmpeg 继续跑；用户再点同一首歌，building 里没有记录，
//      又起一个新的 ffmpeg —— 同一首歌的 ffmpeg 越点越多。
//   4) ensureHLS 里 `.finally(() => building.delete(id))` 不看是不是自己：旧任务
//      收尾时会把"新任务"的记录删掉，造成重复转码。
//   5) 转码进程是普通优先级，几路并发就能把宿主机的其它服务（含 SSH/docker）饿死，
//      这是"宕机"而不仅仅是"慢"的直接原因。
//
// 本模块的做法：
//   A) 按【进度】判断卡死，而不是只靠一个 30 分钟的墙钟超时：给每个 ffmpeg 加
//      `-progress pipe:1`，主循环还活着就会持续输出进度；启动后迟迟没有第一条
//      进度（hwupload/驱动初始化卡死）、或者进度停滞超过阈值，直接 SIGKILL。
//      墙钟超时仍保留作为最后兜底。
//   B) 卡死的是硬件路径（VAAPI/NVENC）时，对该硬件"熔断"一段时间：期间所有 VAAPI
//      调用立刻失败、转码走 libx264，不会每首歌都再卡一次、再多积一个僵住的进程。
//   C) 全局转码并发池（默认 2 首歌），正在播放的歌可优先/额外插队，不会被预热挤住。
//   D) 按歌曲取消：removeHLS 等处调用 cancelSong(id)，杀掉该歌所有 ffmpeg、移出
//      排队，并让该歌正在跑的"多级回退"链条不再拉起新的 ffmpeg。
//   E) 所有 ffmpeg 以低优先级(nice)运行，转码再忙也饿不死宿主机。
//   F) 进程退出/收到 SIGTERM/SIGINT 时杀光子进程；启动时、以及之后每 2 分钟扫描
//      /proc，清理"不在本进程名单里"的孤儿 ffmpeg(HLS 转码特征)。
//
// 可调环境变量（都有默认值，不设也行）：
//   HLS_FFMPEG_TIMEOUT_MS      单个 ffmpeg 墙钟上限，默认 1800000(30 分钟)
//   HLS_FFMPEG_STARTUP_MS      启动后多久还没有第一条进度就判卡死，默认 90000
//   HLS_FFMPEG_STALL_MS        进度停滞多久判卡死，默认 90000
//   HLS_MAX_CONCURRENT_BUILDS  同时转码的歌曲数，默认 2
//   HLS_FFMPEG_NICE            ffmpeg 的 nice 值(0~19)，默认 15
//   HLS_HW_COOLDOWN_MS         硬件加速熔断时长，默认 1800000(30 分钟)
// ============================================================================
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');
const { AsyncLocalStorage } = require('async_hooks');
const log = require('./logger');

const HLS_DIR = process.env.HLS_DIR || '/data/hls';
const num = (v, d) => (Number.isFinite(Number(v)) && Number(v) > 0 ? Number(v) : d);

const FFMPEG_TIMEOUT_MS = Math.max(60 * 1000, num(process.env.HLS_FFMPEG_TIMEOUT_MS, 30 * 60 * 1000));
const STARTUP_STALL_MS = Math.max(10 * 1000, num(process.env.HLS_FFMPEG_STARTUP_MS, 90 * 1000));
const STALL_MS = Math.max(10 * 1000, num(process.env.HLS_FFMPEG_STALL_MS, 90 * 1000));
const SELF_TEST_TIMEOUT_MS = 10 * 1000;
const MAX_CONCURRENT_BUILDS = Math.max(1, Math.floor(num(process.env.HLS_MAX_CONCURRENT_BUILDS, 2)));
// 正在播放的歌可以额外占用的紧急槽位。弱 NAS/核显并发会话有限时设为 0，
// 把"最多同时转码歌曲数"严格限制在 HLS_MAX_CONCURRENT_BUILDS。
const URGENT_EXTRA_SLOTS = (() => {
  const v = Number(process.env.HLS_URGENT_EXTRA_SLOTS);
  return Number.isFinite(v) ? Math.max(0, Math.min(2, Math.floor(v))) : 1;
})();
const NICE = (() => { const n = Math.floor(Number(process.env.HLS_FFMPEG_NICE ?? 15)); return Number.isFinite(n) ? Math.min(19, Math.max(0, n)) : 15; })();
const HW_COOLDOWN_MS = num(process.env.HLS_HW_COOLDOWN_MS, 30 * 60 * 1000);
const KILL_GRACE_MS = 5000;     // SIGKILL 之后最多等这么久再让上层继续(D 状态进程可能一时回收不了)
const WATCH_INTERVAL_MS = 5000; // 看门狗轮询间隔

// ---------- 进程登记 ----------
// ff -> { songId, label, hw, startedAt, alive, lastAdvanceAt, sig, hardKilled }
const procs = new Map();

function isCancelledError(e) { return !!(e && e.cancelled); }
function cancelledError(label) {
  const e = new Error(`${label ? `[${label}] ` : ''}任务已被取消(歌曲被删除/重扫/缓存被清理)，不再启动 ffmpeg`);
  e.cancelled = true;
  return e;
}

// ---------- 硬件熔断 ----------
const hwTrippedUntil = { vaapi: 0, nvenc: 0 };
function isHwTripped(hw) { return !!hw && Date.now() < (hwTrippedUntil[hw] || 0); }
function tripHw(hw, reason, taskId) {
  if (!hw) return;
  const was = isHwTripped(hw);
  hwTrippedUntil[hw] = Date.now() + HW_COOLDOWN_MS;
  if (!was) {
    log.error('FFMPEG_GUARD', `${taskId ? `[task=${taskId}] ` : ''}${hw.toUpperCase()} 硬件加速疑似卡死(${reason})，熔断 ${Math.round(HW_COOLDOWN_MS / 60000)} 分钟：期间所有 ${hw.toUpperCase()} 调用直接跳过，转码走软件编码(libx264)；冷却结束后会自动重新尝试`);
  }
}
function detectHw(args, bin) {
  const joined = args.join(' ');
  if (/h264_nvenc|hevc_nvenc|-hwaccel cuda/.test(joined) || /nvenc/.test(bin)) return 'nvenc';
  if (/vaapi/.test(joined)) return 'vaapi';
  return null;
}

// ---------- 按歌曲取消 ----------
const songGen = new Map();          // songId -> 取消代数，每次 cancelSong 自增
const buildCtx = new AsyncLocalStorage();
const gen = key => songGen.get(key) || 0;

// ---------- 全局转码并发池 ----------
let running = 0;
const queue = []; // { key, urgent, resolve, reject }

function acquire(key, urgent) {
  const limit = MAX_CONCURRENT_BUILDS + (urgent ? URGENT_EXTRA_SLOTS : 0);
  if (running < limit && queue.length === 0) { running++; return Promise.resolve(); }
  if (urgent && running < limit) { running++; return Promise.resolve(); }
  return new Promise((resolve, reject) => {
    const e = { key, urgent: !!urgent, resolve, reject };
    if (urgent) queue.unshift(e); else queue.push(e);
  });
}
function drain() {
  while (queue.length) {
    const e = queue[0];
    const limit = MAX_CONCURRENT_BUILDS + (e.urgent ? URGENT_EXTRA_SLOTS : 0);
    if (running >= limit) break;
    queue.shift();
    running++;
    e.resolve();
  }
}
function release() { running = Math.max(0, running - 1); drain(); }

/** 把排队中的这首歌提到队首并允许占用"额外槽位"(正在被用户播放的歌不该被预热挤住)。 */
function promote(songId) {
  const key = String(songId);
  const i = queue.findIndex(e => e.key === key);
  if (i < 0) return false;
  const [e] = queue.splice(i, 1);
  e.urgent = true;
  queue.unshift(e);
  drain();
  return true;
}

/**
 * 包住一首歌的整个转码过程：占并发槽位 + 建立"这首歌的取消上下文"。
 * 必须在 ensureHLS 里【同步】调用(这样取消代数是转码任务创建那一刻的)。
 */
async function runBuild(songId, opts, fn) {
  const key = String(songId);
  const myGen = gen(key);
  const taskId = (opts && opts.taskId) || null;
  await acquire(key, !!(opts && opts.urgent));
  try {
    if (gen(key) !== myGen) throw cancelledError(`歌曲 id=${key}${taskId ? ` task=${taskId}` : ''}`);
    return await buildCtx.run({ songId: key, gen: myGen, taskId }, fn);
  } finally {
    release();
  }
}

/** 取消一首歌：杀掉它所有 ffmpeg、移出排队，并让它正在进行的多级回退不再起新进程。 */
function cancelSong(songId) {
  const key = String(songId);
  songGen.set(key, gen(key) + 1);
  for (let i = queue.length - 1; i >= 0; i--) {
    if (queue[i].key === key) { const [e] = queue.splice(i, 1); e.reject(cancelledError(`歌曲 id=${key}`)); }
  }
  let killed = 0;
  let taskId = null;
  for (const [ff, meta] of procs) {
    if (meta.songId === key) {
      if (!taskId && meta.taskId) taskId = meta.taskId;
      meta.abort ? meta.abort('cancel', true) : ff.kill('SIGKILL');
      killed++;
    }
  }
  if (killed) log.info('FFMPEG_GUARD', `${taskId ? `[task=${taskId}] ` : ''}[歌曲 id=${key}] 已取消，强制终止 ${killed} 个 ffmpeg 子进程`);
  return killed;
}

// ---------- 核心：runFFmpeg ----------
function runFFmpeg(args, bin = 'ffmpeg', opts = {}) {
  const label = opts.label || '';
  const lavfiSelfTest = args.includes('lavfi'); // VAAPI/NVENC 自检：与具体歌曲无关，只用短硬超时
  const ctx = lavfiSelfTest ? undefined : buildCtx.getStore();
  const timeoutMs = opts.timeoutMs || (lavfiSelfTest ? SELF_TEST_TIMEOUT_MS : FFMPEG_TIMEOUT_MS);
  const watchProgress = !lavfiSelfTest && opts.timeoutMs === undefined;
  const hw = detectHw(args, bin);

  return new Promise((resolve, reject) => {
    if (ctx && ctx.gen !== gen(ctx.songId)) return reject(cancelledError(label));
    if (hw && !lavfiSelfTest && isHwTripped(hw)) {
      return reject(new Error(`${label ? `[${label}] ` : ''}${hw.toUpperCase()} 硬件加速已熔断(此前检测到卡死)，跳过该路径`));
    }

    const fullArgs = watchProgress ? ['-progress', 'pipe:1', '-nostats', ...args] : args;
    const ff = spawn(bin, fullArgs, { stdio: ['ignore', watchProgress ? 'pipe' : 'ignore', 'pipe'] });
    const meta = {
      songId: ctx ? ctx.songId : null, taskId: ctx ? (ctx.taskId || null) : null, label, hw,
      startedAt: Date.now(), alive: false, lastAdvanceAt: Date.now(), sig: '', hardKilled: null,
    };
    procs.set(ff, meta);
    if (ff.pid) { try { os.setPriority(ff.pid, NICE); } catch (e) { /* 没权限就算了 */ } }

    let settled = false;
    let errBuf = '';
    let outBuf = '';
    let blockSig = '';
    let killTimer = null;

    const finish = (fn, v) => {
      if (settled) return;
      settled = true;
      clearInterval(watch);
      if (killTimer) clearTimeout(killTimer);
      fn(v);
    };

    ff.stderr.on('data', d => {
      errBuf += d.toString();
      if (errBuf.length > 4000) errBuf = errBuf.slice(-4000);
    });
    if (watchProgress) {
      ff.stdout.on('data', d => {
        outBuf += d.toString();
        let i;
        while ((i = outBuf.indexOf('\n')) >= 0) {
          const line = outBuf.slice(0, i).trim();
          outBuf = outBuf.slice(i + 1);
          if (line.startsWith('frame=') || line.startsWith('out_time_us=') || line.startsWith('total_size=')) {
            blockSig += line + '|';
          } else if (line.startsWith('progress=')) {
            meta.alive = true;
            if (blockSig !== meta.sig) { meta.sig = blockSig; meta.lastAdvanceAt = Date.now(); }
            blockSig = '';
          }
        }
        if (outBuf.length > 8192) outBuf = '';
      });
    }

    const killIt = (reason, isCancel) => {
      if (settled || meta.hardKilled) return;
      meta.hardKilled = isCancel ? 'cancel' : reason;
      try { ff.kill('SIGKILL'); } catch (e) { /* 忽略 */ }
      if (!isCancel && meta.hw) tripHw(meta.hw, `${label || bin}: ${reason}`, meta.taskId);
      const taskPrefix = meta.taskId ? `[task=${meta.taskId}] ` : '';
      const err = isCancel
        ? cancelledError(`${taskPrefix}${label}`)
        : Object.assign(new Error(`${taskPrefix}${label ? `[${label}] ` : ''}ffmpeg ${reason}，已强制终止(pid=${ff.pid})；命令: ${bin} ${args.slice(0, 10).join(' ')}...`), { stalled: true });
      // 先给内核一点时间回收，避免下一级回退和这个进程同时写同一批输出文件；
      // 进程卡在内核 D 状态收不掉时，最多等 KILL_GRACE_MS 就放行，不阻塞上层。
      killTimer = setTimeout(() => {
        if (procs.has(ff)) {
          log.error('FFMPEG_GUARD', `${label || bin} (pid=${ff.pid}) 收到 SIGKILL ${KILL_GRACE_MS}ms 后仍未退出，疑似卡在内核/GPU 驱动(D 状态)，需要宿主机侧排查驱动；已放行上层继续`);
        }
        finish(reject, err);
      }, KILL_GRACE_MS);
      meta.err = err;
    };
    meta.abort = killIt;

    const watch = setInterval(() => {
      const now = Date.now();
      if (meta.hardKilled) return;
      if (now - meta.startedAt > timeoutMs) {
        killIt(`运行超过 ${Math.round(timeoutMs / 1000)}s 仍未结束(墙钟超时)`);
      } else if (watchProgress) {
        if (!meta.alive && now - meta.startedAt > STARTUP_STALL_MS) {
          killIt(`启动 ${Math.round(STARTUP_STALL_MS / 1000)}s 内没有任何进度输出(疑似 hwupload/驱动初始化卡死)`);
        } else if (meta.alive && now - meta.lastAdvanceAt > STALL_MS) {
          killIt(`进度停滞超过 ${Math.round(STALL_MS / 1000)}s(疑似 GPU/驱动卡死)`);
        }
      }
    }, WATCH_INTERVAL_MS);
    watch.unref();

    ff.on('close', code => {
      procs.delete(ff);
      if (meta.hardKilled) return finish(reject, meta.err || cancelledError(label));
      if (code === 0) finish(resolve);
      else finish(reject, new Error(`${bin} exit ${code}: ${errBuf}`));
    });
    ff.on('error', err => {
      procs.delete(ff);
      finish(reject, err);
    });
  });
}

// ---------- 全员清理 ----------
function killAllFFmpeg() {
  let n = 0;
  for (const ff of procs.keys()) { try { ff.kill('SIGKILL'); n++; } catch (e) { /* 忽略 */ } }
  return n;
}

/** 扫描 /proc，杀掉"不是本进程启动的" HLS 转码 ffmpeg(上一个进程遗留的孤儿)。 */
function reapOrphanFFmpeg() {
  let names;
  try { names = fs.readdirSync('/proc').filter(n => /^\d+$/.test(n)); } catch (e) { return 0; }
  const ours = new Set([...procs.keys()].map(f => f.pid));
  let killed = 0;
  for (const n of names) {
    const pid = Number(n);
    if (pid === process.pid || ours.has(pid)) continue;
    let argv;
    try { argv = fs.readFileSync(`/proc/${n}/cmdline`, 'utf8').split('\0'); } catch (e) { continue; }
    if (!/^ffmpeg(-nvenc)?$/.test(path.basename(argv[0] || ''))) continue;
    const i = argv.indexOf('-hls_segment_filename');
    if (i < 0 || !String(argv[i + 1] || '').startsWith(HLS_DIR)) continue;
    try {
      process.kill(pid, 'SIGKILL');
      killed++;
      log.warn('FFMPEG_GUARD', `清理孤儿 ffmpeg pid=${pid}(不属于当前服务进程的 HLS 转码)`);
    } catch (e) { /* 已经没了 */ }
  }
  return killed;
}

function stats() {
  return {
    running, queued: queue.length, ffmpeg: procs.size,
    hwTripped: { vaapi: isHwTripped('vaapi'), nvenc: isHwTripped('nvenc') },
  };
}

// ---------- 进程生命周期 ----------
process.on('exit', () => { killAllFFmpeg(); });
for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => {
    const n = killAllFFmpeg();
    if (n) log.info('FFMPEG_GUARD', `收到 ${sig}，已终止 ${n} 个 ffmpeg 子进程`);
    // 只有别处没有注册同一信号的处理函数时才由这里退出，避免抢掉 index.js 的优雅退出逻辑。
    // (Docker 里 node 是 PID 1，没有信号处理函数时 SIGTERM 会被直接忽略，
    //  docker stop 只能等 10 秒后强杀，所以这里至少要保证能退出。)
    if (process.listenerCount(sig) <= 1) process.exit(0);
  });
}

const startupReaped = reapOrphanFFmpeg();
if (startupReaped) log.warn('FFMPEG_GUARD', `启动清理：终止了 ${startupReaped} 个上次运行遗留的孤儿 ffmpeg`);
setInterval(() => {
  reapOrphanFFmpeg();
  if (procs.size > MAX_CONCURRENT_BUILDS * 4) {
    log.warn('FFMPEG_GUARD', `ffmpeg 子进程数偏多(${procs.size})，并发池状态: ${JSON.stringify(stats())}`);
  }
}, 2 * 60 * 1000).unref();

module.exports = {
  runFFmpeg, runBuild, cancelSong, promote, killAllFFmpeg, reapOrphanFFmpeg,
  isHwTripped, isCancelledError, stats, SELF_TEST_TIMEOUT_MS,
};
