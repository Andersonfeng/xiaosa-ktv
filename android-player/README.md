# 潇洒K歌 · 电视播放端 APK

这是给电视盒子 / 投影仪用的 **WebView 外壳**：APK 本身不重复实现播放器，而是加载
服务端的 `/tv` 网页大屏端，所以字幕、原伴唱、三套皮肤、手机遥控等能力与浏览器版完全一致。

- 包名：`com.xiaosa.ktv`
- 版本：`1.2.0`（versionCode 4）
- 最低 Android：5.1（API 21）
- 目标 Android：13（API 33）
- 安装包：`dist/潇洒K歌-TV播放端-v1.2.0.apk`（约 145 KB）
- 签名证书：`keystore/xiaosa-release.jks`（别名 `xiaosa`，密码 `xiaosa123`）
  —— 和 v1.0.0 使用同一份证书，可以直接覆盖安装升级。

## v1.2.0 新增：老内核自动走 `/tv_old`

- 新增 **`app/docker/web/tv_old/index.html`**（单页）：由 `/tv/index.html` 生成，
  JS 用 esbuild 转译到 Chrome 52，CSS Grid 用 flex 回退；业务逻辑与 `/tv` 相同。
- APK 启动时检测 WebView 版本：**< Chrome/60 加载 `/tv_old`，>=60 加载 `/tv`**，
  自动切换入口，不需要用户改设置。
- `/tv_old` 同样需要 TV 登录：服务端新增了静态路由和登录门禁，
  未登录会跳到 `/tv/login.html?to=/tv_old/index.html`；APK 的原生登录照常工作。
- **需要更新服务端镜像**（包含 `web/tv_old`）后才会生效；只换 APK 而服务端没有这个页面，
  会 404。
- 布局是重新写的 flex 版：主界面、歌名/歌手/分类/搜索、排行/最新、字母键盘、已点队列
  等主要面板都做了回退；个别弹层/主题 3 的装饰可能和原版不完全一样。
- 功能逻辑没有删减；已知兼容性风险是 **Chrome 52 的 MSE/音频轨**：如果 HLS 不可用，
  页面会回退到 `/stream` 直传，此时原唱/伴唱切换可能失效（取决于盒子 WebView 的 MSE 支持）。

### 维护 `/tv_old`

`app/docker/web/tv_old/index.html` 是 `app/docker/web/tv/index.html` 的**生成快照**：

- 内联 JS 用 esbuild `--target=chrome52` 转译，`async/await` 会变成生成器 + helper；
- 补了 `Object.entries` / `padStart` / `Object.getOwnPropertyDescriptors` 等 polyfill；
- CSS Grid 在 `@supports not (display:grid)` 里退回 flex；
- `fonts/`、`images/` 这类相对路径改成了 `/tv/fonts/`、`/tv/images/`，因为页面现在挂在 `/tv_old/` 下；
- `karaoke-ass.js` 内联成转译版，`room.js` / `qrcode.min.js` / `hls.min.js` 继续走 `/shared`。

以后改了 `/tv/index.html`，需要按同样方式重新生成本页，否则两边会逐渐漂移。

## v1.1.1 修了什么

v1.1.1 只改“内核太旧”提示页：加了「用浏览器打开」「WebView 设置」两个按钮、
显示 CPU 架构，并把“装 Chrome 之后要切换 WebView 实现”的步骤写清楚。
网页端和原生登录逻辑与 v1.1.0 相同。

针对电视盒子上的实际反馈（老盒子上网页登录页排版很大/很乱、遥控器选不中输入框、
自动搜索找不到 8083），这一版做了四件事：

1. **原生登录页，不再走网页登录**
   - 老盒子的 WebView 内核通常低于 Chrome 79，网页登录页里的 `width:min(...)`
     会被整条忽略，logo 按 1000px 原图撑开，导致卡片溢出、输入框跑到屏幕外，
     遥控器自然“选不中输入框”。
   - v1.1 直接用原生界面登录：账号 / 密码 + 自带屏幕键盘，完全不依赖网页登录页，
     也不依赖盒子的系统输入法。登录成功后拿到 `ktv_tv_session` Cookie 再进 `/tv`。
