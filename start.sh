#!/bin/bash

# ============================================
# ISAPI 录像下载工具 - 一键启动脚本
# ============================================

cd "$(dirname "$0")"

echo "========================================"
echo "   ISAPI 录像下载工具"
echo "========================================"
echo ""

# ========================================
# 检查 Java 环境
# ========================================
if ! command -v java &> /dev/null; then
    echo "[错误] 未找到 Java，请先安装 JDK 8 或更高版本"
    echo "下载地址: https://adoptium.net/"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1)
echo "[信息] $JAVA_VERSION"

# ========================================
# 检查 FFmpeg 环境 (优先使用项目内置)
# ========================================
echo ""
echo "[信息] 检查 FFmpeg 环境..."

# 下载 FFmpeg 到项目目录
download_ffmpeg() {
    echo ""
    echo "[信息] 开始下载 FFmpeg 到项目目录..."
    
    mkdir -p ffmpeg/mac ffmpeg/linux
    
    if [[ "$OSTYPE" == "darwin"* ]]; then
        echo "[信息] 下载 FFmpeg (macOS)..."
        curl -L -o ffmpeg/ffmpeg-mac.zip "https://evermeet.cx/ffmpeg/getrelease/ffmpeg/zip"
        
        if [ -f "ffmpeg/ffmpeg-mac.zip" ]; then
            unzip -o ffmpeg/ffmpeg-mac.zip -d ffmpeg/mac/
            rm ffmpeg/ffmpeg-mac.zip
            chmod +x ffmpeg/mac/ffmpeg
            
            if [ -f "ffmpeg/mac/ffmpeg" ]; then
                echo "[信息] FFmpeg (macOS) 安装成功！"
                export PATH="$(pwd)/ffmpeg/mac:$PATH"
                return 0
            fi
        fi
        
        echo "[错误] 下载失败，尝试使用 Homebrew..."
        if command -v brew &> /dev/null; then
            brew install ffmpeg
            # 复制到项目目录
            if command -v ffmpeg &> /dev/null; then
                cp "$(which ffmpeg)" ffmpeg/mac/
                chmod +x ffmpeg/mac/ffmpeg
                echo "[信息] FFmpeg 已复制到项目目录"
                return 0
            fi
        fi
        return 1
        
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        ARCH=$(uname -m)
        if [ "$ARCH" = "x86_64" ]; then
            FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
        else
            FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz"
        fi
        
        echo "[信息] 下载 FFmpeg (Linux)..."
        curl -L -o ffmpeg/ffmpeg-linux.tar.xz "$FFMPEG_URL"
        
        if [ -f "ffmpeg/ffmpeg-linux.tar.xz" ]; then
            tar -xf ffmpeg/ffmpeg-linux.tar.xz -C ffmpeg/
            mv ffmpeg/ffmpeg-*-static/ffmpeg ffmpeg/linux/
            rm -rf ffmpeg/ffmpeg-*-static ffmpeg/ffmpeg-linux.tar.xz
            chmod +x ffmpeg/linux/ffmpeg
            
            if [ -f "ffmpeg/linux/ffmpeg" ]; then
                echo "[信息] FFmpeg (Linux) 安装成功！"
                export PATH="$(pwd)/ffmpeg/linux:$PATH"
                return 0
            fi
        fi
        return 1
    fi
    
    return 1
}

FFMPEG_FOUND=0

# 1. 优先检查系统已安装的 FFmpeg
if command -v ffmpeg &> /dev/null; then
    FFMPEG_VERSION=$(ffmpeg -version 2>&1 | head -n 1)
    echo "[信息] 使用系统 FFmpeg: $FFMPEG_VERSION"
    FFMPEG_FOUND=1
# 2. 回退：项目内置 FFmpeg
elif [[ "$OSTYPE" == "darwin"* ]] && [ -f "./ffmpeg/mac/ffmpeg" ]; then
    echo "[信息] 系统未安装 FFmpeg，使用项目内置: ./ffmpeg/mac/ffmpeg"
    export PATH="$(pwd)/ffmpeg/mac:$PATH"
    ./ffmpeg/mac/ffmpeg -version 2>/dev/null | head -1
    FFMPEG_FOUND=1
