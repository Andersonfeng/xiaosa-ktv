// 字幕探测与 WebVTT 转换辅助。
//
// 支持两类字幕：
//   1. 视频内嵌的文本字幕（mkv/mp4 里的 srt / ass / ssa / mov_text / webvtt 等）
//   2. 与视频同名的外挂字幕（.srt / .ass / .ssa / .vtt，可带 .zh / .chs 等语言后缀）
//
// PGS / DVD / DVB 这类位图字幕不在支持范围内：WebVTT 无法承载位图字幕，
// 强行转换只会得到空文件，所以探测阶段直接过滤掉，前端显示「无字幕」。
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');
const { promisify } = require('util');
const { TextDecoder } = require('util');

const execFileAsync = promisify(execFile);

// ffprobe 的 codec_name；这些是能转成 WebVTT 的文本字幕。
const TEXT_SUBTITLE_CODECS = new Set([
  'subrip', 'srt', 'ass', 'ssa', 'webvtt', 'mov_text', 'text',
]);

function escapeRegExp(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// 判断一个语言标签/标题/文件名是否像中文字幕。
// 支持 chi / zho / zh / zh-CN / zh_Hans / chs / cht / cn / sc / tc 以及
// "中文""中字""简体""繁体"等标题。
function isChineseText(...values) {
  return values.some(value => {
    const s = String(value || '').trim();
    if (!s) return false;
    if (/(?:^|[.\s_-])(zh|chi|zho|chs|cht|cn|sc|tc)(?:$|[.\s_-])/i.test(s)) return true;
    return /中文|中字|简体|繁体|简中|繁中/.test(s);
  });
}

// 查找与视频同名（可带语言后缀）的外挂字幕，例如：
//   周杰伦 - 稻香.mp4
//   周杰伦 - 稻香.srt
//   周杰伦 - 稻香.zh.srt
//   周杰伦 - 稻香.chs.ass
function findSidecarSubtitles(filepath) {
  const dir = path.dirname(filepath);
  const base = path.basename(filepath, path.extname(filepath));
  if (!base) return [];

  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (e) {
    return [];
  }

  const re = new RegExp('^' + escapeRegExp(base) + '(?:\\.[A-Za-z0-9_-]+)?\\.(srt|ass|ssa|vtt)$', 'i');
  const found = [];
  for (const entry of entries) {
    if (!entry.isFile()) continue;
    const match = re.exec(entry.name);
    if (!match) continue;
    found.push({
      filepath: path.join(dir, entry.name),
      format: match[1].toLowerCase(),
      isChinese: isChineseText(entry.name),
    });
  }

  // 中文字幕优先；同语言内按文件名稳定排序。
  found.sort((a, b) => {
    if (a.isChinese !== b.isChinese) return a.isChinese ? -1 : 1;
    return a.filepath.localeCompare(b.filepath, 'zh-CN');
  });
  return found;
}

// 用 ffprobe 读取内嵌字幕流。返回的 streamIndex 是「第几条字幕流」，
// 也就是 ffmpeg -map 0:s:N 里的 N；不能用 ffprobe 输出的全局 stream.index。
async function probeEmbeddedSubtitles(filepath) {
  let stdout;
  try {
    const result = await execFileAsync('ffprobe', [
      '-v', 'error',
      '-select_streams', 's',
      '-show_entries', 'stream=codec_name:stream_tags=language,title',
      '-of', 'json',
      filepath,
    ], { timeout: 15000, maxBuffer: 2 * 1024 * 1024 });
    stdout = result.stdout;
  } catch (e) {
    return [];
  }

  let parsed;
  try { parsed = JSON.parse(stdout || '{}'); } catch (e) { return []; }
  const streams = Array.isArray(parsed.streams) ? parsed.streams : [];

  const out = [];
  let ordinal = 0;
  for (const stream of streams) {
    const codec = String(stream.codec_name || '').toLowerCase();
    const tags = stream.tags || {};
    if (TEXT_SUBTITLE_CODECS.has(codec)) {
      out.push({
        kind: 'embedded',
        streamIndex: ordinal,
        codec,
        language: String(tags.language || ''),
        title: String(tags.title || ''),
        isChinese: isChineseText(tags.language, tags.title),
      });
    }
    ordinal++;
  }
  return out;
}

// 返回按「默认显示优先级」排好序的字幕源：
//   中文内嵌 -> 中文外挂 -> 非中文内嵌 -> 非中文外挂
// 前端目前只使用第一条（显示/隐藏开关），后续要做字幕轨选择时可以直接用这个列表。
async function probeSubtitleSources(filepath) {
  const [embedded, sidecars] = await Promise.all([
    probeEmbeddedSubtitles(filepath),
    Promise.resolve(findSidecarSubtitles(filepath)),
  ]);

  const sources = [];
  for (const item of embedded) {
    sources.push({
      ...item,
      label: item.title || (item.language ? `内嵌字幕(${item.language})` : `内嵌字幕${item.streamIndex + 1}`),
    });
  }
  for (const item of sidecars) {
    sources.push({
      kind: 'sidecar',
      filepath: item.filepath,
      format: item.format,
      codec: item.format,
      language: '',
      title: '',
      label: path.basename(item.filepath),
      isChinese: item.isChinese,
    });
  }

  // 只有 ASS/SSA 才可能带 \kf 逐字卡拉OK时间；KTV 工具生成的软字幕就是
  // 追加在最后一条的 ASS 轨，所以优先选 ASS，并在多条 ASS 里选最后一条。
  // 没有 ASS 时再按语言优先（中文优先）。
  const isAss = s => s.codec === 'ass' || s.codec === 'ssa' || s.format === 'ass' || s.format === 'ssa';
  const rank = s => isAss(s) ? 0 : (s.isChinese ? 1 : 2);
  sources.sort((a, b) => {
    const ra = rank(a), rb = rank(b);
    if (ra !== rb) return ra - rb;
    if (isAss(a) && isAss(b)) return (b.streamIndex || 0) - (a.streamIndex || 0);
    return 0;
  });
  return sources;
}

// 外挂字幕常见的编码：UTF-8、UTF-16(BOM)、GBK/GB18030。
// ffmpeg 默认按 UTF-8 读字幕，GBK 文件会变成乱码；这里简单探测一下，
// 给 -sub_charenc 用。探测失败时保守按 UTF-8 处理。
function detectSidecarEncoding(filepath) {
  let buf;
  try { buf = fs.readFileSync(filepath); } catch (e) { return 'utf-8'; }
  if (buf.length >= 3 && buf[0] === 0xEF && buf[1] === 0xBB && buf[2] === 0xBF) return 'utf-8';
  if (buf.length >= 2 && buf[0] === 0xFF && buf[1] === 0xFE) return 'utf-16le';
  if (buf.length >= 2 && buf[0] === 0xFE && buf[1] === 0xFF) return 'utf-16be';

  const utf8 = buf.toString('utf8');
  if (!utf8.includes('\uFFFD')) return 'utf-8';

  try {
    const gb = new TextDecoder('gb18030').decode(buf);
    if (!gb.includes('\uFFFD')) return 'gb18030';
  } catch (e) { /* 环境不支持 gb18030 时保持 utf-8 */ }
  return 'utf-8';
}

module.exports = {
  TEXT_SUBTITLE_CODECS,
  isChineseText,
  findSidecarSubtitles,
  probeEmbeddedSubtitles,
  probeSubtitleSources,
  detectSidecarEncoding,
};
