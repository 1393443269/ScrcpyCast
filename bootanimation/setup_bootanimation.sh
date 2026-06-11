#!/bin/bash
# Android 开机LOGO + 开机动画 源码集成脚本 (全平台)
# 用法: 放到 Android 源码根目录执行
#   ./setup_bootanimation.sh <平台> [选项]
#
# 平台:
#   h618       H618 (K2B/K2C)  - Allwinner
#   a733       A733 (K10B)     - Allwinner
#   rk35       RK3562/3568/3576/3588 - Rockchip
#
# 选项:
#   --no-logo      跳过 bootlogo
#   --only-zip     只安装 zip, 不安装 mp4
#   --only-mp4     只安装 mp4, 不安装 zip

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
    sed -n '2,20p' "$0"
    exit 1
}

[ $# -lt 1 ] && usage
PLATFORM="$1"
shift

# ============================================================
# H618 (K2B/K2C) - Allwinner
# ============================================================
setup_h618() {
    LONGAN_DIR="longan"
    ANDROID_DIR="android"
    BOOTANIM_PATH="device/softwinner/apollo/common/media"
    LOGO_PATH="device/config/chips/h618/boot-resource/boot-resource/bootlogo.bmp"

    # bootanimation 安装
    local anim_dir="$ANDROID_DIR/$BOOTANIM_PATH"
    mkdir -p "$anim_dir"
    for f in bootanimation.zip bootanimation.mp4; do
        [ -f "$SCRIPT_DIR/$f" ] && cp "$SCRIPT_DIR/$f" "$anim_dir/$f" && echo "[OK]  $f -> $anim_dir/"
    done

    # config.mk
    local cfg="$anim_dir/config.mk"
    [ ! -f "$cfg" ] && cat > "$cfg" <<< 'BOOTANIMATION_CONFIG_PATH := device/softwinner/apollo/common/media'
    for f in bootanimation.zip bootanimation.mp4; do
        [ -f "$SCRIPT_DIR/$f" ] && grep -q "$f" "$cfg" 2>/dev/null || echo "PRODUCT_COPY_FILES += \$(BOOTANIMATION_CONFIG_PATH)/$f:system/media/$f" >> "$cfg"
    done
    echo "[OK]  config.mk 已更新"

    # bootlogo
    if [ -f "$SCRIPT_DIR/bootlogo.bmp" ]; then
        local logo_dst="$LONGAN_DIR/$LOGO_PATH"
        mkdir -p "$(dirname "$logo_dst")"
        cp "$SCRIPT_DIR/bootlogo.bmp" "$logo_dst"
        echo "[OK]  bootlogo.bmp -> $logo_dst"
    fi
}

# ============================================================
# A733 (K10B) - Allwinner
# ============================================================
setup_a733() {
    LONGAN_DIR="longan"
    ANDROID_DIR="android"
    BOOTANIM_PATH="device/softwinner/jupiter/a733-demo-aiot/media/bootanimation"
    LOGO_PATH="device/config/chips/a733/configs/demo_aiot/bootlogo.bmp"

    local anim_dir="$ANDROID_DIR/$BOOTANIM_PATH"
    mkdir -p "$anim_dir"
    for f in bootanimation.zip bootanimation.mp4; do
        [ -f "$SCRIPT_DIR/$f" ] && cp "$SCRIPT_DIR/$f" "$anim_dir/$f" && echo "[OK]  $f -> $anim_dir/"
    done

    if [ -f "$SCRIPT_DIR/bootlogo.bmp" ]; then
        local logo_dst="$LONGAN_DIR/$LOGO_PATH"
        mkdir -p "$(dirname "$logo_dst")"
        cp "$SCRIPT_DIR/bootlogo.bmp" "$logo_dst"
        echo "[OK]  bootlogo.bmp -> $logo_dst"
    fi
}

# ============================================================
# Rockchip RK3562/3568/3576/3588
# ============================================================
setup_rk35() {
    KERNEL_DIR="kernel-5.10"  # 或 kernel-6.1 (RK3576)
    ANDROID_DIR="android"
    BOOTANIM_PATH="device/rockchip/common/bootshutdown"

    # 检测内核版本
    if [ -d "$ANDROID_DIR/kernel-6.1" ]; then
        KERNEL_DIR="kernel-6.1"
    fi

    # bootanimation + shutdownanimation
    local anim_dir="$ANDROID_DIR/$BOOTANIM_PATH"
    mkdir -p "$anim_dir"
    for f in bootanimation.zip; do
        [ -f "$SCRIPT_DIR/$f" ] && cp "$SCRIPT_DIR/$f" "$anim_dir/$f" && echo "[OK]  $f -> $anim_dir/"
    done

    # BoardConfig.mk - 启用开关机动画
    local board_cfg="$ANIM_DIR/../BoardConfig.mk"
    if [ -f "$board_cfg" ]; then
        if grep -q "BOOT_SHUTDOWN_ANIMATION_RINGING" "$board_cfg"; then
            sed -i 's/BOOT_SHUTDOWN_ANIMATION_RINGING ?= false/BOOT_SHUTDOWN_ANIMATION_RINGING ?= true/' "$board_cfg"
            echo "[OK]  BoardConfig.mk 已启用开机动画"
        fi
    fi

    # boot logo (Rockchip 有 uboot + kernel 两个)
    for logo in logo.bmp logo_kernel.bmp; do
        [ -f "$SCRIPT_DIR/$logo" ] && cp "$SCRIPT_DIR/$logo" "$ANDROID_DIR/$KERNEL_DIR/$logo" && echo "[OK]  $logo -> $ANDROID_DIR/$KERNEL_DIR/"
    done
}

# ============================================================
# 主入口
# ============================================================
case "$PLATFORM" in
    h618|H618|k2b|K2B)   setup_h618 ;;
    a733|A733|k10b|K10B) setup_a733 ;;
    rk35|RK35|rk3568|RK3568|rk3588|RK3588) setup_rk35 ;;
    *) echo "不支持的平台: $PLATFORM"; usage ;;
esac

echo ""
echo "========================================"
echo " 完成! 下一步:"
echo "========================================"
echo "  cd <SDK根目录>"
echo "  ./build.sh    # 编译 Uboot/Kernel + Android"
echo ""
