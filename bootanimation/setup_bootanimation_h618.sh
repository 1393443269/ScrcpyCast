#!/bin/bash
# H618 (K2B/K2C) 开机LOGO + 开机动画 编译集成脚本
# 用法: 放到 Android 源码根目录执行
#   ./setup_bootanimation_h618.sh
#
# 前置条件:
#   - 本脚本同目录下放置:
#     bootanimation.zip   (逐帧动画, part0+desc.txt 在zip根目录)
#     bootanimation.mp4   (视频动画, 可选)
#     bootlogo.bmp        (uboot阶段logo, 可选, 默认从动画首帧生成)
#   - 已配置好 Android/Longan 编译环境

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ============================================================
# 配置区 — 按实际 SDK 路径修改
# ============================================================
LONGAN_DIR="longan"                        # Longan SDK 根目录 (Uboot/Kernel)
ANDROID_DIR="android"                      # Android SDK 根目录
BOOTANIM_CONFIG_PATH="device/softwinner/apollo/common/media"

# ============================================================
# 1. 开机 LOGO (Uboot 阶段)
# ============================================================
install_bootlogo() {
    local logo_src="$SCRIPT_DIR/bootlogo.bmp"
    local logo_dst="$LONGAN_DIR/device/config/chips/h618/boot-resource/boot-resource/bootlogo.bmp"

    if [ ! -f "$logo_src" ]; then
        echo "[SKIP] bootlogo.bmp 未提供，跳过 uboot logo"
        return
    fi

    mkdir -p "$(dirname "$logo_dst")"
    cp "$logo_src" "$logo_dst"
    echo "[OK]  Uboot LOGO -> $logo_dst"
}

# ============================================================
# 2. 开机动画 (Android 阶段)
# ============================================================
install_bootanimation() {
    local anim_dir="$ANDROID_DIR/$BOOTANIM_CONFIG_PATH"
    mkdir -p "$anim_dir"

    # 安装 ZIP 格式
    if [ -f "$SCRIPT_DIR/bootanimation.zip" ]; then
        cp "$SCRIPT_DIR/bootanimation.zip" "$anim_dir/bootanimation.zip"
        echo "[OK]  bootanimation.zip -> $anim_dir/"
    fi

    # 安装 MP4 格式
    if [ -f "$SCRIPT_DIR/bootanimation.mp4" ]; then
        cp "$SCRIPT_DIR/bootanimation.mp4" "$anim_dir/bootanimation.mp4"
        echo "[OK]  bootanimation.mp4 -> $anim_dir/"
    fi
}

# ============================================================
# 3. 修改编译配置 (config.mk)
# ============================================================
patch_config() {
    local config_file="$ANDROID_DIR/$BOOTANIM_CONFIG_PATH/config.mk"

    # 如不存在则创建
    if [ ! -f "$config_file" ]; then
        mkdir -p "$(dirname "$config_file")"
        cat > "$config_file" << 'MKEOF'
BOOTANIMATION_CONFIG_PATH := device/softwinner/apollo/common/media

MKEOF
        echo "[NEW] 创建 $config_file"
    fi

    # 添加 zip 拷贝规则 (如不存在)
    if [ -f "$SCRIPT_DIR/bootanimation.zip" ]; then
        if ! grep -q "bootanimation.zip" "$config_file" 2>/dev/null; then
            echo 'PRODUCT_COPY_FILES += $(BOOTANIMATION_CONFIG_PATH)/bootanimation.zip:system/media/bootanimation.zip' >> "$config_file"
            echo "[OK]  config.mk 已添加 bootanimation.zip 编译规则"
        else
            echo "[OK]  config.mk 已有 bootanimation.zip 规则"
        fi
    fi

    # 添加 mp4 拷贝规则 (如不存在)
    if [ -f "$SCRIPT_DIR/bootanimation.mp4" ]; then
        if ! grep -q "bootanimation.mp4" "$config_file" 2>/dev/null; then
            echo 'PRODUCT_COPY_FILES += $(BOOTANIMATION_CONFIG_PATH)/bootanimation.mp4:system/media/bootanimation.mp4' >> "$config_file"
            echo "[OK]  config.mk 已添加 bootanimation.mp4 编译规则"
        else
            echo "[OK]  config.mk 已有 bootanimation.mp4 规则"
        fi
    fi
}

# ============================================================
# 执行
# ============================================================
echo "========================================"
echo " H618 (K2B/K2C) 开机动画编译集成"
echo "========================================"
echo ""

install_bootlogo
install_bootanimation
patch_config

echo ""
echo "========================================"
echo " 完成! 后续编译步骤:"
echo "========================================"
echo ""
echo "  cd $LONGAN_DIR"
echo "  ./build.sh    # 编译 Uboot/Kernel (含新 LOGO)"
echo ""
echo "  cd $ANDROID_DIR"
echo "  source build/envsetup.sh"
echo "  lunch apollo_xxx-userdebug"
echo "  make -j$(nproc)   # 编译固件"
echo ""
echo " 或者一键打包:"
echo "  cd $ANDROID_DIR"
echo "  ./build.sh -A -K -U  # 全自动编译打包"
echo ""