2. **自带遥控器屏幕键盘**
   - 全键盘（字母/数字/符号/空格/退格/清空/完成）用于登录；
   - 数字键盘（0-9、`.`、`:`、退格）用于输入服务器 IP / 端口。
   - 遥控器方向键移动，OK 输入；“完成”在登录页会自动按 账号 → 密码 → 登录 前进。
3. **自动发现更可靠**
   - 扫描本机**所有** IPv4 网卡所在的 /24（Wi-Fi、有线、热点；/22、/23 也能覆盖）。
   - 额外扫描默认网关所在网段；本机网段找不到时再扩大搜索
     `192.168.0.x`、`192.168.1.x`、`10.0.0.x` 这些最常见的网段。
   - 兼容老服务端：没有 `/api/discover` 时回退到 `/api/rooms`、`/tv/login.html`
     识别，不再只能手动输入 IP。
   - 界面上直接显示「本机 IP/掩码、网关、WebView 版本」，找不到时能一眼看出
     电视和服务器是不是不在同一网段。
4. **WebView 版本检测**
   - 电视页主脚本要求 Chrome/60+（页面用了 `const`、箭头函数、`async/await`、
     对象展开等语法）。内核太旧时脚本整段解析失败，表现就是页面有壳、右上角
     永远「连接中…」、遥控器按什么都没反应。
   - v1.1 会在页面加载后检查脚本是否真的启动；没启动就显示明确的
     「浏览器内核太旧」页面，并给出当前 WebView 版本和升级建议。
5. **顺手修了电视页两处老内核 CSS 兼容问题**
   - 空闲态大图的 `width:min(380px,60%)` 在 Chrome 79 以下整条失效，图片会按
     原图尺寸撑开；已改成 `width:60%;max-width:380px`。
   - 字幕层的 `inset:0` 在 Chrome 87 以下不生效，字幕层会变成 0×0；已改成
     `top/right/bottom/left` 四个长写属性。
   - 服务端 `tv/index.html`、`tv/clean.html`、`tv/login.html` 已同步修改；
     APK 加载 `/tv` 后也会再注入一份覆盖样式，没升级服务端也能生效。

## 为什么 APK 只有 ~145 KB，参考的 APK 有 13.6 MB？

因为两者是两种不同的产品形态，不是“缺了东西”：

| | 参考的原生 APK（骏耀K歌 1.2.2） | 本 APK（潇洒K歌 1.2.0） |
|---|---|---|
| 实现方式 | 纯 Kotlin 原生 App（Jetpack + AndroidX Media3/ExoPlayer 1.4.1 + OkHttp + Glide） | Java WebView 外壳 |
| 代码体积 | `classes.dex` 8.1 MB + `classes3.dex` 9.0 MB（未压缩） | `classes.dex` 约 76 KB |
| 图片/字体/音效 | 把 6.2 MB 字体、主题背景、5 个音效 mp3 全打进 `res/` | 全部由服务端 `/tv` 提供，APK 里只有图标和 banner |
| 播放器 | 自带 ExoPlayer 解码 | 用盒子系统 WebView + 页面里的 hls.js |
| 包大小 | 13.6 MB | 145 KB |
| 老盒子兼容 | 原生 UI，Android 5.1 就能跑 | Chrome/60+ 走 `/tv`；更老自动走 `/tv_old` |

换句话说：**参考 APK 把整个播放器和素材都背在身上；本 APK 只背了一个浏览器壳，
素材和播放逻辑都放在服务器上。** 包小不代表功能少，但代价是对盒子 WebView 版本有要求，
而且第一次打开必须能连上服务器。如果希望盒子在很老的内核上也能用，最终需要像参考包
那样做原生播放器（工作量大得多）；当前这版先把“能登录、能找到服务器、能诊断”补上。

## 盒子 WebView 是 Chrome/52 怎么办（Android 7.1.2）

Chrome/52 是 Android 7.1 系统自带 WebView 的版本，而 `/tv` 需要 Chrome/60：
Chrome 52 不支持 `async/await`（55）、对象展开（60）、CSS Grid（57）。

**v1.2.0 起不需要再折腾 WebView 了**：APK 检测到内核 <60 会自动加载
**`/tv_old`** 兼容页（功能同 `/tv`，JS 已转译、CSS Grid 换成 flex）。

### 方案 A（推荐）：更新服务端 + 安装 v1.2.0

