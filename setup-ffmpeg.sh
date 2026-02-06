#!/bin/bash

echo "========================================"
echo "   FFmpeg 下载安装脚本"
echo "========================================"
echo ""

cd "$(dirname "$0")"

# 创建目录
mkdir -p ffmpeg/mac
mkdir -p ffmpeg/win

# ========================================
# macOS FFmpeg
# ========================================
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "[信息] 检测到 macOS 系统"
    echo ""
    
    if [ -f "ffmpeg/mac/ffmpeg" ]; then
        echo "[信息] FFmpeg (macOS) 已存在，跳过下载"
        ./ffmpeg/mac/ffmpeg -version 2>/dev/null | head -1
    else
        echo "[信息] 下载 FFmpeg (macOS)..."
        echo "[信息] 源: https://evermeet.cx/ffmpeg/"
        echo ""
        
        # 检测架构
        ARCH=$(uname -m)
        if [ "$ARCH" = "arm64" ]; then
            echo "[信息] 检测到 Apple Silicon (ARM64)"
            FFMPEG_URL="https://evermeet.cx/ffmpeg/getrelease/ffmpeg/zip"
        else
            echo "[信息] 检测到 Intel (x86_64)"
            FFMPEG_URL="https://evermeet.cx/ffmpeg/getrelease/ffmpeg/zip"
        fi
        
        echo "[信息] 正在下载..."
        curl -L -o ffmpeg/ffmpeg-mac.zip "$FFMPEG_URL"
        
        if [ -f "ffmpeg/ffmpeg-mac.zip" ]; then
            echo "[信息] 解压中..."
            unzip -o ffmpeg/ffmpeg-mac.zip -d ffmpeg/mac/
            rm ffmpeg/ffmpeg-mac.zip
            chmod +x ffmpeg/mac/ffmpeg
            
            if [ -f "ffmpeg/mac/ffmpeg" ]; then
                echo "[成功] FFmpeg (macOS) 安装完成！"
                ./ffmpeg/mac/ffmpeg -version 2>/dev/null | head -1
            else
                echo "[错误] FFmpeg (macOS) 安装失败"
            fi
        else
            echo "[错误] 下载失败！"
            echo ""
            echo "[备选方案] 使用 Homebrew 安装:"
            echo "  brew install ffmpeg"
            echo "  然后将 ffmpeg 复制到项目:"
            echo "  cp \$(which ffmpeg) ffmpeg/mac/"
        fi
    fi
fi

# ========================================
# Linux FFmpeg
# ========================================
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    echo "[信息] 检测到 Linux 系统"
    mkdir -p ffmpeg/linux
    
    if [ -f "ffmpeg/linux/ffmpeg" ]; then
        echo "[信息] FFmpeg (Linux) 已存在，跳过下载"
        ./ffmpeg/linux/ffmpeg -version 2>/dev/null | head -1
    else
        echo "[信息] 下载 FFmpeg (Linux)..."
        echo "[信息] 源: https://johnvansickle.com/ffmpeg/"
        echo ""
        
        ARCH=$(uname -m)
        if [ "$ARCH" = "x86_64" ]; then
            FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
        elif [ "$ARCH" = "aarch64" ]; then
            FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz"
        else
            echo "[错误] 不支持的架构: $ARCH"
            exit 1
        fi
        
        echo "[信息] 正在下载..."
        curl -L -o ffmpeg/ffmpeg-linux.tar.xz "$FFMPEG_URL"
        
        if [ -f "ffmpeg/ffmpeg-linux.tar.xz" ]; then
            echo "[信息] 解压中..."
            tar -xf ffmpeg/ffmpeg-linux.tar.xz -C ffmpeg/
            mv ffmpeg/ffmpeg-*-static/ffmpeg ffmpeg/linux/
            mv ffmpeg/ffmpeg-*-static/ffprobe ffmpeg/linux/ 2>/dev/null
            rm -rf ffmpeg/ffmpeg-*-static
            rm ffmpeg/ffmpeg-linux.tar.xz
            chmod +x ffmpeg/linux/ffmpeg
            
            if [ -f "ffmpeg/linux/ffmpeg" ]; then
                echo "[成功] FFmpeg (Linux) 安装完成！"
                ./ffmpeg/linux/ffmpeg -version 2>/dev/null | head -1
            else
                echo "[错误] FFmpeg (Linux) 安装失败"
            fi
        else
            echo "[错误] 下载失败！"
        fi
    fi
fi

echo ""
echo "========================================"
echo "   FFmpeg 目录结构"
echo "========================================"
echo ""
echo "ffmpeg/"
[ -f "ffmpeg/win/ffmpeg.exe" ] && echo "  └── win/ffmpeg.exe     ✓ Windows" || echo "  └── win/ffmpeg.exe     ✗ 需在 Windows 下载"
[ -f "ffmpeg/mac/ffmpeg" ] && echo "  └── mac/ffmpeg         ✓ macOS" || echo "  └── mac/ffmpeg         ✗ 需在 macOS 下载"
[ -f "ffmpeg/linux/ffmpeg" ] && echo "  └── linux/ffmpeg       ✓ Linux" || echo "  └── linux/ffmpeg       ✗ 需在 Linux 下载"
echo ""
