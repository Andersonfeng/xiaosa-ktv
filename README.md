# 潇洒K歌（xiaosa-ktv）- 参考自 junxiao K歌

局域网 K 歌系统 —— 手机扫码点歌，电视/投影仪大屏沉浸演唱。

一台服务器 + 一块大屏，局域网内所有设备扫码即可点歌、切歌、调音，无需额外硬件，无需公网，开箱即用。

---

## ✨ 功能特性

- **手机扫码点歌**：无需安装 App，扫码即可搜索歌曲、点歌、管理已点队列
- **大屏播放主页面**：支持三套视觉风格（简约卡片 / 暗夜霓虹 / 3D 轮播大屏），设置菜单三风格统一交互
- **多端角色管理**：投影仪或者电视安装播放端，安卓平板安装控制端（非必须），浏览器的播放端可上锁防误抢，控制端遥控播放端的暂停/切歌/音量/全屏等操作
- **原唱 / 伴唱切换**：基于双音轨 MV 服务端按需提取，浏览器端无需支持多音轨即可切换
- **字幕显示 / 隐藏**：默认显示字幕，大屏端和手机遥控端都能一键切换；支持视频内嵌文本字幕（srt/ass/ssa/mov_text 等）及同名外挂字幕（.srt/.ass/.ssa/.vtt，可带 .zh/.chs 等语言后缀）。ASS 字幕会按 `\kf` 逐字时间做卡拉OK式变色（未唱白色、已唱填充字幕样式里的颜色），普通 SRT/VTT 使用金色静态字幕。PGS/DVD 位图字幕不支持。
- **均衡器**：标准 / 人声增强 / 低音增强 / 明亮清晰等预设音效
- **曲库管理**：支持本地目录与网盘挂载目录混合曲库，后台可视化管理来源、批量清理孤儿曲目、文件名智能解析、歌手头像自动匹配、多歌手分隔符可配置（默认 `&`，可在后台「设置 → 多歌手」切换）
- **硬件转码**：自动探测并使用 VAAPI（Intel/AMD 核显）或 NVENC（NVIDIA 显卡）硬件加速转码，无对应硬件时自动回退软件编码
- **管理后台**：曲库来源管理、终端设备管理、转码缓存清理策略配置，全部网页端可视化操作
- **电视 APK**：提供电视盒子/投影仪安装包，WebView 加载网页大屏端；首次启动自动搜索同网段 KTV 服务，支持遥控器方向键/OK/返回/菜单操作

---

## 🖥️ 系统组成

| 端 | 地址 | 说明 |
|---|---|---|
| 大屏播放主页面 | `/tv` | 电视/投影仪打开，K歌主界面 |
| 手机遥控端 | `/m` | 手机扫码打开，点歌与遥控 |
| 管理后台 | `/admin` | 曲库、设备、缓存等后台管理 |
| 电视 APK | `潇洒K歌-TV播放端-v1.0.0.apk` | 电视盒子/投影仪安装，WebView 加载 `/tv`，自动发现服务器 |

浏览器打开服务器地址（如 `http://局域网IP:8083`）会看到一个导航首页，三个入口一目了然。

### 电视盒子 / 投影仪 APK

如果不想用电视浏览器，可以直接安装 `潇洒K歌-TV播放端-v1.0.0.apk`：

- 首次启动自动扫描同网段 8083 端口的 KTV 服务；也可以手动输入 `http://服务器IP:8083`。
- 遥控器：方向键移动焦点，OK 确认；返回键先关闭网页弹层，再弹出「继续观看 / 服务器设置 / 退出」；菜单键直接打开服务器设置。
- 首次登录使用网页登录页，电视盒子的系统输入法会弹出；如果遥控器没有输入法，可插 USB 键盘完成一次登录，之后会记住。
- APK 要求 Android 5.1+，建议 WebView 70+；HLS 和二维码库已随服务端本地提供，局域网内不依赖公网 CDN。
- 源码在 `android-player/`，重新构建见 `android-player/README.md`。


---

## 🚀 快速开始

只需 Docker，无需手动安装 Node.js / ffmpeg 等依赖。

