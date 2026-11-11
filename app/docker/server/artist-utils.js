// 多歌手拆分规则（纯函数，不依赖数据库，方便单独测试，也供 db.js 回填复用）。
//
// 说明：这里只负责把 songs.artist 里的「多歌手字符串」按配置的分隔符拆成
// 单个歌手名，用于 song_artists 关联表；不负责文件名 -> 歌手/歌名 的解析，
// 那部分仍然由 scanner.js 的 parseFilename / filenameTemplate.js 负责。
//
// 例如：
//   artist = "周杰伦&费玉清", separator = "&" -> ["周杰伦", "费玉清"]
//   artist = "刀郎 张三",     separator = " " -> ["刀郎", "张三"]
//   artist = "Simon & Garfunkel", separator = "&" -> ["Simon", "Garfunkel"]
// 最后一种属于分隔符本身的歧义，后台会提示管理员谨慎选择。

const DEFAULT_ARTIST_SEPARATOR = '&';

// 把用户输入规范化为实际使用的分隔符：
//   - " & "   -> "&"（忽略首尾空白）
//   - ""      -> ""（不拆分）
//   - "   "   -> " "（全是空白时按一个空格分隔，兼容旧版空格约定）
//   - 含换行  -> null（非法）
function normalizeArtistSeparator(value) {
  if (typeof value !== 'string') return null;
  if (/[\r\n]/.test(value)) return null;
  const trimmed = value.trim();
  if (trimmed) return trimmed;
  return value.length ? ' ' : '';
}

// 按分隔符拆分歌手字段。每个歌手名会 trim，空片段和重复项会被去掉。
// separator 传 undefined 时使用默认值 "&"；传空字符串表示不拆分。
function splitArtists(artist, separator) {
  const raw = String(artist == null ? '' : artist).trim();
  if (!raw) return [];

  const sep = separator === undefined ? DEFAULT_ARTIST_SEPARATOR : separator;
  if (!sep) return [raw];

  const seen = new Set();
  const names = [];
  for (const part of raw.split(sep)) {
    const name = part.trim();
    if (!name || seen.has(name)) continue;
    seen.add(name);
    names.push(name);
  }

  // 极端情况：artist 本身就是分隔符（例如 "&"），拆完没有有效名字，
  // 此时保留原值，避免这首歌从歌手列表里彻底消失。
  return names.length ? names : [raw];
}

module.exports = {
  DEFAULT_ARTIST_SEPARATOR,
  normalizeArtistSeparator,
  splitArtists,
};
