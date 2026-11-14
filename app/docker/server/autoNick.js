// ============ 模块：点歌昵称兜底（没填昵称时按客户端 IP 自动起名） ============
// 需求：原来不填昵称一律叫"匿名歌手"，已点队列里一排匿名根本分不清谁点的。
// 现在改成：手填了昵称就用填的；没填就按客户端 IP 的最后一段生成"13号麦霸"
// 这类名字——同一台设备(同一个 IP)每次点歌拿到的名字都一样，不会这次
// "13号麦霸"、下次"13号歌神"；后缀由 IP 哈希从词库里挑，纯粹为了好看，
// 没有业务含义。
//
// 注意：这只是"展示用昵称"的兜底，跟 /tv 登录账号、管理后台密码完全无关，
// 也不做任何身份校验——昵称本来谁都能随手填。
//
// 单独抽成模块是为了让本地预览代理(.tmppreview/preview-proxy.js)也能复用
// 同一份逻辑：本机没有 Node 20/Docker 时跑不起真服务端，但预览时点歌仍然
// 要看到和线上一致的名字，不能两边各写一套。

// 后缀词库纯装饰用，想加词往下加即可，不用改别的逻辑；词库长度变化会让
// 同一个 IP 重新分到一个后缀（因为按 哈希 % 词库长度 取），只影响之后新点
// 的歌，已经写进队列的历史名字不会变。
const AUTO_NICK_SUFFIXES = [
  // 第一批
  '麦霸', '歌神', '歌仙', '主唱', '气氛组', '点唱机', 'C位', '隐藏歌手', '麦手', '评委',
  // 第二批
  '开嗓王', '接歌侠', '伴唱官', '炸场王', '切歌员', '跟唱搭子', '金曲猎手', '降噪选手',
  '合唱搭子', '抢麦选手', '清唱大佬', '忘词大师', '转音怪', '曲库保管员', '捧场专员',
  '低音猎手', '升调选手', '压轴选手', '麦霸替补', '混响玩家',
];

function autoNickSuffix(seed) {
  if (!seed) return AUTO_NICK_SUFFIXES[0];
  // FNV-1a 32 位。这里不能用"逐字符乘 31 再取模"那种朴素写法：IPv4 只有末段
  // 几位数字在变，而 31^k ≡ 1 (mod 30)，朴素哈希的取模结果几乎等于字符码之和，
  // 实测 30 个后缀里有 5 个永远抽不到、其余也明显偏斜。FNV-1a 的乘法会把输入
  // 充分打散，末尾再做一次异或移位+乘法，避免取模只看低位带来的规律性。
  let h = 0x811c9dc5;
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  h ^= h >>> 15;
  h = Math.imul(h, 0x2545f491) >>> 0;
  h ^= h >>> 13;
  // 注意：Math.imul/异或的结果是有符号 32 位，可能为负；取模前必须转回无符号，
  // 否则会得到负下标，从词库里取到 undefined（变成"42号undefined"）。
  return AUTO_NICK_SUFFIXES[(h >>> 0) % AUTO_NICK_SUFFIXES.length];
}

// 取真实客户端 IP：直连时就是 socket 的 remoteAddress；套了反向代理(NAS 上的
// nginx 之类)时 remoteAddress 会变成 127.0.0.1/::1，这时退回 X-Forwarded-For
// 的第一段。IPv4-mapped 的 "::ffff:192.168.1.13" 统一剥成纯 IPv4。
function clientIpOf(req) {
  const direct = String((req.socket && req.socket.remoteAddress) || (req.connection && req.connection.remoteAddress) || '');
  const xff = String((req.headers && req.headers['x-forwarded-for']) || '').split(',')[0].trim();
  const chosen = /^(::1$|127\.|::ffff:127\.)/.test(direct) && xff ? xff : (direct || xff);
  return chosen.replace(/^::ffff:/i, '').trim();
}

// "192.168.1.13" -> "13号麦霸"；IPv6 取最后一段 16 进制折算成 1~250 的稳定编号；
// 完全拿不到地址时退化成"神秘麦霸"，总比一排"匿名歌手"强。
function autoNickname(req) {
  const ip = clientIpOf(req);
  const suffix = autoNickSuffix(ip);
  const v4 = ip.match(/(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    const last = parseInt(v4[4], 10);
    return last > 0 ? `${last}号${suffix}` : `神秘${suffix}`;
  }
  const tail = ip.split(':').filter(Boolean).pop() || '';
  const n = parseInt(tail, 16);
  if (Number.isFinite(n)) return `${(n % 250) + 1}号${suffix}`;
  return `神秘${suffix}`;
}

// 手填昵称优先；老版本前端不填昵称时会把"匿名歌手"原样传上来，这里也当成
// "没填"，这样旧页面不刷新也能立刻用上新的兜底名字。
function resolveQueueNickname(req) {
  const raw = String((req.body && req.body.nickname) || '').trim();
  return raw && raw !== '匿名歌手' ? raw : autoNickname(req);
}

module.exports = { AUTO_NICK_SUFFIXES, autoNickSuffix, clientIpOf, autoNickname, resolveQueueNickname };
