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
- **电视 APK**：提供电视盒子/投影仪安装包，WebView 加载网页大屏端；首次启动自动搜索同网段 KTV 服务，原生登录 + 遥控器屏幕键盘，支持方向键/OK/返回/菜单操作

---

## 🖥️ 系统组成

| 端 | 地址 | 说明 |
|---|---|---|
| 大屏播放主页面 | `/tv` | 电视/投影仪打开，K歌主界面 |
| 手机遥控端 | `/m` | 手机扫码打开，点歌与遥控 |
| 管理后台 | `/admin` | 曲库、设备、缓存等后台管理 |
| 电视 APK | `潇洒K歌-TV播放端-v1.2.0.apk` | 电视盒子/投影仪安装，WebView 加载 `/tv`；老内核自动切 `/tv_old` 兼容页，原生登录 + 自动发现服务器 |
| 老内核兼容大屏页 | `/tv_old` | 给 Chrome/52 这类老电视盒子用的兼容版，功能同 `/tv`，由 APK 自动选择 |

浏览器打开服务器地址（如 `http://局域网IP:8083`）会看到一个导航首页，三个入口一目了然。

### 电视盒子 / 投影仪 APK

如果不想用电视浏览器，可以直接安装 `潇洒K歌-TV播放端-v1.2.0.apk`：

- 首次启动自动扫描本机网段的 8083 端口，并显示本机 IP / 网关；本机网段找不到时会再扩大搜索 `192.168.0/1`、`10.0.0` 等常见网段。仍找不到可手动输入 `http://服务器IP:8083`。
- 登录改成 **APK 原生登录页**，不再依赖电视盒子的网页登录页和系统输入法：自带遥控器屏幕键盘，方向键选字、OK 输入。老盒子也能登录。
- 遥控器：方向键移动焦点，OK 确认；返回键先关闭网页弹层，再弹出「继续观看 / 服务器设置 / 退出」；菜单键打开服务器设置 / 屏幕键盘。
- APK 要求 Android 5.1+；系统 WebView **< Chrome/60** 时自动加载兼容页 `/tv_old`（功能同 `/tv`，JS 已转译、CSS Grid 换成 flex），**>=60** 加载完整 `/tv`。Chrome/52 的老盒子不用再折腾 WebView；如果兼容页也跑不起来，会显示当前版本和升级建议。
- HLS 和二维码库随服务端本地提供，局域网内不依赖公网 CDN。
- 源码在 `android-player/`，重新构建见 `android-player/README.md`。


---

## 🚀 快速开始（Docker）

只需要 Docker 20.10+ / Compose v2，不用在宿主机装 Node.js、ffmpeg 或数据库；镜像里已经带好 Node.js 20 + ffmpeg。

### 1. 准备配置

```bash
git clone https://github.com/Andersonfeng/xiaosa-ktv.git
cd xiaosa-ktv

cp .env.example .env
mkdir -p ktv-data/{data,mv,mv-net,singer}
```

`.env` 里至少改这两项：

```ini
KTV_PORT=8083                     # 宿主机访问端口
ADMIN_PASSWORD=换成你自己的密码     # 管理后台 /admin 的登录密码
```

MV 已经放在宿主机某个目录的话，把 `KTV_MV_DIR` 指过去（例如 `KTV_MV_DIR=/mnt/disk/mv`）；不指就用仓库下的 `./ktv-data/mv`。仓库根目录的 `docker-compose.yml` 已经把这些都参数化了，不用再手写 compose。

> Windows PowerShell 里没有 `mkdir -p`，用 `mkdir ktv-data\data, ktv-data\mv, ktv-data\mv-net, ktv-data\singer` 代替。

### 2. 构建并启动

```bash
docker compose up -d --build
```

首次构建约 3~10 分钟（安装 ffmpeg 等系统依赖 + npm 依赖）。amd64 上还会从 GitHub 额外下载一份带 NVENC 的 ffmpeg，网络不通时会自动跳过并回退到系统 ffmpeg，不影响启动，只是 NVIDIA 硬编码不可用。


### 3. 访问

浏览器打开 `http://<服务器IP>:8083`：

| 端 | 地址 |
|---|---|
| 导航首页 | `/` |
| 大屏播放 | `/tv` |
| 手机点歌 | `/m` 或 `/mobile` |
| 管理后台 | `/admin`（密码 = `.env` 里的 `ADMIN_PASSWORD`） |

