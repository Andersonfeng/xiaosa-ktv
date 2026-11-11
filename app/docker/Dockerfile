FROM node:20-bookworm-slim

# 系统依赖：
#   - python3 / make / g++：better-sqlite3 没有预编译包时回退源码编译
#   - ffmpeg：软件编码 / 常规转码
#   - curl / xz-utils：下载并解压带 NVENC 的 FFmpeg（仅 amd64）
#   - libva2 / libva-drm2 / va-driver-all / vainfo：Intel/AMD 核显 VAAPI 硬解硬编
#
# 说明：这是 1.3.1 的构建方式。amd64 额外安装一份带 NVENC 的 FFmpeg 到
# /usr/local/bin/ffmpeg-nvenc、ffprobe-nvenc；没有 NVIDIA 显卡或 arm64 时
# 代码会自动回退到系统 ffmpeg / 软件编码。
RUN apt-get update && apt-get install -y \
      python3 make g++ ffmpeg curl xz-utils \
      libva2 libva-drm2 va-driver-all vainfo \
    && rm -rf /var/lib/apt/lists/* \
    && ARCH="$(dpkg --print-architecture)" \
    && if [ "$ARCH" = "amd64" ]; then \
         curl -fsSL -o /tmp/ffmpeg.tar.xz \
           "https://github.com/BtbN/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-linux64-gpl.tar.xz" \
         && mkdir -p /tmp/ffmpeg-extract \
         && tar -xJf /tmp/ffmpeg.tar.xz -C /tmp/ffmpeg-extract --strip-components=1 \
         && install -m 0755 /tmp/ffmpeg-extract/bin/ffmpeg /usr/local/bin/ffmpeg-nvenc \
         && install -m 0755 /tmp/ffmpeg-extract/bin/ffprobe /usr/local/bin/ffprobe-nvenc \
         && rm -rf /tmp/ffmpeg.tar.xz /tmp/ffmpeg-extract ; \
       fi

WORKDIR /app

COPY server/package.json ./
RUN npm install --omit=dev

COPY server ./server
COPY web ./web

ENV DATA_DIR=/data
ENV MV_DIR=/mv
ENV MV_DIR_NET=/mv-net
ENV SINGER_DIR=/singer
ENV PORT=8080

EXPOSE 8080
VOLUME ["/data", "/mv", "/mv-net", "/singer"]

CMD ["node", "server/index.js"]
