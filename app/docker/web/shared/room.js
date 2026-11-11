/* 多房间(v1.3.0)：网页 TV 页 / 手机遥控页共用的"房间"逻辑。
 *
 * 做三件事：
 *  1) 记住当前房间：网址 ?room=<房间码> 优先(并记入 localStorage)，否则用上次选的；
 *     都没有 = 默认房间(房间码为空串)。老二维码/老网址不带 room，行为和以前一样。
 *  2) 给房间相关请求自动带上房间：fetch 的 /api/queue*、/api/history* 加请求头 X-Room /
 *     X-Room-Token；WebSocket 连 /ws 时自动补 ?room=&rt=。页面里原有的 fetch/new WebSocket
 *     调用一行都不用改。
 *  3) 处理房间错误：需要密码(弹窗输入后换取令牌再刷新)、房间不存在/已被删除(回默认房间)。
 *
 * 酒店包房隔离(各房间互不打扰)：有密码的房间，每次进入、每次切换都必须重新输入密码——
 *  - 房间令牌只放在 sessionStorage(当前标签页)，不写 localStorage、服务端也不再下发 Cookie；
 *    关掉页面就失效，下次扫码/打开必须重新输入密码；
 *  - 切换到别的房间(或网址里的 ?room= 换成另一个房间)时，其它房间的令牌一律清除，所以切回原来的房间也要再输密码；
 *  - 同一个标签页内因为"输入密码后自动刷新页面"产生的刷新不会再要密码。
 *     连接被拒后设置 KtvRoom.blocked=true，页面据此不再无限自动重连。
 * 页面只需要：显示房间名(给元素加 data-room-name 属性自动填充)，和提供"选房间"界面
 * (用 KtvRoom.list() 取列表、KtvRoom.choose(room) 选定)。
 * 写成 ES5 语法，老电视浏览器也能解析。
 */
