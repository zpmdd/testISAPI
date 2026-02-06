@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

echo ========================================
echo    FFmpeg 下载安装脚本 (Windows)
echo ========================================
echo.

cd /d "%~dp0"

:: 创建目录
if not exist "ffmpeg" mkdir ffmpeg
if not exist "ffmpeg\win" mkdir ffmpeg\win

:: 检查是否已存在
if exist "ffmpeg\win\ffmpeg.exe" (
    echo [信息] FFmpeg (Windows) 已存在，跳过下载
    ffmpeg\win\ffmpeg.exe -version 2>nul | findstr "ffmpeg version"
    goto :check_mac
)

echo [信息] 下载 FFmpeg (Windows)...
echo [信息] 源: https://www.gyan.dev/ffmpeg/builds/
echo.

set FFMPEG_URL=https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip
set FFMPEG_ZIP=ffmpeg\ffmpeg-win.zip

echo [信息] 正在下载，请稍候...
powershell -Command "& { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%FFMPEG_URL%' -OutFile '%FFMPEG_ZIP%' -UseBasicParsing }"

if not exist "%FFMPEG_ZIP%" (
    echo [错误] 下载失败！
    goto :check_mac
)

echo [信息] 解压中...
powershell -Command "Expand-Archive -Path '%FFMPEG_ZIP%' -DestinationPath 'ffmpeg\temp-win' -Force"

:: 移动文件
for /d %%d in (ffmpeg\temp-win\ffmpeg-*) do (
    if exist "%%d\bin\ffmpeg.exe" (
        copy "%%d\bin\ffmpeg.exe" "ffmpeg\win\" >nul
        copy "%%d\bin\ffprobe.exe" "ffmpeg\win\" >nul 2>nul
    )
)

:: 清理
rmdir /s /q "ffmpeg\temp-win" 2>nul
del "%FFMPEG_ZIP%" 2>nul

if exist "ffmpeg\win\ffmpeg.exe" (
    echo [成功] FFmpeg (Windows) 安装完成！
    ffmpeg\win\ffmpeg.exe -version 2>nul | findstr "ffmpeg version"
) else (
    echo [错误] FFmpeg (Windows) 安装失败
)

:check_mac
echo.
echo ========================================
echo    FFmpeg (macOS) 需要在 Mac 上下载
echo ========================================
echo.
echo 请在 Mac 上运行: ./setup-ffmpeg.sh
echo.

pause
