#!/system/bin/sh
# Android Boot Animation Switcher — 全自动双格式安装
# 自动安装 bootanimation.zip + bootanimation.mp4

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP_DIR="/data/local/tmp/bootanim_backup"
PRODUCT_PATH=""

_detect_paths() {
    for p in /product/media /system/media /system/product/media; do
        if [ -f "$p/bootanimation.zip" ] || [ -f "$p/bootanimation.mp4" ]; then
            PRODUCT_PATH="$p"
            return 0
        fi
    done
    [ -d /product/media ] && PRODUCT_PATH=/product/media && return 0
    [ -d /system/media ] && PRODUCT_PATH=/system/media && return 0
    echo "ERROR: 无法检测开机动画路径"
    exit 1
}

_backup() {
    mkdir -p "$BACKUP_DIR" 2>/dev/null
    local ts
    ts=$(date "+%Y%m%d_%H%M%S")
    for f in bootanimation.zip bootanimation.mp4; do
        [ -f "$PRODUCT_PATH/$f" ] && cp "$PRODUCT_PATH/$f" "$BACKUP_DIR/${f%.*}_$ts.${f##*.}" && echo "已备份: $BACKUP_DIR/${f%.*}_$ts.${f##*.}"
    done
}

_install_file() {
    local src="$1"
    local name
    name=$(basename "$src")
    [ ! -f "$src" ] && echo "跳过 $name (文件不存在)" && return 1
    cp "$src" "$PRODUCT_PATH/$name" 2>/dev/null || { echo "ERROR: 复制失败 $name"; return 1; }
    chmod 644 "$PRODUCT_PATH/$name"
    echo "已安装: $PRODUCT_PATH/$name"
}

_usage() {
    echo "全自动安装开机动画 (bootanimation.zip + bootanimation.mp4)"
    echo "用法: sh change_bootanimation.sh"
    echo "       sh change_bootanimation.sh check   # 仅检查"
    echo "       sh change_bootanimation.sh restore # 恢复备份"
    exit 0
}

case "$1" in
    check)
        _detect_paths
        echo "=== 当前开机动画 ==="
        for f in bootanimation.zip bootanimation.mp4; do
            [ -f "$PRODUCT_PATH/$f" ] && ls -lh "$PRODUCT_PATH/$f" || echo "$f: 不存在"
        done
        echo "=== 备份 ==="
        ls -lh "$BACKUP_DIR" 2>/dev/null || echo "(无备份)"
        exit 0
        ;;
    restore)
        _detect_paths
        for ext in zip mp4; do
            latest=$(ls -t "$BACKUP_DIR"/bootanimation_*.$ext 2>/dev/null | head -1)
            [ -n "$latest" ] && _install_file "$latest"
        done
        echo "已恢复"
        exit 0
        ;;
    "");;
    *) _usage;;
esac

_detect_paths
echo "检测到路径: $PRODUCT_PATH"

# 安装两种格式
echo ">>> 备份当前开机动画..."
_backup
echo ">>> 安装新开机动画..."
_install_file "$SCRIPT_DIR/bootanimation.zip"
_install_file "$SCRIPT_DIR/bootanimation.mp4"
echo ""
echo "====== 完成! 重启手机后生效 ======"
