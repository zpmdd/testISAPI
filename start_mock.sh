#!/bin/bash

# ============================================
# 海康威视 ISAPI 模拟服务器启动脚本
# ============================================

cd "$(dirname "$0")"

echo "========================================"
echo "   海康威视 ISAPI 模拟服务器"
echo "========================================"
echo ""

# 检查 Python3
if ! command -v python3 &> /dev/null; then
    echo "[错误] 未找到 Python3，请先安装"
    echo "macOS: brew install python3"
    echo "Ubuntu: sudo apt install python3"
    exit 1
fi

PYTHON_VERSION=$(python3 --version)
echo "[信息] $PYTHON_VERSION"
echo ""

# 启动模拟服务器
echo "[信息] 启动模拟服务器..."
echo ""
python3 mock_server.py
