#!/bin/bash
# 共用函数：目前只保留「应用数据 / 曲库目录固定路径」相关的小工具函数。
#
# 端口固定使用 8083（写死在 app/docker/docker-compose.yml），生命周期脚本
# 不需要再处理端口。
#
# 管理员密码：1.3.1 改成由 docker-compose.yml 里的 ADMIN_PASSWORD 环境变量
# 定义，不再由用户在「曲库管理」首次打开时设置、也不存在 SQLite 里。生命周期
# 脚本不参与密码的设置或存储；部署方需要自己修改 compose 里的默认值。
#
# 应用数据 / 曲库目录统一固定使用应用默认共享目录下的子目录，直接写死在
# docker-compose.yml 中，不再需要生命周期脚本用 sed 改写。这里保留几个函数
# 用于取得固定路径，供安装/升级脚本预创建目录、卸载脚本按需清空 MV 曲库：
#   data     数据库、封面、HLS/网盘源缓存等
#   mv       本地曲库
#   mv-net   网络/网盘曲库
#   singer   歌手头像

# 应用数据 / 曲库统一固定的持久化存储根路径。
default_app_share_root() {
    echo "/vol1/@appshare/${TRIM_APPNAME}"
}

# 固定的本地 MV 曲库路径，卸载向导选择清空曲库时使用。
default_mv_path() {
    echo "$(default_app_share_root)/mv"
}

# 固定的网络/网盘曲库路径。
default_mv_net_path() {
    echo "$(default_app_share_root)/mv-net"
}

# 固定的歌手头像目录。
default_singer_path() {
    echo "$(default_app_share_root)/singer"
}

# 固定的应用数据路径（数据库、封面等）。
default_data_path() {
    echo "$(default_app_share_root)/data"
}