### 1. 准备 `docker-compose.yaml`

```yaml
services:
  xiaosa-ktv:
    image: Andersonfeng/xiaosa-ktv:latest
    container_name: xiaosa-ktv
    restart: unless-stopped
    ports:
      - "8083:8080"          # 访问端口：http://局域网IP:8083
    environment:
      - TZ=Asia/Shanghai
      - PORT=8080
      - DATA_DIR=/data
      - ADMIN_PASSWORD=admin888          # 管理后台("/admin")登录密码，记得改掉
      - VAAPI_DEVICE=/dev/dri/renderD128 # 核显硬件转码用，没有核显就删掉这行
      - HLS_CACHE_MAX_AGE_DAYS=3         # 转码缓存超过几天没人点就自动清理
      # 用 NVIDIA 显卡转码(NVENC)就取消下面两行注释，同时取消最下面 runtime: nvidia 的注释
      # - NVIDIA_VISIBLE_DEVICES=all
      # - NVIDIA_DRIVER_CAPABILITIES=compute,video,utility
    volumes:
      - /path/to/your/data:/data                 # 应用数据(数据库、封面等)，必须挂载

      # 曲库挂载：按需增删，本地曲库挂到 /mv/<自定义名>，网盘曲库挂到 /mv-net/<自定义名>
      - /path/to/local/library1:/mv/library1
      - /path/to/local/library2:/mv/library2
      - /path/to/netdisk/library:/mv-net/netdisk1
      - /path/to/singer/avatars:/singer           # 歌手头像目录，图片名对应歌手名，如 周杰伦.jpg
      # 挂载完成后，还需要去后台「曲库管理→曲库来源」里把新目录逐个启用，才会真正参与扫描
    devices:
      - /dev/dri:/dev/dri     # 核显硬件转码用，没有核显就删掉这行
    # runtime: nvidia         # 用 NVIDIA 显卡转码时取消注释(同时打开上面两行 NVIDIA 环境变量)
```


### 2. 启动

```bash
docker compose up -d
```

### 3. 访问

浏览器打开 `http://局域网IP:8083`，或直接访问 `/tv`（大屏）、`/m`（手机点歌）、`/admin`（管理后台）。

### 4. 曲库配置

进入「管理后台 → 曲库管理」，将挂载进容器的目录逐个启用为曲库来源，保存后即会自动扫描曲库，无需重建容器。

### 5. 从本仓库源码构建（可选）

本仓库已经包含 1.0.0 的完整 `server/` 和 `web/` 源码，也可以不走 Docker Hub，直接构建：

```bash
cd app/docker
# 该 compose 使用 fnOS 固定共享目录；其它环境先按实际路径修改 volumes
mkdir -p /vol1/@appshare/xiaosa-ktv/{data,mv,mv-net,singer}
docker compose build --no-cache
docker compose up -d
```

构建前请把 `app/docker/docker-compose.yml` 里的 `ADMIN_PASSWORD=admin888` 改掉；没有 `/dev/dri` 的机器需要删掉 `devices` 段（fnOS 安装/升级脚本会自动处理）。

---

## ⚙️ 硬件转码

- **Intel / AMD 核显**：挂载 `/dev/dri` 设备节点即可自动启用 VAAPI 硬件转码
- **NVIDIA 显卡**：需安装 [nvidia-container-toolkit](https://github.com/NVIDIA/nvidia-container-toolkit)，并在 compose 中启用 `runtime: nvidia`
- 无对应硬件时自动回退到 CPU 软件转码，不影响正常使用

---

## 🛠️ 技术栈

- **服务端**：Node.js + Express + WebSocket + better-sqlite3 + ffmpeg（HLS 转码）
- **前端**：原生 HTML/CSS/JS，无框架依赖，多主题风格通过 CSS 变量 + 状态属性驱动
- **客户端**：Android（Java WebView 外壳）
- **部署**：Docker / Docker Compose，支持 x86_64 与 aarch64 多架构镜像

---

## 📄 License

本项目仅供个人学习与局域网自用场景使用。

---

原作者：清风渡客