首次使用：进 `/admin` 建一个 TV 登录账号（`/tv` 需要登录）；MV 文件放进 `KTV_MV_DIR` 后，到「曲库管理 → 曲库来源」确认目录已启用并点一次扫描。

### 4. 常用命令

```bash
docker compose logs -f        # 看日志
docker compose restart        # 重启（改完 .env 需要）
docker compose down           # 停止并删除容器（数据在宿主机上，不会丢）
docker compose up -d --build  # 更新代码后重建
docker compose ps             # 状态，Up (healthy) 表示健康检查通过
```

所有持久化数据都在 `./ktv-data` 下：`data/`（ktv.db、封面、HLS 缓存）、`mv/`、`mv-net/`、`singer/`。备份这个目录就够了，删容器/重建都不会丢歌。

### 5. 部署到远端机器

**A. 直接用已发布的镜像（最快，推荐）**

`andersonfeng/xiaosa-ktv:1.2.0`（amd64，约 570MB）已经发布在 Docker Hub，目标机器只要 `docker-compose.release.yml` + `.env`：

```bash
docker pull andersonfeng/xiaosa-ktv:1.2.0
docker compose -f docker-compose.release.yml up -d
```

**B. 目标机器能拿到源码**（内网 Git、直接拷贝仓库都行；`data/`、`node_modules/` 不用拷）：

```bash
cd xiaosa-ktv
cp .env.example .env      # 改密码、端口、曲库路径
docker compose up -d --build
```

**C. 用 GitHub Actions 自动构建（本机不用装 Docker）**

`.github/workflows/docker-publish.yml` 已经配好：push 到 `main`/`master` 或打 `v1.2.0` 这样的 tag，GitHub 会自动构建 amd64 镜像并推到 **ghcr.io/andersonfeng/xiaosa-ktv**，不需要额外配置密钥（用仓库自带的 `GITHUB_TOKEN`）。

推上去之后，目标机器（只要 `docker-compose.release.yml` + `.env`）：

```ini
# .env
KTV_IMAGE=ghcr.io/andersonfeng/xiaosa-ktv:latest
```

```bash
docker compose -f docker-compose.release.yml up -d
```

> 仓库是私有的话，目标机要先 `docker login ghcr.io` 并给 GitHub 账号发一个 `read:packages` 的 Personal Access Token。公开仓库不用登录。

**C. 手动构建并推送镜像**（本机/服务器上装了 Docker）：

```bash
docker build -t andersonfeng/xiaosa-ktv:1.2.0 ./app/docker
docker push andersonfeng/xiaosa-ktv:1.2.0        # 需要先 docker login
```

目标机器只要 `docker-compose.release.yml` + `.env` 两个文件：

```bash
docker compose -f docker-compose.release.yml up -d
```

没有镜像仓库时可以离线搬运：

```bash
docker save xiaosa-ktv:1.0.0 -o xiaosa-ktv.tar    # 构建机
docker load -i xiaosa-ktv.tar                      # 目标机
# 在目标机 .env 里加一行 KTV_IMAGE=xiaosa-ktv:1.0.0，然后
docker compose -f docker-compose.release.yml up -d
```

> release 文件里的镜像名默认是 `andersonfeng/xiaosa-ktv:1.2.0`，可用 `.env` 的 `KTV_IMAGE` 覆盖。

### 6. fnOS / 群晖等套件安装

应用包走的是另一份 `app/docker/docker-compose.yml`（固定使用 `/vol1/@appshare/xiaosa-ktv` 共享目录），和上面的通用部署互相独立；安装/升级脚本会自动处理 `devices` 段，不需要手动改。

---

## ⚙️ 硬件转码

- **Intel / AMD 核显**：宿主机有 `/dev/dri` 时，把 `docker-compose.yml`（或 release 文件）里 `devices:` 两行的注释去掉，即可自动启用 VAAPI 硬解硬编
- **NVIDIA 显卡**：需安装 [nvidia-container-toolkit](https://github.com/NVIDIA/nvidia-container-toolkit)，并在 compose 中启用 `runtime: nvidia`
- 无对应硬件时自动回退到 CPU 软件转码，不影响正常使用
- 没有 `/dev/dri` 却保留 `devices` 段时 `docker compose up` 会直接报错退出，所以默认是注释掉的

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