1. 把本仓库的 `app/docker/web/tv_old/` 一起更新到服务端，重新构建/重启容器；
2. 电视盒子安装 `潇洒K歌-TV播放端-v1.2.0.apk`；
3. 打开 App，它会自动走 `/tv_old`，原生登录、自动发现、遥控器和变色歌词都保留。

如果只换 APK、服务端还是旧镜像（没有 `/tv_old`），页面会 404——先更新服务端。

### 方案 B（可选）：把系统 WebView 实现换成 119

这样 APK 会自动改走完整版 `/tv`，CSS/动画最完整，性能也更好。

Android 7.1.2 能用的最后大版本是 **Chrome 119 / Android System WebView 119**
（Chrome 120 起要求 Android 8+）。注意 CPU 架构：v1.2.0 的提示页会显示
`arm64-v8a` 或 `armeabi-v7a`，下载对应版本（或 universal 版）。

1. 电脑上下载 Chrome 119 或 Android System WebView 119 的 APK，U 盘拷到盒子安装；
2. 盒子「设置 → 关于」连点版本号 7 次，开启「开发者选项」；
3. 开发者选项 →「WebView 实现」→ 选刚装的 Chrome / Android System WebView；
4. **完全退出「潇洒K歌」**（设置 → 应用 → 强行停止），再重新打开；
5. 设置页或登录页会显示 `系统 WebView Chrome/119`，这时 App 会走完整版 `/tv`。

如果第 3 步列表里没有新装的包，说明盒子的系统不允许更换 WebView 实现。
有 ADB 的话可以直接查看/切换：

```bash
adb shell dumpsys webviewupdate
adb shell cmd webviewupdate set-webview-implementation com.google.android.webview
# 也可能是 com.android.chrome，以 dumpsys 里的 Valid packages 为准
adb shell am force-stop com.xiaosa.ktv
```

### 方案 C：用新版 Chrome 浏览器打开

在盒子上装 Chrome 119 浏览器，用 v1.2.0 提示页的「用浏览器打开」，或直接在 Chrome
地址栏输入 `http://服务器IP:8083/tv`。浏览器里需要再登录一次；网页登录页在 Chrome 119
上排版正常。这条路不用改 WebView 实现，适合系统不让切换的盒子。

### 方案 D：这个盒子用参考的原生播放器

参考的 `junyao-ktv-Android-player-v1.2.2.apk` 是纯原生 UI（Media3/ExoPlayer），
Android 5.1 就能跑，不受 WebView 版本影响。它调用同一套 `/api/*`，可以先用它顶这个
老盒子；但它没有外挂 ASS 逐字变色歌词（除非歌词烧录在 MV 画面里）。

## 安装

把 APK 拷到 U 盘插电视盒子安装，或：

```bash
adb install -r "dist/潇洒K歌-TV播放端-v1.2.0.apk"
```

如果电视盒子提示“未知来源”，在系统设置里允许文件管理器/ADB 安装即可。
从 v1.0.0 升级可以直接覆盖（同一签名）。

## 首次使用

1. 确保电视盒子和 KTV 服务器在同一个局域网。
2. 打开「潇洒K歌」，首次启动会自动扫描同网段 `8083` 端口，并显示本机 IP / 网关。
3. 找到后会自动填入 `http://服务器IP:8083` 并连接；没找到就点「屏幕键盘」手动输入，
   或用遥控器选中地址框按 OK 调出数字键盘。
4. 进入 **原生登录页**：输入后台「用户管理」里创建的 TV 账号。
   - 遥控器：方向键移动，OK 确认；选中账号/密码框按 OK 会打开屏幕键盘。
   - 键盘上的「完成」会按 账号 → 密码 → 登录 自动前进。
   - 手机/平板上安装时用系统输入法即可，不会弹出屏幕键盘。
5. 登录成功后 WebView 会记住 Cookie，下次一般不用再输。

## 遥控器

| 按键 | 行为 |
|---|---|
| 方向键 | 移动焦点（原生页面 / 网页 `data-f` 焦点系统） |
| OK / 确认 | 点击当前焦点；在输入框上会打开屏幕键盘 |
| 返回 | 先给网页发 `Escape` 关闭弹层，再弹出「继续观看 / 服务器设置 / 退出应用」 |
| 菜单键 | 服务器设置；在登录页切换屏幕键盘；在设置页打开 IP 数字键盘 |
| 音量键 | 系统音量（不拦截） |