elif [[ "$OSTYPE" == "linux-gnu"* ]] && [ -f "./ffmpeg/linux/ffmpeg" ]; then
    echo "[信息] 系统未安装 FFmpeg，使用项目内置: ./ffmpeg/linux/ffmpeg"
    export PATH="$(pwd)/ffmpeg/linux:$PATH"
    ./ffmpeg/linux/ffmpeg -version 2>/dev/null | head -1
    FFMPEG_FOUND=1
elif [ -f "./ffmpeg/bin/ffmpeg" ]; then
    # 兼容旧目录结构
    echo "[信息] 系统未安装 FFmpeg，使用本地: ./ffmpeg/bin/ffmpeg"
    export PATH="$(pwd)/ffmpeg/bin:$PATH"
    FFMPEG_FOUND=1
fi

# 未找到 FFmpeg
if [ "$FFMPEG_FOUND" = "0" ]; then
    echo ""
    echo "[警告] FFmpeg 未安装！RTSP 直接截取功能将不可用。"
    echo ""
    echo "请选择:"
    echo "  1. 自动下载到项目目录 (推荐)"
    echo "  2. 跳过，稍后运行 ./setup-ffmpeg.sh 安装"
    echo ""
    read -p "请输入选择 (1/2): " FFMPEG_CHOICE
    
    if [ "$FFMPEG_CHOICE" = "1" ]; then
        download_ffmpeg
    else
        echo "[信息] 跳过 FFmpeg 安装，RTSP 截取功能将不可用"
    fi
fi

# 创建日志目录
mkdir -p ./log
echo "[信息] 日志目录: $(pwd)/log"

# ========================================
# 检查 Maven 并构建
# ========================================
if ! command -v mvn &> /dev/null; then
    echo ""
    echo "[警告] 未找到 Maven，尝试使用备用方式..."
    echo ""
    
    # 备用方式：直接下载依赖并运行
    LIB_DIR="./lib"
    mkdir -p "$LIB_DIR"
    
    # 检查依赖是否已下载
    if [ ! -f "$LIB_DIR/okhttp.jar" ]; then
        echo "[信息] 下载依赖库..."
        curl -L -o "$LIB_DIR/okhttp.jar" "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"
        curl -L -o "$LIB_DIR/okio.jar" "https://repo1.maven.org/maven2/com/squareup/okio/okio/3.6.0/okio-3.6.0.jar"
        curl -L -o "$LIB_DIR/okio-jvm.jar" "https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar"
        curl -L -o "$LIB_DIR/kotlin-stdlib.jar" "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.21/kotlin-stdlib-1.9.21.jar"
        curl -L -o "$LIB_DIR/commons-codec.jar" "https://repo1.maven.org/maven2/commons-codec/commons-codec/1.16.0/commons-codec-1.16.0.jar"
        curl -L -o "$LIB_DIR/jackson-databind.jar" "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar"
        curl -L -o "$LIB_DIR/jackson-core.jar" "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar"
        curl -L -o "$LIB_DIR/jackson-annotations.jar" "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar"
        echo "[信息] 依赖下载完成"
    fi
    
    # 强制清理并重新编译
    echo "[信息] 清理旧编译文件..."
    rm -rf classes
    mkdir -p classes
    
    echo "[信息] 编译 Java 文件..."
    javac -cp "$LIB_DIR/*" -d classes -encoding UTF-8 *.java
    
    if [ $? -ne 0 ]; then
        echo "[错误] 编译失败"
        exit 1
    fi
    
    # 复制 index.html
    cp index.html classes/ 2>/dev/null
    echo "[信息] 编译完成"
    
    # 运行 (强制 UTF-8 编码)
    echo ""
    echo "[信息] 启动 Web 服务器..."
    echo ""
    cd classes
    java -Dfile.encoding=UTF-8 -cp ".:../lib/*" com.comp.testISAPI.ISAPIWebServer
else
    echo "[信息] 使用 Maven 构建..."
    echo ""
    
    # 强制清理并重新构建
    echo "[信息] 清理并重新构建项目..."
    mvn clean package -q -DskipTests
    
    if [ $? -ne 0 ]; then
        echo "[错误] 构建失败"
        exit 1
    fi
    
    # 复制 index.html 到 target
    cp index.html target/
    echo "[信息] 构建完成"
    
    # 运行 (强制 UTF-8 编码)
    echo ""
    echo "[信息] 启动 Web 服务器..."
    echo ""
    cd target
    java -Dfile.encoding=UTF-8 -jar testISAPI-1.0.0.jar
fi
