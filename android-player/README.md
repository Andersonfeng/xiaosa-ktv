# 潇洒K歌 · 电视播放端 APK

这是给电视盒子 / 投影仪用的 **WebView 外壳**：APK 本身不重复实现播放器，而是加载
服务端的 `/tv` 网页大屏端，所以字幕、原伴唱、三套皮肤、手机遥控等能力与浏览器版完全一致。

- 包名：`com.xiaosa.ktv`
- 版本：`1.0.0`
- 最低 Android：5.1（API 21）
- 目标 Android：13（API 33）
- 安装包：`dist/潇洒K歌-TV播放端-v1.0.0.apk`
- 新包名是 `com.xiaosa.ktv`；和旧的 `com.xiaosa.ktv` 原生播放端不是同一个包，可以共存，不想保留旧版请手动卸载。

## 安装

把 APK 拷到 U 盘插电视盒子安装，或：

```bash
adb install -r "dist/潇洒K歌-TV播放端-v1.0.0.apk"
```

如果电视盒子提示“未知来源”，在系统设置里允许文件管理器/ADB 安装即可。

## 首次使用

1. 确保电视盒子和 KTV 服务器在同一个局域网。
2. 打开「潇洒K歌」，首次启动会自动扫描同网段 `8083` 端口的 `/api/discover`。
3. 找到后会自动填入 `http://服务器IP:8083` 并连接；没找到就手动输入地址后点「连接」。
4. 进入网页登录页，用后台「用户管理」里创建的 TV 账号登录。
   - 电视盒子的系统输入法会弹出；如果遥控器没有输入法，可以插 USB 键盘完成一次登录。
   - 登录后 WebView 会记住 Cookie，下次一般不用再输。
5. 登录后就是网页大屏端，用法和浏览器 `/tv` 一样。

## 遥控器

| 按键 | 行为 |
|---|---|
| 方向键 | 移动网页焦点（网页自己的 `data-f` 焦点系统） |
| OK / 确认 | 点击当前焦点 |
| 返回 | 先给网页发 `Escape` 关闭弹层，再弹出「继续观看 / 服务器设置 / 退出应用」 |
| 菜单键 | 直接打开服务器地址设置 |
| 音量键 | 系统音量（不拦截） |

> 返回键的「服务器设置」和菜单键都能改服务器地址；改完会重新加载 `/tv`。

## 实现要点

- 全屏沉浸、横屏、保持亮屏。
- `WebView` 开启 JS / DOM Storage / Cookie / 自动播放 / 明文 HTTP。
- 首次启动扫描本机所有 IPv4 网段的 `8083` 端口；识别 `app=xiaosa-ktv` 的 `/api/discover` 响应。
- 地址输入支持：
  - `192.168.1.100`
  - `192.168.1.100:8083`
  - `http://192.168.1.100:8083`
  - 不带端口默认 `8083`
- 网页里的 HLS 播放依赖 hls.js；服务端已把 `hls.min.js` / `qrcode.min.js` 放到
  `/shared/` 本地提供，APK 在纯局域网（无公网）环境也能用。

## 构建

需要：

- Android SDK：`build-tools/33.0.0`、`platforms/android-33`
- **JDK 8**（`build-tools 33` 的 `d8.bat` 在 JDK 9+ 上会报 `java.ext.dirs` 不支持）
- Windows PowerShell 5.1 或 PowerShell 7

```powershell
# 默认会找 D:\Projects\Android\SDK 和 D:\Program Files\Java\jdk1.8.0_152
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1

# 自定义 SDK/JDK
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 `
  -Sdk "D:\Projects\Android\SDK" `
  -JavaHome "D:\Program Files\Java\jdk1.8.0_152"
```

产物：`dist/潇洒K歌-TV播放端-v1.0.0.apk`

签名证书在 `keystore/xiaosa-release.jks`（别名 `xiaosa`，密码 `xiaosa123`）。
以后升级请用同一份证书，否则 Android 会拒绝覆盖安装，需要先卸载旧版。

## 已知限制

- 这是 WebView 外壳，不包含独立的 ExoPlayer/原生解码器；电视盒子的 WebView 版本太老
  （建议 Chromium/WebView 70+）时，网页端自身的新语法或 HLS 可能不工作。
- 首次登录仍然需要输入账号密码；没有内置软键盘，依赖电视盒子的系统输入法或 USB 键盘。
- 只支持文本字幕（srt/ass/ssa/vtt 等）；PGS/DVD 位图字幕和已烧录进画面的硬字幕无法隐藏。
- 服务端默认端口是 `8083`；如果改了端口，自动搜索找不到，需要手动输入 `IP:端口`。