## 实现要点

- 全屏沉浸、横屏、保持亮屏。
- `WebView` 开启 JS / DOM Storage / Cookie / 自动播放 / 明文 HTTP。
- 登录流程：
  - `GET /api/tv-auth/session` 检查 Cookie；
  - 未登录显示原生登录页；`POST /api/tv-auth/login` 成功后把 `Set-Cookie`
    写入 `CookieManager`，再加载 `/tv`；
  - 老服务端没有登录接口（404）时直接加载 `/tv`，保持向后兼容。
- 自动发现：
  - 扫描本机所有 IPv4 网卡（含 /22、/23）+ 默认网关的 /24；
  - 每个 IP 先试 `/api/discover`，老服务端回退 `/api/rooms`、`/tv/login.html`；
  - 8083 明确拒绝连接时再试 8080；
  - 第一轮找不到才扩大搜索常见网段，避免每次都等太久。
- 地址输入支持：
  - `192.168.1.100`
  - `192.168.1.100:8083`
  - `http://192.168.1.100:8083`
  - 不带端口默认 `8083`
- 网页里的 HLS 播放依赖 hls.js；服务端已把 `hls.min.js` / `qrcode.min.js` 放到
  `/shared/` 本地提供，APK 在纯局域网（无公网）环境也能用。
- WebView 页面 watchdog：加载 `/tv` 后检查 `typeof connectWS`，脚本没启动就显示
  内核过旧提示；脚本启动了但迟迟连不上，会在顶部显示「与服务器连接中断」提示条。

## 构建

需要：

- Android SDK：`build-tools/33.0.0`、`platforms/android-33`
- **JDK 8**（`build-tools 33` 的 `d8.bat` 在 JDK 9+ 上会报 `java.ext.dirs` 不支持）
- Windows PowerShell 7（或 5.1）

```powershell
# 默认会找 D:\Projects\Android\SDK 和 D:\Program Files\Java\jdk1.8.0_152
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1

# 自定义 SDK/JDK
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 `
  -Sdk "D:\Projects\Android\SDK" `
  -JavaHome "D:\Program Files\Java\jdk1.8.0_152"
```

产物：`dist/潇洒K歌-TV播放端-v1.2.0.apk`

签名证书在 `keystore/xiaosa-release.jks`（别名 `xiaosa`，密码 `xiaosa123`）。
以后升级请用同一份证书，否则 Android 会拒绝覆盖安装，需要先卸载旧版。

> 在 Windows PowerShell 5.1 下如果脚本里的中文被误读，请确认 `build.ps1` 保存为
> **UTF-8 with BOM**；PowerShell 7 读 UTF-8 无 BOM 也可以。

## 已知限制

- 这是 WebView 外壳，不包含独立的 ExoPlayer/原生解码器。WebView **< Chrome/60**
  时自动走 `/tv_old`（JS 转译到 Chrome 52 + CSS Grid 换 flex），>=60 走完整 `/tv`。
  `/tv_old` 的布局是 flex 重写的，个别弹层/主题 3 的装饰可能和原版不同，功能逻辑一致。
- Chrome/52 的 **MSE/音频轨支持不确定**：如果 HLS 不可用，页面会回退到 `/stream` 直传，
  此时原唱/伴唱切换可能失效；需要在盒子上实测。
- 如果只更新 APK、服务端没有 `web/tv_old`，`/tv_old` 会 404；请同时更新服务端镜像。
  - 电视盒子的应用商店通常可以更新「Android System WebView」/「Chrome」；
  - 更新不了的老盒子，建议换盒子或使用参考的原生播放器。
- 首次登录仍然需要账号密码（账号在管理后台「用户管理」里创建）。
- 只支持文本字幕（srt/ass/ssa/vtt 等）；PGS/DVD 位图字幕和已烧录进画面的硬字幕无法隐藏。
- 服务端默认端口是 `8083`；如果改了端口，自动搜索仍会尝试 8080，再找不到就需要
  手动输入 `IP:端口`。
- 自动搜索依赖“电视和服务器在可互通的网段”。如果路由器开了 AP 隔离/访客网络，
  或服务器在另一个 VLAN，自动搜索和手动输入都会失败——请先把两台设备放到同一网段。
