# ISAPI 录像机管理工具

海康威视 ISAPI 协议录像管理与下载工具，提供 Web 管理界面，支持录像搜索、多模式下载、实时预览、云台控制等功能。

## 功能特性

- **设备管理** — 连接海康威视 NVR/DVR，查看设备信息、通道列表、存储状态
- **录像搜索** — 按通道和时间段搜索设备上的录像记录
- **多模式下载**
  - 文件下载（downloadPath）— 直接下载设备已存储的录像文件，速度快
  - 流式下载（playbackURI）— 通过流式接口分段下载，自动尝试 6 种下载方式
  - RTSP 时间段截取（推荐）— 使用 FFmpeg 按指定时间段截取回放流，生成单个连续文件
- **实时预览** — 生成 RTSP / HTTP-FLV 预览地址，可用 VLC 等播放器观看
- **云台控制（PTZ）** — 支持上下左右、变焦、预置点调用
- **存储管理** — 查看硬盘状态、容量、剩余空间
- **Web 界面** — 现代化深色主题 UI，无需安装额外前端依赖

## 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| Java (JDK) | >= 8 | 必需，推荐 [Adoptium](https://adoptium.net/) |
| Maven | >= 3.x | 可选，无 Maven 时自动下载依赖并编译 |
| FFmpeg | 任意版本 | 可选，仅 RTSP 时间段截取功能需要 |
| Python 3 | >= 3.6 | 可选，仅运行模拟服务器时需要 |

## 快速开始

### 1. 一键启动

**macOS / Linux：**

```bash
chmod +x start.sh
./start.sh
```

**Windows：**

```bat
start.bat
```

启动脚本会自动完成以下操作：
1. 检查 Java 环境
2. 检查 FFmpeg（未安装时可选择自动下载到项目目录）
3. 使用 Maven 构建项目（无 Maven 时自动下载依赖并手动编译）
4. 启动 Web 服务器

### 2. 访问管理界面

启动成功后，在浏览器访问：

```
http://localhost:8080
```

### 3. 连接设备

在 Web 界面中填写设备 IP、端口、用户名和密码，点击"连接设备"即可。

## 项目结构

```
testISAPI/
├── ISAPIWebServer.java       # Web 服务器主程序（入口）
├── ISAPIClient.java          # ISAPI 协议客户端封装
├── ISAPIQueryRecMain.java    # 命令行录像查询/下载工具
├── DigestAuthenticator.java  # HTTP Digest 认证实现
├── Logger.java               # 日志工具（控制台 + 文件）
├── index.html                # Web 管理界面
├── pom.xml                   # Maven 项目配置
├── mock_server.py            # ISAPI 模拟服务器（Python）
├── ffmpeg/                   # 内置 FFmpeg 可执行文件
│   ├── mac/ffmpeg            #   macOS 版本
│   └── win/ffmpeg.exe        #   Windows 版本
├── start.sh                  # macOS/Linux 一键启动脚本
├── start.bat                 # Windows 一键启动脚本
├── setup-ffmpeg.sh           # FFmpeg 安装脚本（macOS/Linux）
├── setup-ffmpeg.bat          # FFmpeg 安装脚本（Windows）
├── start_mock.sh             # 模拟服务器启动脚本（macOS/Linux）
└── start_mock.bat            # 模拟服务器启动脚本（Windows）
```

## 使用 Maven 构建

```bash
# 编译打包（生成 fat jar）
mvn clean package -DskipTests

# 运行
cd target
java -Dfile.encoding=UTF-8 -jar testISAPI-1.0.0.jar
```

## FFmpeg 安装

RTSP 时间段截取功能依赖 FFmpeg。可通过以下方式安装：

**方式一：运行安装脚本（推荐）**

```bash
# macOS / Linux
chmod +x setup-ffmpeg.sh
./setup-ffmpeg.sh

# Windows
setup-ffmpeg.bat
```

脚本会自动下载对应平台的 FFmpeg 到项目 `ffmpeg/` 目录。

**方式二：使用系统包管理器**

```bash
# macOS
brew install ffmpeg

# Ubuntu / Debian
sudo apt install ffmpeg

# Windows (Scoop)
scoop install ffmpeg
```

