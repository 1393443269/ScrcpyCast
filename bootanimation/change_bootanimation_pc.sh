#!/bin/bash
# Android 开机动画全自动更换脚本 (PC 端)
# 自动推送并安装 bootanimation.zip + bootanimation.mp4
# 无需手动选择，即插即用

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEVICE_SCRIPT="change_bootanimation.sh"

_usage() {
    echo "Android 开机动画全自动更换工具"
    echo ""
    echo "用法:"
    echo "  ./$(basename "$0")          全自动安装 (默认)"
    echo "  ./$(basename "$0") check    检查设备状态"
    echo "  ./$(basename "$0") restore  恢复上次备份"
    exit 0
}

# 检查 adb
command -v adb &>/dev/null || { echo "ERROR: 未找到 adb，请安装 Android SDK platform-tools"; exit 1; }

# 检查设备
DEVICES=$(adb devices | awk 'NR>1 && /device$/ {print $1}' | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "ERROR: 未检测到 Android 设备，请连接设备并开启 USB 调试"
    exit 1
fi

echo "检测到 $DEVICES 台设备"
adb devices -l

# adb root 尝试（可选）
adb root 2>&1 | grep -q "cannot run as root" && echo "无 adb root，继续普通模式..." || true

# 检查所需文件
MISSING=""
[ ! -f "$SCRIPT_DIR/bootanimation.zip" ] && MISSING="$MISSING bootanimation.zip"
[ ! -f "$SCRIPT_DIR/bootanimation.mp4" ] && MISSING="$MISSING bootanimation.mp4"
[ ! -f "$SCRIPT_DIR/$DEVICE_SCRIPT" ] && MISSING="$MISSING $DEVICE_SCRIPT"
if [ -n "$MISSING" ]; then
    echo "ERROR: 缺少文件:$MISSING"
    exit 1
fi

case "${1:-auto}" in
    auto)
        echo ">>> 推送文件到设备..."
        adb push "$SCRIPT_DIR/bootanimation.zip" /data/local/tmp/
        adb push "$SCRIPT_DIR/bootanimation.mp4" /data/local/tmp/
        adb push "$SCRIPT_DIR/$DEVICE_SCRIPT" /data/local/tmp/
        echo ">>> 在设备上执行安装..."
        adb shell "sh /data/local/tmp/$DEVICE_SCRIPT"

        # 拉取备份到 PC
        ts=$(date "+%Y%m%d_%H%M%S")
        for f in bootanimation.zip bootanimation.mp4; do
            adb shell "test -f /data/local/tmp/bootanim_backup/$f" 2>/dev/null &&
                adb pull "/data/local/tmp/bootanim_backup/$f" "$SCRIPT_DIR/backup_${ts}_$f" 2>/dev/null
        done

        echo "重启设备? (输入 y 或直接 Ctrl+C 跳过)"
        read -r ans
        if [ "$ans" = "y" ] || [ "$ans" = "Y" ]; then
            adb reboot
        fi
        ;;
    check)
        echo "=== 设备信息 ==="
        adb shell "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release"
        echo ""
        adb shell "sh /data/local/tmp/$DEVICE_SCRIPT check"
        echo ""
        echo "=== 本地文件准备 ==="
        for f in bootanimation.zip bootanimation.mp4 change_bootanimation.sh; do
            [ -f "$SCRIPT_DIR/$f" ] && ls -lh "$SCRIPT_DIR/$f" || echo "$f: 未找到"
        done
        ;;
    restore)
        [ ! -f "$SCRIPT_DIR/$DEVICE_SCRIPT" ] && adb push "$SCRIPT_DIR/$DEVICE_SCRIPT" /data/local/tmp/
        adb shell "sh /data/local/tmp/$DEVICE_SCRIPT restore"
        ;;
    *)
        _usage
        ;;
esac