(function () {
  'use strict';
  var LS_CODE = 'ktv_room_code', LS_NAME = 'ktv_room_name', LS_PICKED = 'ktv_room_picked', LS_TOKEN = 'ktv_room_token_';
  function lsGet(k) { try { return window.localStorage.getItem(k); } catch (e) { return null; } }
  function lsSet(k, v) { try { window.localStorage.setItem(k, v); } catch (e) {} }
  function lsDel(k) { try { window.localStorage.removeItem(k); } catch (e) {} }
  // 房间令牌只放 sessionStorage(当前标签页)，见文件头说明
  function ssGet(k) { try { return window.sessionStorage.getItem(k); } catch (e) { return null; } }
  function ssSet(k, v) { try { window.sessionStorage.setItem(k, v); } catch (e) {} }
  function ssDel(k) { try { window.sessionStorage.removeItem(k); } catch (e) {} }
  // 清掉除 keepCode 之外所有房间的令牌(keepCode 传 null = 全清)
  function clearTokensExcept(keepCode) {
    try {
      var keys = [];
      for (var i = 0; i < window.sessionStorage.length; i++) {
        var k = window.sessionStorage.key(i);
        if (k && k.indexOf(LS_TOKEN) === 0 && (keepCode === null || k !== LS_TOKEN + keepCode)) keys.push(k);
      }
      for (var j = 0; j < keys.length; j++) window.sessionStorage.removeItem(keys[j]);
    } catch (e) {}
  }

  // 网址里的 ?room= 优先并记住；?room= (空值) 表示明确选了默认房间
  var m = /[?&]room=([^&#]*)/.exec(window.location.search || '');
  if (m) {
    var fromUrl = '';
    try { fromUrl = decodeURIComponent(m[1]); } catch (e) { fromUrl = m[1]; }
    if (fromUrl !== (lsGet(LS_CODE) || '')) { lsDel(LS_NAME); clearTokensExcept(fromUrl); }   // 换了房间：其它房间的令牌全部作废
    lsSet(LS_CODE, fromUrl);
    lsSet(LS_PICKED, '1');
  }
  var code = lsGet(LS_CODE) || '';                 // '' = 默认房间
  var token = ssGet(LS_TOKEN + code) || '';

  // v1.4.3 扫码免密：二维码网址带 k=<扫码入房钥匙>(只对网址里 room= 那个房间有效)。
  // 先把它当作临时令牌立即生效(同步，页面第一个请求/WS 就能用)，再异步换成正常的 12 小时令牌；
  // 随后把 k 从地址栏抹掉，避免被截图/转发。切换到别的房间仍然要输密码(clearTokensExcept 会清掉)。
  var scanKey = '';
  (function () {
    var km = /[?&]k=([^&#]*)/.exec(window.location.search || '');
    if (!km) return;
    try { scanKey = decodeURIComponent(km[1]); } catch (e) { scanKey = km[1]; }
    if (scanKey) { token = scanKey; ssSet(LS_TOKEN + code, scanKey); }
    try {
      var clean = window.location.search.replace(/([?&])k=[^&#]*&?/, '$1').replace(/[?&]$/, '');
      window.history.replaceState(null, '', window.location.pathname + clean + (window.location.hash || ''));
    } catch (e) {}
  })();

  var KtvRoom = window.KtvRoom = {
    blocked: false,
    code: function () { return code; },
    token: function () { return token; },
    name: function () { return lsGet(LS_NAME) || (code ? '房间 ' + code : '默认房间'); },
    // 二维码/分享链接要带的查询串：默认房间不带，保持和老二维码完全一致
    mobileQuery: function () { return code ? '?room=' + encodeURIComponent(code) : ''; },
    // 页面可赋值：首次访问且存在多个房间时调用一次，让页面弹出"选房间"界面
    onNeedPick: null
  };

  function fillNames() {
    var els = document.querySelectorAll ? document.querySelectorAll('[data-room-name]') : [];
    for (var i = 0; i < els.length; i++) els[i].textContent = KtvRoom.name();
  }

  function jsonOrNull(r) { return r.json().then(function (j) { return j; }, function () { return null; }); }

  KtvRoom.list = function () {
    return fetch('/api/rooms').then(jsonOrNull).then(function (d) { return (d && d.rooms) || []; });
  };

  // 加入房间(校验密码)：serverCode 为空 = 默认房间。返回 {ok,status,json}
  KtvRoom.join = function (serverCode, password) {
    var body = {};
    if (serverCode) body.code = serverCode;
    if (password) body.password = password;
    return fetch('/api/rooms/join', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    }).then(function (r) {
      return jsonOrNull(r).then(function (j) { return { ok: r.ok, status: r.status, json: j || {} }; });
    });
  };

  // 二维码网址：当前房间有密码时附带一把新的扫码入房钥匙(拿不到就退回不带钥匙的老链接，手机端会要求输密码)
  KtvRoom.scanUrl = function (base) {
    var plain = base + KtvRoom.mobileQuery();
    var h = {};
    if (code) h['X-Room'] = code;
    if (token) h['X-Room-Token'] = token;
    return fetch('/api/rooms/scan-key', { headers: h }).then(jsonOrNull).then(function (j) {
      if (j && j.key) return plain + (code ? '&' : '?') + 'k=' + encodeURIComponent(j.key);
      return plain;
    }, function () { return plain; });
  };

  // 选定房间并刷新页面(最简单可靠：队列/WS/播放端身份全部按新房间重新初始化)
  KtvRoom.select = function (selCode, name, tk) {
    lsSet(LS_CODE, selCode || '');
    if (name) lsSet(LS_NAME, name); else lsDel(LS_NAME);
    if ((selCode || '') !== code) clearTokensExcept(selCode || '');   // 切换房间：其它房间的令牌一律清除
    if (tk) ssSet(LS_TOKEN + (selCode || ''), tk);
    lsSet(LS_PICKED, '1');
    window.location.href = window.location.pathname + '?room=' + encodeURIComponent(selCode || '');
  };

  // 弹窗输入房间密码并换取令牌；密码错误会重新弹出，取消返回 false
  function askJoin(selCode, serverCode, roomName) {
    var pw = window.prompt('「' + roomName + '」需要房间密码，请输入：');
    if (pw === null) return Promise.resolve(false);
    return KtvRoom.join(serverCode, pw).then(function (r) {
      if (r.ok) { if (r.json.token) ssSet(LS_TOKEN + selCode, r.json.token); return true; }
      if (r.status === 401) { window.alert('房间密码错误，请重试'); return askJoin(selCode, serverCode, roomName); }
      window.alert((r.json && r.json.error) || '加入房间失败');
      return false;
    });
  }

  // 页面"选房间"界面点中某个房间时调用(room 来自 KtvRoom.list())
  KtvRoom.choose = function (room) {
    var selCode = room.isDefault ? '' : room.code;
    if (!room.hasPassword) { KtvRoom.select(selCode, room.name, ''); return; }
    // 酒店包房隔离：有密码的房间每次进入/切换都必须输入密码，不复用任何已保存的令牌
    askJoin(selCode, room.isDefault ? '' : room.code, room.name).then(function (ok) {
      if (ok) KtvRoom.select(selCode, room.name, ssGet(LS_TOKEN + selCode));
    });
  };

  // 扫码进来的：用钥匙换正常令牌(失败就继续用钥匙，3 小时内照常可用)
  if (scanKey) {
    fetch('/api/rooms/join', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: code || undefined, scanKey: scanKey }) })
      .then(jsonOrNull).then(function (j) {
        if (j && j.ok && j.token) { token = j.token; ssSet(LS_TOKEN + code, j.token); }
      }, function () {});
  }

  var handling = false;
  function denied(kind, room) {
    KtvRoom.blocked = true;   // 页面据此停止无限重连
    if (handling) return;
    handling = true;
    if (kind === 'password') {
      ssDel(LS_TOKEN + code);  // 旧令牌已失效(密码被改)
      var name = (room && room.name) || KtvRoom.name();
      askJoin(code, (room && !room.isDefault) ? room.code : '', name).then(function (ok) {
        if (ok) { window.location.reload(); return; }
        handling = false;
        if (code !== '') KtvRoom.select('', '', '');   // 取消输入密码：回默认房间
      });
    } else if (kind === 'closed') {
      window.alert('该房间已被管理员删除，将回到默认房间');
      KtvRoom.select('', '', '');
    } else {
      KtvRoom.select('', '', '');   // 房间不存在：回默认房间
    }
  }

  // ---- fetch：给房间相关接口带上房间标识 ----
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      var isRoomPath = false;
      if (typeof input === 'string') {
        var path = input.replace(/^https?:\/\/[^\/]+/, '').split('?')[0];
        isRoomPath = /^\/api\/(queue|history)(\/|$)/.test(path);
      }
      if (isRoomPath && (code || token)) {
        init = init || {};
        var h = {}, src = init.headers, k;
        if (src) {
          if (typeof Headers !== 'undefined' && src instanceof Headers) src.forEach(function (v, key) { h[key] = v; });
          else for (k in src) if (Object.prototype.hasOwnProperty.call(src, k)) h[k] = src[k];
        }
        if (code) h['X-Room'] = code;
        if (token) h['X-Room-Token'] = token;
        init.headers = h;
      }
      var p = origFetch.call(this, input, init);
      if (isRoomPath) {
        p = p.then(function (r) {
          if (r.status === 403 || r.status === 404) {
            r.clone().json().then(function (j) {
              if (j && j.code === 'room_password_required') denied('password', j.room);
              else if (j && j.code === 'room_not_found') denied('notfound');
            }).catch(function () {});
          }
          return r;
        });
      }
      return p;
    };
  }

  // ---- WebSocket：连 /ws 时自动补房间参数，并处理被拒/房间被删 ----
  var NativeWS = window.WebSocket;
  if (NativeWS) {
    var WrappedWS = function (url, protocols) {
      if (typeof url === 'string' && /\/ws$/.test(url.split('?')[0])) {
        var q = [];
        if (code) q.push('room=' + encodeURIComponent(code));
        if (token) q.push('rt=' + encodeURIComponent(token));
        if (q.length) url += (url.indexOf('?') < 0 ? '?' : '&') + q.join('&');
      }
      var ws = protocols !== undefined ? new NativeWS(url, protocols) : new NativeWS(url);
      ws.addEventListener('message', function (ev) {
        var msg;
        try { msg = JSON.parse(ev.data); } catch (e) { return; }
        if (!msg || !msg.type) return;
        if (msg.type === 'room_joined' && msg.room) {
          lsSet(LS_NAME, msg.room.name);
          fillNames();
        } else if (msg.type === 'room_denied') {
          denied(msg.reason === 'room_password_required' ? 'password' : 'notfound', msg.room);
        } else if (msg.type === 'room_closed') {
          denied('closed');
        } else if (msg.type === 'room_token' && msg.token) {
          // 管理员改了房间密码：当前播放端(电视网页)收到服务端推送的新令牌，不会因改密码掉线
          token = msg.token;
          ssSet(LS_TOKEN + code, msg.token);
        }
      });
      return ws;
    };
    WrappedWS.prototype = NativeWS.prototype;
    WrappedWS.CONNECTING = NativeWS.CONNECTING; WrappedWS.OPEN = NativeWS.OPEN;
    WrappedWS.CLOSING = NativeWS.CLOSING; WrappedWS.CLOSED = NativeWS.CLOSED;
    window.WebSocket = WrappedWS;
  }

  // ---- 页面就绪：填房间名；首次访问且有多个房间时提示选房间 ----
  function ready() {
    fillNames();
    if (!lsGet(LS_PICKED)) {
      lsSet(LS_PICKED, '1');   // 只提示一次，之后靠页面上的"房间"入口手动切换
      KtvRoom.list().then(function (rooms) {
        if (rooms.length > 1 && typeof KtvRoom.onNeedPick === 'function') setTimeout(KtvRoom.onNeedPick, 600);
      }).catch(function () {});
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready);
  else ready();
})();