程序会按以下优先级查找 FFmpeg：项目内置 → 系统 PATH → 常见系统路径。

## API 接口

Web 服务器提供以下 REST 接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | Web 管理界面 |
| POST | `/api/device-info` | 获取设备信息 |
| POST | `/api/channels` | 获取通道列表 |
| POST | `/api/search` | 搜索录像（支持 `clientTimezoneOffsetMinutes`） |
| POST | `/api/download` | 下载录像（文件/流式，支持 `clientTimezoneOffsetMinutes`、`rtspPort`） |
| POST | `/api/rtsp-download` | RTSP 时间段截取下载（支持 `clientTimezoneOffsetMinutes`、`rtspPort`） |
| GET | `/api/download-status?taskId=xxx` | 查询下载进度 |
| DELETE | `/api/download-status?taskId=xxx` | 取消运行中任务或删除已完成任务记录 |
| POST | `/api/rtsp-url` | 获取 RTSP 预览地址 |
| POST | `/api/storage` | 获取存储状态 |
| POST | `/api/ptz` | 云台控制 |
| GET | `/downloads/{filename}` | 下载已保存的录像文件 |

`/api/download-status` 返回中新增了时间归一化与诊断字段：`timeMode`、`timeBasis`、`deviceTimeZone`、`normalizedStart`、`normalizedEnd`、`attemptedUrls`、`cancelRequested`。

## 环境变量配置

| 变量名 | 默认值 | 说明 |
|------|------|------|
| `ISAPI_TIME_MODE` | `DEVICE_LOCAL_LITERAL_Z` | 时间模式，可选 `DEVICE_LOCAL_LITERAL_Z` / `UTC_Z` |
| `MAX_DOWNLOAD_RANGE_MINUTES` | `1440` | 最大下载时间范围（分钟） |
| `STREAM_READ_TIMEOUT_SECONDS` | `600` | 流式下载读取超时（秒） |
| `FFMPEG_TIMEOUT_SECONDS` | `1800` | RTSP 截取 ffmpeg 超时（秒） |
| `TASK_TTL_MINUTES` | `30` | 已完成/失败/取消任务保留时长（分钟） |
| `MAX_TASK_LOG_LINES` | `500` | 单任务日志最大保留行数 |
| `RTSP_PORT_DEFAULT` | `554` | RTSP 默认端口 |
| `METHOD5_ENABLED` | `true` | 是否启用 StreamingProxy 回退下载方法 |

## 模拟服务器（开发测试）

项目内置 Python 模拟服务器，可在无真实设备的情况下进行开发调试：

```bash
# macOS / Linux
./start_mock.sh

# Windows
start_mock.bat

# 或直接运行
python3 mock_server.py
```

模拟服务器默认监听 `localhost:8000`，支持设备信息查询和录像搜索接口。在 Web 界面中将设备 IP 设为 `localhost`，端口设为 `8000` 即可连接。

## 技术栈

- **后端**：Java 8 + `com.sun.net.httpserver`（内置 HTTP 服务器）
- **HTTP 客户端**：OkHttp 4.12.0
- **认证方式**：HTTP Digest Authentication（Apache Commons Codec）
- **前端**：原生 HTML/CSS/JavaScript（单文件，无框架依赖）
- **视频截取**：FFmpeg（RTSP 流录制）
- **构建工具**：Maven 3.x（maven-shade-plugin 打 fat jar）

## 日志

日志同时输出到控制台和文件，日志文件按日期存放在 `./log/` 目录：

```
log/isapi_2026-02-05.log
```

支持 DEBUG、INFO、WARN、ERROR 四个级别。

## 注意事项

- 录像下载保存在 `./recordings/` 目录
- 建议单次搜索时间段不超过 1 小时，避免返回过多录像片段
- RTSP 截取模式需要设备支持 RTSP 回放功能
- 流式下载会自动尝试多种方式（GET+Token、GET+XML、POST+XML 等），兼容不同固件版本
- 搜索录像时会尝试 3 种 XML 命名空间格式，兼容不同设备型号

## License

Internal use / 内部使用
