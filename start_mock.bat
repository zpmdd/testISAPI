@echo off
chcp 65001 >nul
title 海康威视 ISAPI 模拟服务器

echo ========================================
echo    海康威视 ISAPI 模拟服务器
echo ========================================
echo.

cd /d "%~dp0"

:: 检查 Python
python --version >nul 2>&1
if errorlevel 1 (
    python3 --version >nul 2>&1
    if errorlevel 1 (
        echo [错误] 未找到 Python，请先安装
        echo 下载地址: https://www.python.org/downloads/
        pause
        exit /b 1
    )
    set PYTHON_CMD=python3
) else (
    set PYTHON_CMD=python
)

for /f "tokens=*" %%g in ('%PYTHON_CMD% --version 2^>^&1') do (
    echo [信息] %%g
)
echo.

:: 启动模拟服务器
echo [信息] 启动模拟服务器...
echo.
%PYTHON_CMD% mock_server.py

pause
