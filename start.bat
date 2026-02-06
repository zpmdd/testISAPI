@echo off
chcp 65001 >nul
title ISAPI 录像下载工具

echo ========================================
echo    ISAPI 录像下载工具
echo ========================================
echo.

cd /d "%~dp0"

:: ========================================
:: 检查 Java 环境
:: ========================================
java -version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Java，请先安装 JDK 8 或更高版本
    echo 下载地址: https://adoptium.net/
    pause
    exit /b 1
)

for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    echo [信息] Java 版本: %%g
)

:: ========================================
:: 检查 FFmpeg 环境 (优先使用系统已安装)
:: ========================================
setlocal EnableDelayedExpansion
echo.
echo [信息] 检查 FFmpeg 环境...

:: 1. 优先检查系统已安装的 FFmpeg
where ffmpeg >nul 2>&1
if not errorlevel 1 (
    for /f "tokens=3" %%v in ('ffmpeg -version 2^>^&1 ^| findstr /i "ffmpeg version"') do (
        echo [信息] 使用系统 FFmpeg 版本: %%v
    )
    goto :ffmpeg_done
)

:: 2. 回退：项目内置 FFmpeg
if exist "ffmpeg\win\ffmpeg.exe" (
    echo [信息] 系统未安装 FFmpeg，使用项目内置: ffmpeg\win\ffmpeg.exe
    set "PATH=%cd%\ffmpeg\win;%PATH%"
    ffmpeg\win\ffmpeg.exe -version 2>nul | findstr "ffmpeg version"
    goto :ffmpeg_done
)

:: 3. 回退：旧目录结构
if exist "ffmpeg\bin\ffmpeg.exe" (
    echo [信息] 系统未安装 FFmpeg，使用本地: ffmpeg\bin\ffmpeg.exe
    set "PATH=%cd%\ffmpeg\bin;%PATH%"
    goto :ffmpeg_done
)

:: FFmpeg 未找到
echo.
echo [警告] FFmpeg 未安装！RTSP 直接截取功能将不可用。
echo.
echo 请选择:
echo   1. 自动下载安装到项目目录 (推荐)
echo   2. 跳过，稍后运行 setup-ffmpeg.bat 安装
echo.
set /p FFMPEG_CHOICE="请输入选择 (1/2): "

if "!FFMPEG_CHOICE!"=="1" (
    call :install_ffmpeg
) else (
    echo [信息] 跳过 FFmpeg 安装，RTSP 截取功能将不可用
)

:ffmpeg_done

:: 创建日志目录
if not exist "log" mkdir log
echo [信息] 日志目录: %cd%\log
echo.

:: ========================================
:: 检查 Maven 并构建
:: ========================================
where mvn >nul 2>&1
if errorlevel 1 (
    echo.
    echo [警告] 未找到 Maven，尝试使用备用方式...
    echo.
    
    :: 备用方式：直接下载依赖并运行
    if not exist "lib" mkdir lib
    
    :: 检查依赖是否已下载
    if not exist "lib\okhttp.jar" (
        echo [信息] 下载依赖库...
        powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar' -OutFile 'lib\okhttp.jar'"
        powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/squareup/okio/okio/3.6.0/okio-3.6.0.jar' -OutFile 'lib\okio.jar'"
        powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar' -OutFile 'lib\okio-jvm.jar'"
        powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.21/kotlin-stdlib-1.9.21.jar' -OutFile 'lib\kotlin-stdlib.jar'"
        powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/commons-codec/commons-codec/1.16.0/commons-codec-1.16.0.jar' -OutFile 'lib\commons-codec.jar'"
        powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar' -OutFile 'lib\jackson-databind.jar'"
        powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar' -OutFile 'lib\jackson-core.jar'"
        powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar' -OutFile 'lib\jackson-annotations.jar'"
        echo [信息] 依赖下载完成
    )
    
    :: 强制清理并重新编译
    echo [信息] 清理旧编译文件...
    if exist "classes" rmdir /s /q classes
    mkdir classes
    
    echo [信息] 编译 Java 文件...
    javac -cp "lib\*" -d classes -encoding UTF-8 *.java
    
    if errorlevel 1 (
        echo [错误] 编译失败
        pause
        exit /b 1
    )
    
    :: 复制 index.html
    copy index.html classes\ >nul 2>&1
    
    echo [信息] 编译完成
    
    :: 运行 (强制 UTF-8 编码)
    echo.
    echo [信息] 启动 Web 服务器...
    echo.
    cd classes
    java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -cp ".;..\lib\*" com.comp.testISAPI.ISAPIWebServer
) else (
    echo [信息] 使用 Maven 构建...
    echo.
    
    :: 强制清理并重新构建
    echo [信息] 清理并重新构建项目...
    call mvn clean package -q -DskipTests
    
    if errorlevel 1 (
        echo [错误] 构建失败
        pause
        exit /b 1
    )
    
    :: 复制 index.html 到 target
    copy index.html target\ >nul
    echo [信息] 构建完成
    
    :: 运行 (强制 UTF-8 编码)
    echo.
    echo [信息] 启动 Web 服务器...
    echo.
    cd target
    java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar testISAPI-1.0.0.jar
)

pause
goto :eof

:: ========================================
:: FFmpeg 自动安装函数 (安装到 ffmpeg\win\)
:: ========================================
:install_ffmpeg
setlocal EnableDelayedExpansion
echo.
echo [信息] 开始下载 FFmpeg...
echo [信息] 这可能需要几分钟，请耐心等待...
echo.

:: 创建目录
if not exist "ffmpeg" mkdir ffmpeg
if not exist "ffmpeg\win" mkdir ffmpeg\win

:: 下载 FFmpeg
set FFMPEG_URL=https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip
set FFMPEG_ZIP=ffmpeg\ffmpeg-win.zip

echo [信息] 下载地址: %FFMPEG_URL%
powershell -Command "& { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%FFMPEG_URL%' -OutFile '%FFMPEG_ZIP%' -UseBasicParsing }"

if not exist "%FFMPEG_ZIP%" (
    echo [错误] FFmpeg 下载失败
    echo [提示] 请运行 setup-ffmpeg.bat 手动安装
    endlocal
    goto :eof
)

echo [信息] 解压 FFmpeg...
powershell -Command "Expand-Archive -Path '%FFMPEG_ZIP%' -DestinationPath 'ffmpeg\temp-win' -Force"

:: 提取 ffmpeg.exe 到 ffmpeg\win\
for /d %%d in (ffmpeg\temp-win\ffmpeg-*) do (
    if exist "%%d\bin\ffmpeg.exe" (
        copy "%%d\bin\ffmpeg.exe" "ffmpeg\win\" >nul
        copy "%%d\bin\ffprobe.exe" "ffmpeg\win\" >nul 2>nul
    )
)

:: 清理
rmdir /s /q "ffmpeg\temp-win" 2>nul
del "%FFMPEG_ZIP%" 2>nul

:: 验证安装
if exist "ffmpeg\win\ffmpeg.exe" (
    echo [信息] FFmpeg 安装成功！
    set "PATH=%cd%\ffmpeg\win;%PATH%"
    ffmpeg\win\ffmpeg.exe -version 2>nul | findstr "ffmpeg version"
) else (
    echo [错误] FFmpeg 安装失败，请运行 setup-ffmpeg.bat
)

endlocal
goto :eof