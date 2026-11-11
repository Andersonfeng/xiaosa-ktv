// 多歌手分隔符的持久化配置。
//
// 存在 settings 表（key = artist_separator），跟随 /data 持久化。默认值：
//   - 全新数据库：'&'
//   - 从旧版本升级、且已有 songs 表的数据库：' '（空格，保持旧版行为）
// 这个初始值由 db.js 在启动时写入；本模块只负责读取/更新，并做进程内缓存。
const db = require('./db');
const { DEFAULT_ARTIST_SEPARATOR } = require('./artist-utils');

const ARTIST_SEPARATOR_KEY = 'artist_separator';

let cachedSeparator;

function getArtistSeparator() {
  if (cachedSeparator === undefined) {
    let stored = null;
    try {
      const row = db.prepare('SELECT value FROM settings WHERE key = ?').get(ARTIST_SEPARATOR_KEY);
      stored = row ? row.value : null;
    } catch (e) {
      stored = null;
    }
    cachedSeparator = stored === null ? DEFAULT_ARTIST_SEPARATOR : stored;
  }
  return cachedSeparator;
}

function setArtistSeparator(value) {
  db.prepare(`
    INSERT INTO settings (key, value) VALUES (?, ?)
    ON CONFLICT(key) DO UPDATE SET value = excluded.value
  `).run(ARTIST_SEPARATOR_KEY, value);
  cachedSeparator = value;
}

module.exports = {
  ARTIST_SEPARATOR_KEY,
  getArtistSeparator,
  setArtistSeparator,
};
