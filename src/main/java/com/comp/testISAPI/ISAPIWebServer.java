package com.comp.testISAPI;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ISAPI 录像下载 Web 服务
 * 启动后访问 http://localhost:8080
 */
public class ISAPIWebServer {

    private static final Logger log = Logger.getLogger(ISAPIWebServer.class);

    private static final int PORT = 8080;
    private static final String DOWNLOAD_DIR = "./recordings";
    private static final Map<String, DownloadTask> downloadTasks = new ConcurrentHashMap<>();
    private static final Map<String, OkHttpClient> clientCache = new ConcurrentHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService MAINTENANCE = Executors.newSingleThreadScheduledExecutor();

    private static final String TIME_MODE = getEnv("ISAPI_TIME_MODE", "DEVICE_LOCAL_LITERAL_Z").toUpperCase(Locale.ROOT);
    private static final int MAX_DOWNLOAD_RANGE_MINUTES = getEnvInt("MAX_DOWNLOAD_RANGE_MINUTES", 1440);
    private static final int STREAM_READ_TIMEOUT_SECONDS = getEnvInt("STREAM_READ_TIMEOUT_SECONDS", 600);
    private static final int FFMPEG_TIMEOUT_SECONDS = getEnvInt("FFMPEG_TIMEOUT_SECONDS", 1800);
    private static final int TASK_TTL_MINUTES = getEnvInt("TASK_TTL_MINUTES", 30);
    private static final int MAX_TASK_LOG_LINES = getEnvInt("MAX_TASK_LOG_LINES", 500);
    private static final int RTSP_PORT_DEFAULT = getEnvInt("RTSP_PORT_DEFAULT", 554);
    private static final boolean METHOD5_ENABLED = getEnvBool("METHOD5_ENABLED", true);

    private static final DateTimeFormatter INPUT_LOCAL_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter SEARCH_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final DateTimeFormatter RTSP_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    public static void main(String[] args) throws IOException {
        log.info("========================================");
        log.info("ISAPI 录像下载服务启动中...");
        log.info("========================================");

        // 创建下载目录
        new File(DOWNLOAD_DIR).mkdirs();
        log.debug("下载目录: %s", new File(DOWNLOAD_DIR).getAbsolutePath());

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        log.debug("HTTP 服务器创建成功，端口: %d", PORT);

        // 路由配置
        server.createContext("/", new StaticHandler());
        server.createContext("/api/search", new SearchHandler());
        server.createContext("/api/download", new DownloadHandler());
        server.createContext("/api/download-status", new DownloadStatusHandler());
        server.createContext("/downloads/", new FileDownloadHandler());
        // 新增接口
        server.createContext("/api/device-info", new DeviceInfoHandler());
        server.createContext("/api/channels", new ChannelsHandler());
        server.createContext("/api/rtsp-url", new RtspUrlHandler());
        server.createContext("/api/rtsp-download", new RtspDownloadHandler());  // RTSP 时间段截取
        server.createContext("/api/storage", new StorageHandler());
        server.createContext("/api/ptz", new PtzHandler());
        log.debug("路由配置完成");

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        MAINTENANCE.scheduleAtFixedRate(ISAPIWebServer::cleanupExpiredTasks, 5, 5, TimeUnit.MINUTES);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[关闭] 开始清理下载任务与子进程");
            for (DownloadTask task : downloadTasks.values()) {
                cancelTask(task, "服务关闭");
            }
            MAINTENANCE.shutdownNow();
        }, "isapi-shutdown"));

        log.info("========================================");
        log.info("ISAPI 录像下载服务已启动");
        log.info("请访问: http://localhost:%d", PORT);
        log.info("日志目录: %s", new File("./log").getAbsolutePath());
        log.info("时间模式: %s, 最大时间范围(分钟): %d", TIME_MODE, MAX_DOWNLOAD_RANGE_MINUTES);
        log.info("========================================");
    }

    // 录像信息
    static class RecordingInfo {
        String trackId;
        String startTime;
        String endTime;
        String eventType;
        String downloadPath;
        String playbackURI;
        long contentLength; // 文件大小（如果可获取）

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("trackId", valueOrEmpty(trackId));
            map.put("startTime", valueOrEmpty(startTime));
            map.put("endTime", valueOrEmpty(endTime));
            map.put("eventType", valueOrEmpty(eventType));
            map.put("downloadPath", valueOrEmpty(downloadPath));
            map.put("playbackURI", valueOrEmpty(playbackURI));
            map.put("contentLength", contentLength);
            return map;
        }
    }

    // 下载任务
    static class DownloadTask {
        volatile String taskId;
        volatile String status = "pending"; // pending, downloading, completed, failed, cancelled
        volatile String downloadMode = "file"; // file: 文件下载, stream: 流式下载
        volatile int total;
        volatile int current;
        volatile int success;
        volatile int failed;
        volatile String currentFile = "";
        volatile String message = "";
        volatile long totalBytes = 0; // 当前文件已下载字节数
        volatile long expectedBytes = 0; // 当前文件预期字节数（流式下载时为0）
        volatile long totalDownloadedBytes = 0; // 所有文件总下载字节数
        volatile String timeMode = TIME_MODE;
        volatile String timeBasis = "";
        volatile String deviceTimeZone = "";
        volatile String normalizedStart = "";
        volatile String normalizedEnd = "";
        volatile boolean cancelRequested = false;
        volatile long createdAt = System.currentTimeMillis();
        volatile long updatedAt = System.currentTimeMillis();
        volatile long finishedAt = 0;
        volatile Process activeProcess;
        volatile Call activeCall;
        volatile String requestedMethod = "";   // 用户请求的方式: "isapi-http" / "rtsp"
        volatile String effectiveMethod = "";   // 实际生效方式: "isapi-http" / "rtsp"
        volatile boolean fallbackUsed = false;  // 是否发生了回退
        List<String> downloadedFiles = new CopyOnWriteArrayList<>();
        List<String> logs = new CopyOnWriteArrayList<>();
        List<String> attemptedUrls = new CopyOnWriteArrayList<>();
    }

    // 静态文件处理
    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = getHtmlPage();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    // 搜索录像
    static class SearchHandler implements HttpHandler {
        private final Logger log = Logger.getLogger(SearchHandler.class);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            log.info("[搜索请求] 客户端IP: %s", clientIp);

            if (!"POST".equals(exchange.getRequestMethod())) {
                log.warn("不支持的请求方法: %s", exchange.getRequestMethod());
                sendJson(exchange, 405, errorResponse("METHOD_NOT_ALLOWED", "Method not allowed"));
                return;
            }

            try {
                Map<String, String> params = parseFormData(exchange);
                String deviceIp = params.get("deviceIp");
                int port = Integer.parseInt(params.getOrDefault("port", "80"));
                String username = params.get("username");
                String password = params.get("password");
                String channelId = params.get("channelId");
                String startTime = params.get("startTime");
                String endTime = params.get("endTime");
                Integer clientTzOffsetMinutes = parseNullableInt(params.get("clientTimezoneOffsetMinutes"));

                log.info("[搜索参数] 设备: %s:%d, 用户: %s, 通道: %s", deviceIp, port, username, channelId);
                log.info("[搜索参数] 时间范围: %s ~ %s", startTime, endTime);

                OkHttpClient client = getClient(deviceIp, username, password);
                TimeRange resolved = resolveTimeRange(client, deviceIp, port, startTime, endTime, clientTzOffsetMinutes);

                log.debug("开始连接设备...");
                List<RecordingInfo> recordings = searchRecordings(client, deviceIp, port, channelId, resolved.searchStart, resolved.searchEnd);

                log.info("[搜索结果] 找到 %d 条录像", recordings.size());
                for (int i = 0; i < recordings.size(); i++) {
                    RecordingInfo rec = recordings.get(i);
                    log.debug("  [%d] 通道:%s 时间:%s~%s 类型:%s", 
                            i + 1, rec.trackId, rec.startTime, rec.endTime, rec.eventType);
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                for (RecordingInfo rec : recordings) {
                    rows.add(rec.toMap());
                }
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("count", recordings.size());
                response.put("recordings", rows);
                response.put("timeMode", TIME_MODE);
                response.put("timeBasis", resolved.timeBasis);
                response.put("deviceTimeZone", valueOrEmpty(resolved.deviceTimeZone));
                response.put("normalizedStart", resolved.searchStart);
                response.put("normalizedEnd", resolved.searchEnd);
                sendJson(exchange, 200, response);

            } catch (IllegalArgumentException e) {
                log.warn("[搜索参数错误] %s", e.getMessage());
                sendJson(exchange, 400, errorResponse("INVALID_TIME_RANGE", e.getMessage()));
            } catch (Exception e) {
                log.error("[搜索失败] " + e.getMessage(), e);
                sendJson(exchange, 500, errorResponse("SEARCH_FAILED", e.getMessage()));
            }
        }
    }

    // 下载录像
    static class DownloadHandler implements HttpHandler {
        private final Logger log = Logger.getLogger(DownloadHandler.class);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            log.info("[下载请求] 客户端IP: %s", clientIp);

            if (!"POST".equals(exchange.getRequestMethod())) {
                log.warn("不支持的请求方法: %s", exchange.getRequestMethod());
                sendJson(exchange, 405, errorResponse("METHOD_NOT_ALLOWED", "Method not allowed"));
                return;
            }

            try {
                Map<String, String> params = parseFormData(exchange);
                String deviceIp = params.get("deviceIp");
                int port = Integer.parseInt(params.getOrDefault("port", "80"));
                String username = params.get("username");
                String password = params.get("password");
                String channelId = params.get("channelId");
                String startTime = params.get("startTime");
                String endTime = params.get("endTime");
                String downloadMode = params.getOrDefault("downloadMode", "file"); // file 或 stream
                Integer clientTzOffsetMinutes = parseNullableInt(params.get("clientTimezoneOffsetMinutes"));
                Integer rtspPort = parseNullableInt(params.get("rtspPort"));
                if (rtspPort != null && (rtspPort <= 0 || rtspPort > 65535)) {
                    throw new IllegalArgumentException("rtspPort 必须在 1-65535 之间");
                }

                log.info("[下载参数] 设备: %s:%d, 用户: %s, 通道: %s", deviceIp, port, username, channelId);
                log.info("[下载参数] 时间范围: %s ~ %s", startTime, endTime);
                log.info("[下载参数] 下载模式: %s", downloadMode.equals("stream") ? "流式下载" : "文件下载");
                if (rtspPort != null) {
                    log.info("[下载参数] RTSP端口(透传): %d", rtspPort);
                }

                OkHttpClient initialClient = getClient(deviceIp, username, password);
                TimeRange resolved = resolveTimeRange(initialClient, deviceIp, port, startTime, endTime, clientTzOffsetMinutes);

                String taskId = UUID.randomUUID().toString().substring(0, 8);
                DownloadTask task = new DownloadTask();
                task.taskId = taskId;
                task.status = "pending";
                task.downloadMode = downloadMode;
                task.timeBasis = resolved.timeBasis;
                task.deviceTimeZone = valueOrEmpty(resolved.deviceTimeZone);
                task.normalizedStart = resolved.searchStart;
                task.normalizedEnd = resolved.searchEnd;
                touchTask(task);
                downloadTasks.put(taskId, task);

                log.info("[下载任务] 创建任务 ID: %s, 模式: %s", taskId, downloadMode);

                // 异步执行下载
                final String fStart = resolved.searchStart;
                final String fEnd = resolved.searchEnd;
                final String fDownloadMode = downloadMode;
                new Thread(() -> {
                    Logger tLog = Logger.getLogger(DownloadHandler.class);
                    try {
                        String logMsg = String.format("[任务 %s] 开始搜索录像...", taskId);
                        tLog.info(logMsg);
                        addTaskLog(task, logMsg);
                        
                        OkHttpClient client = getClient(deviceIp, username, password);
                        List<RecordingInfo> recordings = searchRecordings(client, deviceIp, port, channelId, fStart, fEnd);

                        task.total = recordings.size();
                        task.status = "downloading";
                        touchTask(task);
                        
                        logMsg = String.format("[任务 %s] 找到 %d 条录像，开始%s...", 
                                taskId, recordings.size(), fDownloadMode.equals("stream") ? "流式下载" : "文件下载");
                        tLog.info(logMsg);
                        addTaskLog(task, logMsg);

                        for (int i = 0; i < recordings.size(); i++) {
                            if (task.cancelRequested) {
                                task.status = "cancelled";
                                task.message = "任务已取消";
                                task.finishedAt = System.currentTimeMillis();
                                touchTask(task);
                                addTaskLog(task, String.format("[任务 %s] 任务已取消", taskId));
                                return;
                            }
                            RecordingInfo rec = recordings.get(i);
                            task.current = i + 1;
                            task.currentFile = rec.startTime;
                            task.totalBytes = 0;
                            task.expectedBytes = rec.contentLength;
                            touchTask(task);

                            String fileName = generateFileName(rec, i);
                            String savePath = DOWNLOAD_DIR + "/" + fileName;

                            logMsg = String.format("[任务 %s] 下载 %d/%d: %s", taskId, i + 1, recordings.size(), fileName);
                            tLog.info(logMsg);
                            addTaskLog(task, logMsg);

                            try {
                                long startMs = System.currentTimeMillis();
                                long downloadedBytes;
                                
                                if ("stream".equals(fDownloadMode)) {
                                    // 流式下载
                                    logMsg = String.format("[任务 %s] 使用流式下载, playbackURI: %s", taskId, 
                                            rec.playbackURI != null ? rec.playbackURI.substring(0, Math.min(80, rec.playbackURI.length())) + "..." : "null");
                                    tLog.debug(logMsg);
                                    addTaskLog(task, logMsg);
                                    
                                    downloadedBytes = downloadStream(client, deviceIp, port, rec, savePath, task);
                                } else {
                                    // 文件下载
                                    logMsg = String.format("[任务 %s] 使用文件下载, downloadPath: %s", taskId, 
                                            rec.downloadPath != null ? rec.downloadPath.substring(0, Math.min(80, rec.downloadPath.length())) + "..." : "null");
                                    tLog.debug(logMsg);
                                    addTaskLog(task, logMsg);
                                    
                                    downloadedBytes = downloadFileWithProgress(client, deviceIp, port, rec.downloadPath, savePath, task);
                                }
                                
                                long elapsed = System.currentTimeMillis() - startMs;
                                double sizeMB = downloadedBytes / 1024.0 / 1024.0;
                                double speedMBps = elapsed > 0 ? (sizeMB / (elapsed / 1000.0)) : 0;
                                
                                logMsg = String.format("[任务 %s] 下载完成: %s (%.2f MB, 耗时 %d ms, 速度 %.2f MB/s)", 
                                        taskId, fileName, sizeMB, elapsed, speedMBps);
                                tLog.info(logMsg);
                                addTaskLog(task, logMsg);
                                
                                task.success++;
                                task.totalDownloadedBytes += downloadedBytes;
                                task.downloadedFiles.add(fileName);
                                touchTask(task);
                                
                            } catch (Exception e) {
                                logMsg = String.format("[任务 %s] 下载失败: %s - %s", taskId, fileName, e.getMessage());
                                tLog.error(logMsg, e);
                                addTaskLog(task, logMsg);
                                task.failed++;
                                task.message = e.getMessage();
                                touchTask(task);
                            }
                        }

                        if (!task.cancelRequested) {
                            task.status = "completed";
                            double totalMB = task.totalDownloadedBytes / 1024.0 / 1024.0;
                            task.message = String.format("下载完成 (总计 %.2f MB)", totalMB);
                            task.finishedAt = System.currentTimeMillis();
                            touchTask(task);
                        
                            logMsg = String.format("[任务 %s] 全部完成！成功: %d, 失败: %d, 总大小: %.2f MB", 
                                    taskId, task.success, task.failed, totalMB);
                            tLog.info(logMsg);
                            addTaskLog(task, logMsg);
                        }

                    } catch (Exception e) {
                        task.status = "failed";
                        task.message = e.getMessage();
                        task.finishedAt = System.currentTimeMillis();
                        touchTask(task);
                        String logMsg = String.format("[任务 %s] 任务失败: %s", taskId, e.getMessage());
                        tLog.error(logMsg, e);
                        addTaskLog(task, logMsg);
                    }
                }).start();

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("taskId", taskId);
                response.put("downloadMode", downloadMode);
                if (rtspPort != null) {
                    response.put("rtspPort", rtspPort);
                }
                sendJson(exchange, 200, response);

            } catch (IllegalArgumentException e) {
                log.warn("[下载参数错误] %s", e.getMessage());
                sendJson(exchange, 400, errorResponse("INVALID_TIME_RANGE", e.getMessage()));
            } catch (Exception e) {
                log.error("[下载请求失败] " + e.getMessage(), e);
                sendJson(exchange, 500, errorResponse("DOWNLOAD_REQUEST_FAILED", e.getMessage()));
            }
        }
    }

    // 时间段截取下载 - 支持 ISAPI HTTP 快速下载和 FFmpeg RTSP 两种方式
    static class RtspDownloadHandler implements HttpHandler {
        private final Logger log = Logger.getLogger(RtspDownloadHandler.class);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
            log.info("[时间段截取] 客户端IP: %s", clientIp);

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorResponse("METHOD_NOT_ALLOWED", "Method not allowed"));
                return;
            }

            try {
                Map<String, String> params = parseFormData(exchange);
                String deviceIp = params.get("deviceIp");
                int port = Integer.parseInt(params.getOrDefault("port", "80"));
                String username = params.get("username");
                String password = params.get("password");
                String channelId = params.get("channelId");
                String startTime = params.get("startTime");
                String endTime = params.get("endTime");
                int rtspPort = Integer.parseInt(params.getOrDefault("rtspPort", String.valueOf(RTSP_PORT_DEFAULT)));
                if (rtspPort <= 0 || rtspPort > 65535) {
                    throw new IllegalArgumentException("rtspPort 必须在 1-65535 之间");
                }
                String downloadMethod = valueOrEmpty(params.getOrDefault("downloadMethod", "rtsp"))
                        .trim().toLowerCase(Locale.ROOT); // "isapi-http" 或 "rtsp"
                if (!"isapi-http".equals(downloadMethod) && !"rtsp".equals(downloadMethod)) {
                    throw new IllegalArgumentException("downloadMethod 仅支持 isapi-http 或 rtsp");
                }
                Integer clientTzOffsetMinutes = parseNullableInt(params.get("clientTimezoneOffsetMinutes"));

                log.info("[时间段截取] 设备: %s:%d, 通道: %s, 方式: %s", deviceIp, port, channelId, downloadMethod);
                log.info("[时间段截取] 时间范围: %s ~ %s", startTime, endTime);

                OkHttpClient initialClient = getClient(deviceIp, username, password);
                TimeRange resolved = resolveTimeRange(initialClient, deviceIp, port, startTime, endTime, clientTzOffsetMinutes);
                String rtspStart = resolved.rtspStart;
                String rtspEnd = resolved.rtspEnd;

                String taskId = UUID.randomUUID().toString().substring(0, 8);
                DownloadTask task = new DownloadTask();
                task.taskId = taskId;
                task.status = "pending";
                task.downloadMode = downloadMethod;
                task.requestedMethod = downloadMethod;
                task.total = 1;  // 只有一个文件
                task.timeBasis = resolved.timeBasis;
                task.deviceTimeZone = valueOrEmpty(resolved.deviceTimeZone);
                task.normalizedStart = resolved.searchStart;
                task.normalizedEnd = resolved.searchEnd;
                touchTask(task);
                downloadTasks.put(taskId, task);

                log.info("[时间段截取] 创建任务 ID: %s, 方式: %s", taskId, downloadMethod);

                // 异步执行下载 — 捕获所有线程内需要的 final 变量
                final String fRtspStart = rtspStart;
                final String fRtspEnd = rtspEnd;
                final String fChannelId = channelId;
                final String fUsername = username;
                final String fPassword = password;
                final String fDeviceIp = deviceIp;
                final int fRtspPort = rtspPort;
                final int fPort = port;
                final String fDownloadMethod = downloadMethod;
                final OkHttpClient fClient = initialClient;
                new Thread(() -> {
                    Logger tLog = Logger.getLogger(RtspDownloadHandler.class);
                    try {
                        task.status = "downloading";
                        task.current = 1;
                        touchTask(task);
                        
                        // 生成文件名
                        String fileName = String.format("ch%s_%s_to_%s.mp4", 
                                fChannelId, 
                                fRtspStart.replace("T", "_").replace("Z", ""),
                                fRtspEnd.replace("T", "_").replace("Z", ""));
                        String savePath = DOWNLOAD_DIR + "/" + fileName;
                        task.currentFile = fileName;
                        
                        String logMsg = String.format("[时间段截取] 开始下载: %s (方式: %s)", fileName, fDownloadMethod);
                        tLog.info(logMsg);
                        addTaskLog(task, logMsg);

                        long startMs = System.currentTimeMillis();
                        long downloadedBytes;

                        if ("isapi-http".equals(fDownloadMethod)) {
                            // ISAPI HTTP 快速下载，失败自动回退到 FFmpeg RTSP
                            task.effectiveMethod = "isapi-http";
                            try {
                                addTaskLog(task, "使用 ISAPI HTTP 快速下载...");
                                downloadedBytes = downloadViaISAPIHttp(fClient, fDeviceIp, fPort, fChannelId,
                                        fRtspStart, fRtspEnd, savePath, task);
                            } catch (Exception e) {
                                if (task.cancelRequested || isCancellationException(e)) {
                                    throw e;
                                }
                                String fallbackMsg = "ISAPI HTTP 失败 (" + e.getMessage() + ")，回退到 FFmpeg RTSP...";
                                tLog.warn("[时间段截取] %s", fallbackMsg);
                                addTaskLog(task, fallbackMsg);
                                task.effectiveMethod = "rtsp";
                                task.fallbackUsed = true;
                                task.downloadMode = "rtsp";
                                touchTask(task);
                                downloadedBytes = downloadRtspStream(fDeviceIp, fUsername, fPassword, fRtspPort,
                                        fChannelId, fRtspStart, fRtspEnd, savePath, task);
                            }
                        } else {
                            // 现有 FFmpeg RTSP 方式，完全不变
                            task.effectiveMethod = "rtsp";
                            downloadedBytes = downloadRtspStream(fDeviceIp, fUsername, fPassword, fRtspPort,
                                    fChannelId, fRtspStart, fRtspEnd, savePath, task);
                        }
                        
                        long elapsed = System.currentTimeMillis() - startMs;
                        double sizeMB = downloadedBytes / 1024.0 / 1024.0;
                        double speedMBps = elapsed > 0 ? (sizeMB / (elapsed / 1000.0)) : 0;

                        task.success = 1;
                        task.totalDownloadedBytes = downloadedBytes;
                        task.downloadedFiles.add(fileName);
                        task.finishedAt = System.currentTimeMillis();
                        touchTask(task);
                        
                        logMsg = String.format("[时间段截取] 下载完成: %s (%.2f MB, 耗时 %d ms, 速度 %.2f MB/s, 方式: %s%s)", 
                                fileName, sizeMB, elapsed, speedMBps, task.effectiveMethod,
                                task.fallbackUsed ? ", 已回退" : "");
                        tLog.info(logMsg);
                        addTaskLog(task, logMsg);

                        task.status = "completed";
                        task.message = String.format("下载完成 (%.2f MB)", sizeMB);
                        touchTask(task);

                    } catch (Exception e) {
                        if (task.cancelRequested) {
                            task.status = "cancelled";
                            task.message = "任务已取消";
                        } else {
                            task.status = isTimeoutFailure(e) ? "failed(timeout)" : "failed";
                            task.failed = 1;
                            task.message = e.getMessage();
                        }
                        task.finishedAt = System.currentTimeMillis();
                        touchTask(task);
                        String logMsg = String.format("[时间段截取] 任务失败: %s", e.getMessage());
                        tLog.error(logMsg, e);
                        addTaskLog(task, logMsg);
                    }
                }).start();

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("taskId", taskId);
                response.put("downloadMode", task.downloadMode);
                response.put("downloadMethod", downloadMethod);
                response.put("rtspPort", rtspPort);
                sendJson(exchange, 200, response);

            } catch (IllegalArgumentException e) {
                log.warn("[时间段截取参数错误] %s", e.getMessage());
                sendJson(exchange, 400, errorResponse("INVALID_TIME_RANGE", e.getMessage()));
            } catch (Exception e) {
                log.error("[时间段截取失败] " + e.getMessage(), e);
                sendJson(exchange, 500, errorResponse("RTSP_DOWNLOAD_FAILED", e.getMessage()));
            }
        }
    }

    // RTSP 时间段流下载 - 使用 ffmpeg 实现精确时间截取
    private static long downloadRtspStream(String deviceIp, String username, String password, int rtspPort,
                                           String channelId, String startTime, String endTime,
                                           String saveFilePath, DownloadTask task) throws IOException {
        Logger log = Logger.getLogger(ISAPIWebServer.class);
        
        log.info("========================================");
        log.info("[RTSP截取] 开始 RTSP 精确时间截取 (ffmpeg)");
        log.info("[RTSP截取] 设备: %s, 通道: %s, RTSP端口: %d", deviceIp, channelId, rtspPort);
        log.info("[RTSP截取] 时间: %s ~ %s", startTime, endTime);
        log.info("[RTSP截取] 保存: %s", saveFilePath);
        log.info("========================================");
        
        addTaskLog(task, String.format("精确时间: %s ~ %s", startTime, endTime));
        
        // 检查 ffmpeg 是否可用
        String ffmpegPath = findFfmpeg();
        if (ffmpegPath == null) {
            String errMsg = "未找到 ffmpeg，请先安装 ffmpeg 并确保在系统 PATH 中";
            log.error("[RTSP截取] %s", errMsg);
            addTaskLog(task, "错误: " + errMsg);
            throw new IOException(errMsg);
        }
        log.info("[RTSP截取] ffmpeg 路径: %s", ffmpegPath);
        addTaskLog(task, "ffmpeg: " + ffmpegPath);

        String encodedUser = encodeUserInfo(username);
        String encodedPassword = encodeUserInfo(password);
        String query = String.format("starttime=%s&endtime=%s", startTime, endTime);
        List<String> rtspUrls = Arrays.asList(
                String.format("rtsp://%s:%s@%s:%d/Streaming/tracks/%s/?%s", encodedUser, encodedPassword, deviceIp, rtspPort, channelId, query),
                String.format("rtsp://%s:%s@%s:%d/Streaming/tracks/%s?%s", encodedUser, encodedPassword, deviceIp, rtspPort, channelId, query),
                String.format("rtsp://%s:%s@%s:%d/Streaming/channels/%s?%s", encodedUser, encodedPassword, deviceIp, rtspPort, channelId, query),
                String.format("rtsp://%s:%s@%s:%d/ISAPI/Streaming/tracks/%s?%s", encodedUser, encodedPassword, deviceIp, rtspPort, channelId, query)
        );

        IOException lastError = null;
        for (String rtspUrl : rtspUrls) {
            if (task.cancelRequested) {
                throw new IOException("任务已取消");
            }
            addAttemptedUrl(task, maskRtspUrl(rtspUrl));
            try {
                long bytes = runFfmpegCapture(ffmpegPath, rtspUrl, saveFilePath, task);
                task.totalBytes = bytes;
                return bytes;
            } catch (IOException e) {
                lastError = e;
                log.warn("[RTSP截取] URL 尝试失败: %s, %s", maskRtspUrl(rtspUrl), e.getMessage());
                addTaskLog(task, "URL失败: " + e.getMessage());
                File out = new File(saveFilePath);
                if (out.exists() && out.length() == 0) {
                    // 清理空文件，准备下一次尝试
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("所有 RTSP URL 模板均失败");
    }
    
    // 查找 ffmpeg 可执行文件 (优先使用系统已安装，项目内置作为回退)
    private static String findFfmpeg() {
        String os = System.getProperty("os.name").toLowerCase();
        boolean isWindows = os.contains("win");
        boolean isMac = os.contains("mac");
        
        List<String> paths = new ArrayList<>();
        
        // 1. 系统 PATH（最高优先级：用户已安装的版本）
        paths.add("ffmpeg");
        if (isWindows) {
            paths.add("ffmpeg.exe");
        }
        
        // 2. 常见系统安装路径
        if (isWindows) {
            paths.add("C:\\ffmpeg\\bin\\ffmpeg.exe");
            paths.add("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe");
        } else {
            paths.add("/usr/bin/ffmpeg");
            paths.add("/usr/local/bin/ffmpeg");
            paths.add("/opt/homebrew/bin/ffmpeg");
        }
        
        // 3. 用户目录
        paths.add(System.getProperty("user.home") + (isWindows ? "\\ffmpeg\\bin\\ffmpeg.exe" : "/ffmpeg/bin/ffmpeg"));
        
        // 4. 项目内置（回退）
        if (isWindows) {
            paths.add("./ffmpeg/win/ffmpeg.exe");
            paths.add("ffmpeg\\win\\ffmpeg.exe");
        } else if (isMac) {
            paths.add("./ffmpeg/mac/ffmpeg");
        } else {
            paths.add("./ffmpeg/linux/ffmpeg");
        }
        
        // 5. 旧目录结构兼容（最低优先级）
        paths.add(isWindows ? "./ffmpeg/bin/ffmpeg.exe" : "./ffmpeg/bin/ffmpeg");
        
        for (String path : paths) {
            try {
                ProcessBuilder pb = new ProcessBuilder(path, "-version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int exitCode = p.waitFor();
                if (exitCode == 0) {
                    log.info("[ffmpeg] 找到: %s", path);
                    return path;
                }
            } catch (Exception e) {
                // 继续尝试下一个路径
            }
        }
        
        return null;
    }

    // 下载状态查询
    static class DownloadStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String taskId = parseQuery(exchange.getRequestURI().getQuery()).get("taskId");
            if (taskId == null || taskId.trim().isEmpty()) {
                sendJson(exchange, 400, errorResponse("INVALID_TASK_ID", "taskId 不能为空"));
                return;
            }

            DownloadTask task = downloadTasks.get(taskId);
            if (task == null) {
                sendJson(exchange, 404, errorResponse("TASK_NOT_FOUND", "Task not found"));
                return;
            }

            if ("DELETE".equalsIgnoreCase(method)) {
                if ("downloading".equals(task.status) || "pending".equals(task.status)) {
                    cancelTask(task, "客户端取消");
                    sendJson(exchange, 200, successResponse("任务取消成功", taskId));
                    return;
                }
                downloadTasks.remove(taskId);
                sendJson(exchange, 200, successResponse("任务已删除", taskId));
                return;
            }

            if (!"GET".equalsIgnoreCase(method)) {
                sendJson(exchange, 405, errorResponse("METHOD_NOT_ALLOWED", "Method not allowed"));
                return;
            }

            sendJson(exchange, 200, buildTaskSnapshot(task));
        }
    }

    // 文件下载
    static class FileDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String fileName = path.substring("/downloads/".length()).trim();
            if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
                sendJson(exchange, 400, errorResponse("INVALID_FILE_NAME", "非法文件名"));
                return;
            }

            File root = new File(DOWNLOAD_DIR).getCanonicalFile();
            File file = new File(root, fileName).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator) && !file.equals(root)) {
                sendJson(exchange, 403, errorResponse("FORBIDDEN_PATH", "禁止访问"));
                return;
            }

            if (!file.exists() || !file.isFile()) {
                sendJson(exchange, 404, errorResponse("FILE_NOT_FOUND", "File not found"));
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            exchange.sendResponseHeaders(200, file.length());

            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    // 创建HTTP客户端
    private static OkHttpClient createClient(String username, String password) {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .authenticator(new DigestAuthenticator(username, password))
                .build();
    }

    private static OkHttpClient getClient(String deviceIp, String username, String password) {
        String key = deviceIp + "|" + username + "|" + password;
        return clientCache.computeIfAbsent(key, k -> createClient(username, password));
    }

    // 搜索录像（支持多种 XML 格式尝试）
    private static List<RecordingInfo> searchRecordings(OkHttpClient client, String ip, int port,
                                                         String channelId, String start, String end) throws Exception {
        Logger log = Logger.getLogger(ISAPIWebServer.class);
        
        // 生成 UUID 格式的 searchID
        String searchId = String.format("{%s}", UUID.randomUUID().toString().toUpperCase());
        
        // 尝试多种 XML 格式（不同固件版本可能需要不同格式）
        String[] xmlFormats = {
            // 格式1：使用 isapi.org 命名空间
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<CMSearchDescription version=\"1.0\" xmlns=\"http://www.isapi.org/ver20/XMLSchema\">" +
            "<searchID>%s</searchID>" +
            "<trackList><trackID>%s</trackID></trackList>" +
            "<timeSpanList><timeSpan>" +
            "<startTime>%s</startTime>" +
            "<endTime>%s</endTime>" +
            "</timeSpan></timeSpanList>" +
            "<maxResults>100</maxResults>" +
            "<searchResultPosition>0</searchResultPosition>" +
            "</CMSearchDescription>",
            
            // 格式2：使用 hikvision.com 命名空间
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<CMSearchDescription version=\"2.0\" xmlns=\"http://www.hikvision.com/ver20/XMLSchema\">" +
            "<searchID>%s</searchID>" +
            "<trackList><trackID>%s</trackID></trackList>" +
            "<timeSpanList><timeSpan>" +
            "<startTime>%s</startTime>" +
            "<endTime>%s</endTime>" +
            "</timeSpan></timeSpanList>" +
            "<maxResults>100</maxResults>" +
            "<searchResultPosition>0</searchResultPosition>" +
            "</CMSearchDescription>",
            
            // 格式3：无命名空间
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<CMSearchDescription>" +
            "<searchID>%s</searchID>" +
            "<trackList><trackID>%s</trackID></trackList>" +
            "<timeSpanList><timeSpan>" +
            "<startTime>%s</startTime>" +
            "<endTime>%s</endTime>" +
            "</timeSpan></timeSpanList>" +
            "<maxResults>100</maxResults>" +
            "<searchResultPosition>0</searchResultPosition>" +
            "</CMSearchDescription>"
        };
        
        String lastError = "";
        String lastResponseBody = "";
        
        for (int i = 0; i < xmlFormats.length; i++) {
            String xmlBody = String.format(xmlFormats[i], searchId, channelId, start, end);
            
            log.info("[搜索] 尝试格式 %d/3，请求 XML:\n%s", i + 1, xmlBody);
            
            Request request = new Request.Builder()
                    .url(String.format("http://%s:%d/ISAPI/ContentMgmt/search", ip, port))
                    .post(RequestBody.create(MediaType.parse("application/xml; charset=utf-8"), xmlBody))
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body().string();
                lastResponseBody = responseBody;
                
                if (response.isSuccessful()) {
                    log.info("[搜索] 格式 %d 成功！", i + 1);
                    log.debug("[搜索] 响应 XML:\n%s", responseBody.length() > 2000 ? responseBody.substring(0, 2000) + "..." : responseBody);
                    return parseResponse(responseBody);
                }
                
                lastError = extractErrorMessage(responseBody);
                log.warn("[搜索] 格式 %d 失败 (HTTP %d): %s", i + 1, response.code(), lastError);
            } catch (IOException e) {
                lastError = e.getMessage();
                log.warn("[搜索] 格式 %d 请求异常: %s", i + 1, e.getMessage());
            }
        }
        
        // 所有格式都失败
        log.error("[搜索] 所有格式都失败，最后响应:\n%s", lastResponseBody);
        throw new IOException("搜索失败: " + lastError);
    }
    
    // 从错误响应中提取错误信息
    private static String extractErrorMessage(String xml) {
        try {
            // 尝试提取 <statusString> 或 <subStatusCode> 内容
            if (xml.contains("<statusString>")) {
                int s = xml.indexOf("<statusString>") + 14;
                int e = xml.indexOf("</statusString>");
                if (e > s) {
                    String statusString = xml.substring(s, e);
                    // 同时获取 subStatusCode
                    if (xml.contains("<subStatusCode>")) {
                        int s2 = xml.indexOf("<subStatusCode>") + 15;
                        int e2 = xml.indexOf("</subStatusCode>");
                        if (e2 > s2) {
                            return statusString + " (" + xml.substring(s2, e2) + ")";
                        }
                    }
                    return statusString;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return xml.length() > 300 ? xml.substring(0, 300) : xml;
    }

    // 解析响应
    private static List<RecordingInfo> parseResponse(String xml) throws Exception {
        Logger parseLog = Logger.getLogger(ISAPIWebServer.class);
        List<RecordingInfo> recordings = new ArrayList<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        NodeList items = doc.getElementsByTagName("searchMatchItem");
        parseLog.debug("[解析] 找到 %d 条录像记录", items.getLength());
        
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            RecordingInfo rec = new RecordingInfo();
            rec.trackId = getElementText(item, "trackID");
            rec.startTime = getElementText(item, "startTime");
            rec.endTime = getElementText(item, "endTime");
            rec.eventType = getElementText(item, "eventType");
            rec.downloadPath = getElementText(item, "downloadPath");
            rec.playbackURI = getElementText(item, "playbackURI");
            
            // 尝试获取文件大小
            String sizeStr = getElementText(item, "contentLength");
            if (sizeStr != null && !sizeStr.isEmpty()) {
                try {
                    rec.contentLength = Long.parseLong(sizeStr);
                } catch (NumberFormatException e) {
                    rec.contentLength = 0;
                }
            }
            
            parseLog.debug("[解析] 录像 %d: trackId=%s, playbackURI=%s, downloadPath=%s", 
                    i + 1, rec.trackId, 
                    rec.playbackURI != null ? rec.playbackURI.substring(0, Math.min(50, rec.playbackURI.length())) + "..." : "null",
                    rec.downloadPath != null ? rec.downloadPath.substring(0, Math.min(50, rec.downloadPath.length())) + "..." : "null");
            
            recordings.add(rec);
        }
        return recordings;
    }

    private static String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
    }

    // 获取下载 token（海康设备需要）
    private static String getDownloadToken(OkHttpClient client, String deviceIp, int port, Logger log) {
        try {
            String tokenUrl = String.format("http://%s:%d/ISAPI/Security/token?format=json", deviceIp, port);
            Request request = new Request.Builder()
                    .url(tokenUrl)
                    .get()
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    // 简单解析 JSON: {"Token":{"value":"xxx"}}
                    if (body.contains("\"value\"")) {
                        int start = body.indexOf("\"value\"") + 9;
                        int end = body.indexOf("\"", start);
                        if (end > start) {
                            String token = body.substring(start, end);
                            log.debug("[流式下载] 获取 token 成功: %s", token);
                            return token;
                        }
                    }
                }
                log.warn("[流式下载] 获取 token 失败，HTTP %d", response.code());
            }
        } catch (Exception e) {
            log.warn("[流式下载] 获取 token 异常: %s", e.getMessage());
        }
        return null;
    }

    // 下载文件（原始方法，保留兼容）
    private static void downloadFile(OkHttpClient client, String deviceIp, int port,
                                     String downloadPath, String saveFilePath) throws IOException {
        String url = downloadPath.startsWith("http") ? downloadPath :
                String.format("http://%s:%d%s", deviceIp, port, downloadPath);

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("下载失败: " + response.code());
            }

            try (InputStream is = response.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(saveFilePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    // 带进度的文件下载
    private static long downloadFileWithProgress(OkHttpClient client, String deviceIp, int port,
                                                  String downloadPath, String saveFilePath, 
                                                  DownloadTask task) throws IOException {
        Logger log = Logger.getLogger(ISAPIWebServer.class);
        String url = downloadPath.startsWith("http") ? downloadPath :
                String.format("http://%s:%d%s", deviceIp, port, downloadPath);

        log.debug("[文件下载] 开始下载: %s", url);
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        Call call = client.newCall(request);
        task.activeCall = call;
        touchTask(task);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("下载失败: HTTP " + response.code());
            }

            // 获取文件大小（如果服务器提供）
            long contentLength = response.body().contentLength();
            task.expectedBytes = contentLength;
            log.debug("[文件下载] Content-Length: %d bytes", contentLength);

            try (InputStream is = response.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(saveFilePath)) {
                byte[] buffer = new byte[32768]; // 32KB 缓冲区
                int bytesRead;
                long totalBytesRead = 0;
                long lastLogTime = System.currentTimeMillis();
                
                while ((bytesRead = is.read(buffer)) != -1) {
                    if (task.cancelRequested) {
                        throw new IOException("任务已取消");
                    }
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    task.totalBytes = totalBytesRead;
                    
                    // 每5秒记录一次进度日志
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime > 5000) {
                        double mb = totalBytesRead / 1024.0 / 1024.0;
                        if (contentLength > 0) {
                            double percent = (totalBytesRead * 100.0) / contentLength;
                            log.debug("[文件下载] 进度: %.1f%% (%.2f MB / %.2f MB)", 
                                    percent, mb, contentLength / 1024.0 / 1024.0);
                        } else {
                            log.debug("[文件下载] 已下载: %.2f MB", mb);
                        }
                        lastLogTime = now;
                    }
                }
                
                log.debug("[文件下载] 下载完成: %d bytes", totalBytesRead);
                return totalBytesRead;
            }
        } finally {
            if (task.activeCall == call) {
                task.activeCall = null;
                touchTask(task);
            }
        }
    }

    // 流式下载（尝试多种方法）
    private static long downloadStream(OkHttpClient client, String deviceIp, int port,
                                        RecordingInfo rec, String saveFilePath,
                                        DownloadTask task) throws IOException {
        Logger log = Logger.getLogger(ISAPIWebServer.class);
        
        // 检查 playbackURI
        String playbackURI = rec.playbackURI;
        if (playbackURI == null || playbackURI.isEmpty()) {
            String errMsg = "playbackURI 为空，无法进行流式下载";
            log.error("[流式下载] %s", errMsg);
            addTaskLog(task, "错误: " + errMsg);
            throw new IOException(errMsg);
        }
        
        log.info("========================================");
        log.info("[流式下载] 开始流式下载任务");
        log.info("[流式下载] 目标文件: %s", saveFilePath);
        log.info("[流式下载] playbackURI: %s", playbackURI);
        log.info("========================================");
        addTaskLog(task, "开始流式下载...");
        addTaskLog(task, String.format("playbackURI: %s", playbackURI.length() > 100 ? playbackURI.substring(0, 100) + "..." : playbackURI));
        
        // 为流式下载创建专用客户端（更长的超时时间）
        OkHttpClient streamClient = client.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        
        List<String> errors = new ArrayList<>();

        // ==================== 方法1: POST + XML Body (主路径) ====================
        log.info("[流式下载] 尝试方法1: POST + XML Body (主路径)");
        addTaskLog(task, "方法1: POST + XML Body (主路径)");
        try {
            String url = String.format("http://%s:%d/ISAPI/ContentMgmt/download", deviceIp, port);
            String xmlBody = buildDownloadXml(playbackURI);
            addTaskLog(task, "URL: " + url);

            RequestBody requestBody = RequestBody.create(MediaType.parse("application/xml; charset=utf-8"), xmlBody);
            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Accept", "*/*")
                    .addHeader("Content-Type", "application/xml")
                    .build();
            long result = tryStreamDownload(streamClient, request, saveFilePath, task, log, "方法1");
            if (result > 0) {
                addTaskLog(task, "方法1 成功! 下载 " + result + " 字节");
                return result;
            }
        } catch (Exception e) {
            String err = "方法1 失败: " + e.getMessage();
            log.warn("[流式下载] %s", err);
            addTaskLog(task, err);
            errors.add(err);
        }
        
        // ==================== 方法2: GET + XML Body + Token ====================
        log.info("[流式下载] 尝试方法2: GET + XML Body + Token (兼容回退)");
        addTaskLog(task, "方法2: GET + XML Body + Token (兼容回退)");
        try {
            String token = getDownloadToken(client, deviceIp, port, log);
            if (token != null) {
                String url = String.format("http://%s:%d/ISAPI/ContentMgmt/download?token=%s", deviceIp, port, token);
                String xmlBody = buildDownloadXml(playbackURI);
                
                log.debug("[流式下载] 方法2 URL: %s", url);
                log.debug("[流式下载] 方法2 XML Body: %s", xmlBody);
                addTaskLog(task, "URL: " + url);
                
                RequestBody requestBody = RequestBody.create(MediaType.parse("application/xml; charset=utf-8"), xmlBody);
                Request request = new Request.Builder()
                        .url(url)
                        .method("GET", requestBody)
                        .addHeader("Accept", "*/*")
                        .addHeader("Content-Type", "application/xml")
                        .build();
                
                long result = tryStreamDownload(streamClient, request, saveFilePath, task, log, "方法2");
                if (result > 0) {
                    log.info("[流式下载] 方法2 成功! 下载 %d 字节", result);
                    addTaskLog(task, "方法2 成功! 下载 " + result + " 字节");
                    return result;
                }
            } else {
                log.warn("[流式下载] 方法2 跳过: 无法获取 token");
                addTaskLog(task, "方法2 跳过: 无法获取 token");
            }
        } catch (Exception e) {
            String err = "方法2 失败: " + e.getMessage();
            log.warn("[流式下载] %s", err);
            addTaskLog(task, err);
            errors.add(err);
        }
        
        // ==================== 方法3: GET + XML Body (legacy fallback) ====================
        log.info("[流式下载] 尝试方法3: GET + XML Body (legacy fallback)");
        addTaskLog(task, "方法3: GET + XML Body (legacy fallback)");
        try {
            String url = String.format("http://%s:%d/ISAPI/ContentMgmt/download", deviceIp, port);
            String xmlBody = buildDownloadXml(playbackURI);
            
            log.debug("[流式下载] 方法3 URL: %s", url);
            addTaskLog(task, "URL: " + url);
            
            RequestBody requestBody = RequestBody.create(MediaType.parse("application/xml; charset=utf-8"), xmlBody);
            Request request = new Request.Builder()
                    .url(url)
                    .method("GET", requestBody)
                    .addHeader("Accept", "*/*")
                    .addHeader("Content-Type", "application/xml")
                    .build();
            
            long result = tryStreamDownload(streamClient, request, saveFilePath, task, log, "方法3");
            if (result > 0) {
                log.info("[流式下载] 方法3 成功! 下载 %d 字节", result);
                addTaskLog(task, "方法3 成功! 下载 " + result + " 字节");
                return result;
            }
        } catch (Exception e) {
            String err = "方法3 失败: " + e.getMessage();
            log.warn("[流式下载] %s", err);
            addTaskLog(task, err);
            errors.add(err);
        }
        
        if (METHOD5_ENABLED) {
            // ==================== 方法4: StreamingProxy 接口 ====================
            log.info("[流式下载] 尝试方法4: StreamingProxy 接口");
            addTaskLog(task, "方法4: StreamingProxy 接口");
            try {
                String playbackUrl = buildPlaybackUrl(playbackURI, deviceIp, port);
                if (playbackUrl != null) {
                    log.debug("[流式下载] 方法4 URL: %s", playbackUrl);
                    addTaskLog(task, "URL: " + playbackUrl);
                    
                    Request request = new Request.Builder()
                            .url(playbackUrl)
                            .get()
                            .addHeader("Accept", "*/*")
                            .build();
                    
                    long result = tryStreamDownload(streamClient, request, saveFilePath, task, log, "方法4");
                    if (result > 0) {
                        log.info("[流式下载] 方法4 成功! 下载 %d 字节", result);
                        addTaskLog(task, "方法4 成功! 下载 " + result + " 字节");
                        return result;
                    }
                } else {
                    log.warn("[流式下载] 方法4 跳过: 无法构建 playback URL");
                    addTaskLog(task, "方法4 跳过: 无法构建 playback URL");
                }
            } catch (Exception e) {
                String err = "方法4 失败: " + e.getMessage();
                log.warn("[流式下载] %s", err);
                addTaskLog(task, err);
                errors.add(err);
            }
        } else {
            addTaskLog(task, "方法4 已禁用(METHOD5_ENABLED=false)");
        }
        
        // ==================== 方法5: 直接访问 downloadPath 的 HTTP 变体 ====================
        if (rec.downloadPath != null && !rec.downloadPath.isEmpty()) {
            log.info("[流式下载] 尝试方法5: 直接访问录像文件路径");
            addTaskLog(task, "方法5: 直接访问录像文件路径");
            try {
                String url = rec.downloadPath.startsWith("http") ? rec.downloadPath :
                        String.format("http://%s:%d%s", deviceIp, port, rec.downloadPath);
                
                log.debug("[流式下载] 方法5 URL: %s", url);
                addTaskLog(task, "URL: " + (url.length() > 100 ? url.substring(0, 100) + "..." : url));
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("Accept", "*/*")
                        .build();
                
                long result = tryStreamDownload(streamClient, request, saveFilePath, task, log, "方法5");
                if (result > 0) {
                    log.info("[流式下载] 方法5 成功! 下载 %d 字节", result);
                    addTaskLog(task, "方法5 成功! 下载 " + result + " 字节");
                    return result;
                }
            } catch (Exception e) {
                String err = "方法5 失败: " + e.getMessage();
                log.warn("[流式下载] %s", err);
                addTaskLog(task, err);
                errors.add(err);
            }
        }
        
        // 所有方法都失败
        log.error("[流式下载] 所有方法都失败!");
        addTaskLog(task, "所有方法都失败!");
        StringBuilder errorMsg = new StringBuilder("流式下载失败，已尝试方法:\n");
        for (String err : errors) {
            errorMsg.append("  - ").append(err).append("\n");
        }
        throw new IOException(errorMsg.toString());
    }
    
    // 构建下载请求 XML（使用 CDATA 包裹 playbackURI，避免 & 等字符破坏 XML）
    private static String buildDownloadXml(String playbackURI) {
        return "<?xml version='1.0' encoding='UTF-8'?>" +
               "<downloadRequest>" +
               "<playbackURI><![CDATA[" + playbackURI + "]]></playbackURI>" +
               "</downloadRequest>";
    }
    
    // 从 playbackURI 构建 playback URL
    private static String buildPlaybackUrl(String playbackURI, String deviceIp, int port) {
        if (playbackURI == null) return null;
        
        // 尝试提取 track ID 和时间参数
        // rtsp://192.168.1.6/Streaming/tracks/101?starttime=20240101T000000Z&endtime=20240101T010000Z
        try {
            String trackId = null;
            String startTime = null;
            String endTime = null;
            
            // 提取 trackId
            int tracksIdx = playbackURI.indexOf("/tracks/");
            if (tracksIdx > 0) {
                int trackEnd = playbackURI.indexOf("?", tracksIdx);
                if (trackEnd < 0) trackEnd = playbackURI.length();
                trackId = playbackURI.substring(tracksIdx + 8, trackEnd);
            }
            
            // 提取时间参数
            int startIdx = playbackURI.indexOf("starttime=");
            if (startIdx > 0) {
                int endIdx = playbackURI.indexOf("&", startIdx);
                if (endIdx < 0) endIdx = playbackURI.length();
                startTime = playbackURI.substring(startIdx + 10, endIdx);
            }
            
            int endTimeIdx = playbackURI.indexOf("endtime=");
            if (endTimeIdx > 0) {
                int endIdx = playbackURI.indexOf("&", endTimeIdx);
                if (endIdx < 0) endIdx = playbackURI.length();
                endTime = playbackURI.substring(endTimeIdx + 8, endIdx);
            }
            
            if (trackId != null && startTime != null && endTime != null) {
                return String.format("http://%s:%d/ISAPI/ContentMgmt/StreamingProxy/channels/%s?starttime=%s&endtime=%s",
                        deviceIp, port, trackId, startTime, endTime);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        
        return null;
    }
    
    // ==================== ISAPI HTTP 精确时间段下载 ====================

    // ISAPI HTTP 方式下载精确时间段录像（单文件），尝试多种 playbackURI 变体
    private static long downloadViaISAPIHttp(OkHttpClient client, String deviceIp, int port,
                                             String channelId, String startTime, String endTime,
                                             String saveFilePath, DownloadTask task) throws IOException {
        Logger log = Logger.getLogger(ISAPIWebServer.class);
        log.info("========================================");
        log.info("[ISAPI HTTP] 开始 ISAPI HTTP 精确时间段下载");
        log.info("[ISAPI HTTP] 设备: %s:%d, 通道: %s", deviceIp, port, channelId);
        log.info("[ISAPI HTTP] 时间: %s ~ %s", startTime, endTime);
        log.info("[ISAPI HTTP] 保存: %s", saveFilePath);
        log.info("========================================");

        addTaskLog(task, String.format("ISAPI HTTP 精确时间: %s ~ %s", startTime, endTime));

        // 尝试多种 playbackURI 格式（含尾斜杠变体，与 RTSP URL 回退策略对齐）
        List<String> playbackURIs = Arrays.asList(
                String.format("rtsp://%s/Streaming/tracks/%s?starttime=%s&endtime=%s", deviceIp, channelId, startTime, endTime),
                String.format("rtsp://%s/Streaming/tracks/%s/?starttime=%s&endtime=%s", deviceIp, channelId, startTime, endTime),
                String.format("rtsp://%s/Streaming/channels/%s?starttime=%s&endtime=%s", deviceIp, channelId, startTime, endTime),
                String.format("rtsp://%s/ISAPI/Streaming/tracks/%s?starttime=%s&endtime=%s", deviceIp, channelId, startTime, endTime)
        );

        String downloadUrl = String.format("http://%s:%d/ISAPI/ContentMgmt/download", deviceIp, port);

        // 为 ISAPI HTTP 下载创建专用客户端（更长的超时时间）
        OkHttpClient streamClient = client.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        String tempFile = saveFilePath + ".isapi.tmp";
        IOException lastError = null;

        try {
            for (int idx = 0; idx < playbackURIs.size(); idx++) {
                if (task.cancelRequested) throw new IOException("任务已取消");
                String playbackURI = playbackURIs.get(idx);
                String label = String.format("变体 %d/%d", idx + 1, playbackURIs.size());

                addAttemptedUrl(task, downloadUrl);
                addTaskLog(task, String.format("%s: %s", label,
                        playbackURI.length() > 80 ? playbackURI.substring(0, 80) + "..." : playbackURI));

                String xmlBody = buildDownloadXml(playbackURI);
                RequestBody requestBody = RequestBody.create(
                        MediaType.parse("application/xml; charset=utf-8"), xmlBody);
                Request request = new Request.Builder()
                        .url(downloadUrl)
                        .post(requestBody)
                        .addHeader("Accept", "*/*")
                        .addHeader("Content-Type", "application/xml")
                        .build();

                try {
                    long bytes = executeHttpStreamDownload(streamClient, request, tempFile, task, log, label);
                    if (bytes > 0) {
                        addTaskLog(task, String.format("%s 下载成功: %d 字节", label, bytes));
                        // 下载成功，检查并转封装
                        finalizeDownloadFile(tempFile, saveFilePath, task, log);
                        return new File(saveFilePath).length();
                    }
                } catch (IOException e) {
                    lastError = e;
                    log.warn("[ISAPI HTTP] %s 失败: %s", label, e.getMessage());
                    addTaskLog(task, String.format("%s 失败: %s", label, e.getMessage()));
                    cleanupTmpFile(tempFile);
                }
            }
        } finally {
            // 确保异常/取消时 tmp 文件被清理
            cleanupTmpFile(tempFile);
        }

        if (lastError != null) throw lastError;
        throw new IOException("所有 ISAPI HTTP playbackURI 变体均失败");
    }

    // 执行 HTTP 流式下载到文件（带取消挂接和进度回报）
    private static long executeHttpStreamDownload(OkHttpClient client, Request request,
                                                   String saveFilePath, DownloadTask task,
                                                   Logger log, String label) throws IOException {
        log.debug("[ISAPI HTTP] %s 发送请求...", label);
        if (task.cancelRequested) throw new IOException("任务已取消");

        Call call = client.newCall(request);
        task.activeCall = call;
        touchTask(task);
        try (Response response = call.execute()) {
            int code = response.code();
            String contentType = response.header("Content-Type");
            long contentLength = response.body().contentLength();

            log.info("[ISAPI HTTP] %s 响应: HTTP %d, Content-Type: %s, Content-Length: %d",
                    label, code, contentType, contentLength);
            addTaskLog(task, String.format("%s 响应: HTTP %d, Content-Type: %s", label, code, contentType));

            if (!response.isSuccessful()) {
                String errorBody = "";
                try {
                    errorBody = response.body().string();
                    if (errorBody.length() > 500) errorBody = errorBody.substring(0, 500) + "...";
                } catch (Exception e) {
                    errorBody = "(无法读取)";
                }
                log.warn("[ISAPI HTTP] %s 失败响应体: %s", label, errorBody);
                throw new IOException("HTTP " + code + ": " + errorBody);
            }

            // 检查 Content-Type，拒绝 XML/HTML/JSON 等非视频响应
            if (contentType != null && (contentType.contains("xml") || contentType.contains("html") || contentType.contains("json"))) {
                String body = response.body().string();
                log.warn("[ISAPI HTTP] %s 返回非视频数据: %s", label,
                        body.length() > 200 ? body.substring(0, 200) + "..." : body);
                throw new IOException("返回非视频数据: " + contentType);
            }

            task.expectedBytes = contentLength > 0 ? contentLength : 0;

            // 开始下载到临时文件
            log.info("[ISAPI HTTP] %s 开始接收数据...", label);
            addTaskLog(task, String.format("%s 开始下载...", label));

            try (InputStream is = response.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(saveFilePath)) {

                byte[] buffer = new byte[65536]; // 64KB 缓冲区
                int bytesRead;
                long totalBytesRead = 0;
                long lastLogTime = System.currentTimeMillis();
                long dlStartTime = System.currentTimeMillis();
                int emptyReadCount = 0;
                final int MAX_EMPTY_READS = 100;

                while ((bytesRead = is.read(buffer)) != -1) {
                    if (task.cancelRequested) throw new IOException("任务已取消");
                    if (bytesRead == 0) {
                        emptyReadCount++;
                        if (emptyReadCount > MAX_EMPTY_READS) {
                            log.warn("[ISAPI HTTP] %s 连续空读 %d 次，结束", label, emptyReadCount);
                            break;
                        }
                        continue;
                    }
                    emptyReadCount = 0;

                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    task.totalBytes = totalBytesRead;

                    // 每 5 秒记录一次进度
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime > 5000) {
                        double mb = totalBytesRead / 1024.0 / 1024.0;
                        double elapsed = (now - dlStartTime) / 1000.0;
                        double speed = elapsed > 0 ? mb / elapsed : 0;
                        String progressMsg = String.format("已下载: %.2f MB (速度: %.2f MB/s)", mb, speed);
                        log.debug("[ISAPI HTTP] %s %s", label, progressMsg);
                        addTaskLog(task, progressMsg);
                        lastLogTime = now;
                    }
                }

                double totalMb = totalBytesRead / 1024.0 / 1024.0;
                double totalTime = (System.currentTimeMillis() - dlStartTime) / 1000.0;
                double avgSpeed = totalTime > 0 ? totalMb / totalTime : 0;
                log.info("[ISAPI HTTP] %s 下载完成: %.2f MB, 耗时: %.1f秒, 平均速度: %.2f MB/s",
                        label, totalMb, totalTime, avgSpeed);
                addTaskLog(task, String.format("下载完成: %.2f MB, 耗时: %.1f秒, 速度: %.2f MB/s",
                        totalMb, totalTime, avgSpeed));
                return totalBytesRead;
            }
        } finally {
            if (task.activeCall == call) {
                task.activeCall = null;
                touchTask(task);
            }
        }
    }

    // 检查并转封装下载文件为标准 MP4
    private static void finalizeDownloadFile(String tempFile, String finalFile,
                                              DownloadTask task, Logger log) throws IOException {
        if (isValidMp4(tempFile)) {
            log.info("[ISAPI HTTP] 文件已是标准 MP4，直接移动");
            addTaskLog(task, "文件已是标准 MP4");
            atomicMove(tempFile, finalFile);
        } else {
            log.info("[ISAPI HTTP] 非标准 MP4 封装，需要转封装");
            addTaskLog(task, "转封装中（本地操作，秒级完成）...");
            remuxToMp4(tempFile, finalFile, task);
            cleanupTmpFile(tempFile); // remux 成功后删除 tmp
        }
    }

    // 检查文件是否为有效 MP4（检查 ftyp box 标记）
    private static boolean isValidMp4(String filePath) {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            if (raf.length() < 12) return false;
            byte[] header = new byte[12];
            raf.readFully(header);
            // MP4: bytes 4-7 = "ftyp"
            return header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
        } catch (Exception e) {
            return false;
        }
    }

    // 本地转封装为 MP4（分级策略：纯 copy → 视频copy+音频aac → 视频copy+无音频）
    private static void remuxToMp4(String inputFile, String outputFile,
                                    DownloadTask task) throws IOException {
        String ffmpegPath = findFfmpeg();
        if (ffmpegPath == null) {
            // 无 ffmpeg，直接移动碰运气
            Logger.getLogger(ISAPIWebServer.class).warn("[转封装] 未找到 ffmpeg，直接移动文件");
            addTaskLog(task, "未找到 ffmpeg，跳过转封装");
            atomicMove(inputFile, outputFile);
            return;
        }

        String[][] strategies = {
                {"-c", "copy", "-movflags", "+faststart"},
                {"-c:v", "copy", "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart"},
                {"-c:v", "copy", "-an", "-movflags", "+faststart"},
        };

        Logger log = Logger.getLogger(ISAPIWebServer.class);
        for (int i = 0; i < strategies.length; i++) {
            if (task.cancelRequested) throw new IOException("任务已取消");
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(ffmpegPath);
                cmd.add("-i");
                cmd.add(inputFile);
                cmd.addAll(Arrays.asList(strategies[i]));
                cmd.add("-y");
                cmd.add(outputFile);

                addTaskLog(task, String.format("转封装策略 %d/%d: %s", i + 1, strategies.length,
                        String.join(" ", strategies[i])));
                log.info("[转封装] 策略 %d/%d: ffmpeg %s", i + 1, strategies.length,
                        String.join(" ", cmd.subList(1, cmd.size())));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                task.activeProcess = process;
                touchTask(task);

                // 消耗 stdout/stderr 防止阻塞
                Thread reader = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        while (br.readLine() != null) { /* 消耗输出 */ }
                    } catch (Exception ignored) {}
                }, "remux-output-reader");
                reader.setDaemon(true);
                reader.start();

                try {
                    boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                    reader.join(3000);
                    if (!finished) {
                        process.destroyForcibly();
                        throw new IOException("转封装超时");
                    }
                    if (task.cancelRequested) {
                        process.destroyForcibly();
                        throw new IOException("任务已取消");
                    }
                    if (process.exitValue() == 0) {
                        File out = new File(outputFile);
                        if (out.exists() && out.length() > 0) {
                            addTaskLog(task, String.format("转封装成功 (策略 %d)", i + 1));
                            log.info("[转封装] 策略 %d 成功，输出 %d 字节", i + 1, out.length());
                            return;
                        }
                    }
                    log.warn("[转封装] 策略 %d 退出码 %d，尝试下一个", i + 1, process.exitValue());
                } finally {
                    task.activeProcess = null;
                    touchTask(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("转封装被中断", e);
            } catch (IOException e) {
                if (task.cancelRequested || isCancellationException(e)) {
                    throw e;
                }
                addTaskLog(task, String.format("转封装策略 %d 失败: %s", i + 1, e.getMessage()));
                log.warn("[转封装] 策略 %d 异常: %s，尝试下一个", i + 1, e.getMessage());
            }
        }
        throw new IOException("所有转封装策略均失败");
    }

    // 原子移动文件（不支持原子操作时降级为普通移动）
    private static void atomicMove(String src, String dst) throws IOException {
        Path srcPath = Paths.get(src);
        Path dstPath = Paths.get(dst);
        try {
            Files.move(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // 清理临时文件（忽略异常）
    private static void cleanupTmpFile(String tmpPath) {
        if (tmpPath == null) return;
        try {
            Files.deleteIfExists(Paths.get(tmpPath));
        } catch (Exception ignored) {}
    }

    // ==================== 原有流式下载方法 ====================

    // 尝试流式下载（带详细日志）
    private static long tryStreamDownload(OkHttpClient client, Request request, 
                                          String saveFilePath, DownloadTask task,
                                          Logger log, String methodName) throws IOException {
        log.debug("[流式下载] %s 发送请求...", methodName);
        addAttemptedUrl(task, request.url().toString());
        if (task.cancelRequested) {
            throw new IOException("任务已取消");
        }

        Call call = client.newCall(request);
        task.activeCall = call;
        touchTask(task);
        try (Response response = call.execute()) {
            int code = response.code();
            String contentType = response.header("Content-Type");
            long contentLength = response.body().contentLength();
            String transferEncoding = response.header("Transfer-Encoding");
            
            log.info("[流式下载] %s 响应: HTTP %d", methodName, code);
            log.info("[流式下载] %s Content-Type: %s", methodName, contentType);
            log.info("[流式下载] %s Content-Length: %d", methodName, contentLength);
            log.info("[流式下载] %s Transfer-Encoding: %s", methodName, transferEncoding);
            
            addTaskLog(task, String.format("%s 响应: HTTP %d, Content-Type: %s", methodName, code, contentType));
            
            if (!response.isSuccessful()) {
                // 尝试读取错误响应体
                String errorBody = "";
                try {
                    errorBody = response.body().string();
                    if (errorBody.length() > 500) {
                        errorBody = errorBody.substring(0, 500) + "...";
                    }
                } catch (Exception e) {
                    errorBody = "(无法读取)";
                }
                
                log.warn("[流式下载] %s 失败响应体: %s", methodName, errorBody);
                addTaskLog(task, String.format("%s 错误: %s", methodName, errorBody.length() > 100 ? errorBody.substring(0, 100) + "..." : errorBody));
                
                throw new IOException("HTTP " + code + ": " + errorBody);
            }
            
            // 检查 Content-Type，确保是视频数据
            if (contentType != null && (contentType.contains("xml") || contentType.contains("html") || contentType.contains("json"))) {
                String body = response.body().string();
                log.warn("[流式下载] %s 返回非视频数据: %s", methodName, body.length() > 200 ? body.substring(0, 200) + "..." : body);
                addTaskLog(task, String.format("%s 返回非视频数据", methodName));
                throw new IOException("返回非视频数据: " + contentType);
            }
            
            task.expectedBytes = contentLength > 0 ? contentLength : 0;
            
            // 开始下载
            log.info("[流式下载] %s 开始接收数据...", methodName);
            addTaskLog(task, String.format("%s 开始下载...", methodName));
            
            try (InputStream is = response.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(saveFilePath)) {
                 
                byte[] buffer = new byte[65536]; // 64KB 缓冲区
                int bytesRead;
                long totalBytesRead = 0;
                long lastLogTime = System.currentTimeMillis();
                long startTime = System.currentTimeMillis();
                int emptyReadCount = 0;
                final int MAX_EMPTY_READS = 100;
                
                while ((bytesRead = is.read(buffer)) != -1) {
                    if (task.cancelRequested) {
                        throw new IOException("任务已取消");
                    }
                    if (bytesRead == 0) {
                        emptyReadCount++;
                        if (emptyReadCount > MAX_EMPTY_READS) {
                            log.warn("[流式下载] %s 连续空读 %d 次，结束", methodName, emptyReadCount);
                            break;
                        }
                        continue;
                    }
                    emptyReadCount = 0;
                    
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    task.totalBytes = totalBytesRead;
                    
                    // 每5秒记录一次进度
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime > 5000) {
                        double mb = totalBytesRead / 1024.0 / 1024.0;
                        double elapsed = (now - startTime) / 1000.0;
                        double speed = elapsed > 0 ? mb / elapsed : 0;
                        
                        String progressMsg = String.format("已下载: %.2f MB (速度: %.2f MB/s)", mb, speed);
                        log.debug("[流式下载] %s %s", methodName, progressMsg);
                        addTaskLog(task, progressMsg);
                        
                        lastLogTime = now;
                    }
                }
                
                // 下载完成
                double totalMb = totalBytesRead / 1024.0 / 1024.0;
                double totalTime = (System.currentTimeMillis() - startTime) / 1000.0;
                double avgSpeed = totalTime > 0 ? totalMb / totalTime : 0;
                
                log.info("[流式下载] %s 下载完成: %.2f MB, 耗时: %.1f秒, 平均速度: %.2f MB/s", 
                        methodName, totalMb, totalTime, avgSpeed);
                addTaskLog(task, String.format("下载完成: %.2f MB, 耗时: %.1f秒", totalMb, totalTime));
                
                return totalBytesRead;
            }
        } finally {
            if (task.activeCall == call) {
                task.activeCall = null;
                touchTask(task);
            }
        }
    }

    private static String generateFileName(RecordingInfo rec, int index) {
        String timeStr = rec.startTime.substring(0, 19).replace(":", "-").replace("T", "_");
        return String.format("ch%s_%s_%d.mp4", rec.trackId, timeStr, index);
    }

    static class TimeRange {
        String searchStart;
        String searchEnd;
        String rtspStart;
        String rtspEnd;
        String timeBasis;
        String deviceTimeZone;
    }

    static class DeviceTimeInfo {
        ZoneId zoneId;
        String rawTimeZone;
        String rawLocalTime;
    }

    private static TimeRange resolveTimeRange(OkHttpClient client, String deviceIp, int port,
                                              String startTime, String endTime,
                                              Integer clientTimezoneOffsetMinutes) throws Exception {
        if (startTime == null || endTime == null || startTime.trim().isEmpty() || endTime.trim().isEmpty()) {
            throw new IllegalArgumentException("开始时间和结束时间不能为空");
        }

        LocalDateTime startLocal;
        LocalDateTime endLocal;
        try {
            startLocal = LocalDateTime.parse(startTime, INPUT_LOCAL_DT);
            endLocal = LocalDateTime.parse(endTime, INPUT_LOCAL_DT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("时间格式错误，应为 yyyy-MM-dd'T'HH:mm");
        }

        ZoneId inputZone = ZoneId.systemDefault();
        if (clientTimezoneOffsetMinutes != null) {
            inputZone = ZoneOffset.ofTotalSeconds(clientTimezoneOffsetMinutes * 60);
        }

        Instant startInstant = startLocal.atZone(inputZone).toInstant();
        Instant endInstant = endLocal.atZone(inputZone).toInstant();
        if (!endInstant.isAfter(startInstant)) {
            throw new IllegalArgumentException("结束时间必须大于开始时间");
        }
        long rangeMinutes = Duration.between(startInstant, endInstant).toMinutes();
        if (rangeMinutes > MAX_DOWNLOAD_RANGE_MINUTES) {
            throw new IllegalArgumentException("时间范围过大，最大允许 " + MAX_DOWNLOAD_RANGE_MINUTES + " 分钟");
        }

        DeviceTimeInfo deviceTimeInfo = fetchDeviceTimeInfo(client, deviceIp, port);
        ZoneId targetZone = null;
        String basis;
        String deviceTzText = "";
        if (deviceTimeInfo != null && deviceTimeInfo.zoneId != null) {
            targetZone = deviceTimeInfo.zoneId;
            basis = "device";
            deviceTzText = valueOrEmpty(deviceTimeInfo.rawTimeZone);
        } else if (clientTimezoneOffsetMinutes != null) {
            targetZone = ZoneOffset.ofTotalSeconds(clientTimezoneOffsetMinutes * 60);
            basis = "browser";
        } else {
            targetZone = ZoneId.systemDefault();
            basis = "server";
        }

        TimeRange range = new TimeRange();
        range.timeBasis = basis;
        range.deviceTimeZone = deviceTzText;
        if ("UTC_Z".equals(TIME_MODE)) {
            range.searchStart = SEARCH_TIME_FORMAT.format(startInstant.atZone(ZoneOffset.UTC));
            range.searchEnd = SEARCH_TIME_FORMAT.format(endInstant.atZone(ZoneOffset.UTC));
            range.rtspStart = RTSP_TIME_FORMAT.format(startInstant.atZone(ZoneOffset.UTC));
            range.rtspEnd = RTSP_TIME_FORMAT.format(endInstant.atZone(ZoneOffset.UTC));
        } else {
            LocalDateTime normalizedStart = LocalDateTime.ofInstant(startInstant, targetZone);
            LocalDateTime normalizedEnd = LocalDateTime.ofInstant(endInstant, targetZone);
            range.searchStart = SEARCH_TIME_FORMAT.format(normalizedStart);
            range.searchEnd = SEARCH_TIME_FORMAT.format(normalizedEnd);
            range.rtspStart = RTSP_TIME_FORMAT.format(normalizedStart);
            range.rtspEnd = RTSP_TIME_FORMAT.format(normalizedEnd);
        }
        return range;
    }

    private static DeviceTimeInfo fetchDeviceTimeInfo(OkHttpClient client, String deviceIp, int port) {
        try {
            Request request = new Request.Builder()
                    .url(String.format("http://%s:%d/ISAPI/System/time", deviceIp, port))
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                String xml = response.body().string();
                Document doc = parseXmlDocument(xml);
                DeviceTimeInfo info = new DeviceTimeInfo();
                info.rawLocalTime = getElementText(doc, "localTime");
                info.rawTimeZone = getElementText(doc, "timeZone");
                info.zoneId = parseDeviceZone(info.rawTimeZone, info.rawLocalTime);
                return info;
            }
        } catch (Exception e) {
            log.warn("[时间解析] 获取设备时区失败: %s", e.getMessage());
            return null;
        }
    }

    private static ZoneId parseDeviceZone(String rawTimeZone, String rawLocalTime) {
        if (rawLocalTime != null && rawLocalTime.matches(".*[+-]\\d{2}:\\d{2}$")) {
            try {
                return OffsetDateTime.parse(rawLocalTime).getOffset();
            } catch (Exception ignored) {
                // fallback
            }
        }
        if (rawTimeZone == null || rawTimeZone.trim().isEmpty()) {
            return null;
        }
        String tz = rawTimeZone.trim();
        Matcher m = Pattern.compile("([+-])(\\d{1,2})(?::?(\\d{2}))?").matcher(tz);
        if (!m.find()) {
            return null;
        }
        int hour = Integer.parseInt(m.group(2));
        int minute = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        int sign = "-".equals(m.group(1)) ? -1 : 1;

        // 兼容海康常见 "CST-8:00:00"（POSIX 语义，符号与常规相反）
        boolean posixStyle = tz.matches("^[A-Za-z]{3,}[-+]\\d.*") && !tz.startsWith("UTC") && !tz.startsWith("GMT");
        if (posixStyle) {
            sign = -sign;
        }
        int totalSeconds = sign * (hour * 3600 + minute * 60);
        return ZoneOffset.ofTotalSeconds(totalSeconds);
    }

    private static Document parseXmlDocument(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static String getElementText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
    }

    private static long runFfmpegCapture(String ffmpegPath, String rtspUrl, String saveFilePath, DownloadTask task) throws IOException {
        Logger log = Logger.getLogger(ISAPIWebServer.class);

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-rtsp_transport");
        cmd.add("tcp");
        cmd.add("-i");
        cmd.add(rtspUrl);
        cmd.add("-c:v");
        cmd.add("copy");   // 视频直接复制，不重编码
        cmd.add("-c:a");
        cmd.add("aac");    // 音频转码为 AAC（兼容 MP4 容器，解决 pcm_alaw 不被 MP4 支持的问题）
        cmd.add("-y");
        cmd.add(saveFilePath);

        addTaskLog(task, "执行 ffmpeg: " + maskRtspUrl(rtspUrl));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        task.activeProcess = process;
        touchTask(task);

        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                long lastLogTime = 0L;
                while ((line = reader.readLine()) != null) {
                    log.debug("[ffmpeg] %s", line);
                    if (line.contains("time=")) {
                        long now = System.currentTimeMillis();
                        if (now - lastLogTime >= 3000) {
                            int timeIdx = line.indexOf("time=");
                            if (timeIdx >= 0) {
                                String timeStr = line.substring(timeIdx + 5, Math.min(line.length(), timeIdx + 16));
                                addTaskLog(task, "进度: " + timeStr);
                            }
                            lastLogTime = now;
                        }
                    }
                    if (task.cancelRequested) {
                        process.destroyForcibly();
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("[ffmpeg] 读取输出失败: %s", e.getMessage());
            }
        }, "ffmpeg-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        try {
            boolean finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            outputReader.join(5000);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("ffmpeg 超时(" + FFMPEG_TIMEOUT_SECONDS + "s)");
            }
            if (task.cancelRequested) {
                throw new IOException("任务已取消");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("ffmpeg 执行失败，退出码: " + exitCode);
            }
            File outputFile = new File(saveFilePath);
            if (!outputFile.exists() || outputFile.length() <= 0) {
                throw new IOException("ffmpeg 输出文件为空或不存在");
            }
            return outputFile.length();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg 执行被中断", e);
        } finally {
            task.activeProcess = null;
            touchTask(task);
        }
    }

    private static String encodeUserInfo(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                String hex = Integer.toHexString(c).toUpperCase(Locale.ROOT);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
        }
        return sb.toString();
    }

    private static String maskRtspUrl(String rtspUrl) {
        if (rtspUrl == null) return "";
        return rtspUrl.replaceFirst("://([^:/]+):([^@]+)@", "://$1:***@");
    }

    private static void addTaskLog(DownloadTask task, String message) {
        if (task == null || message == null) return;
        synchronized (task) {
            task.logs.add(message);
            while (task.logs.size() > MAX_TASK_LOG_LINES) {
                task.logs.remove(0);
            }
            task.updatedAt = System.currentTimeMillis();
        }
    }

    private static void addAttemptedUrl(DownloadTask task, String url) {
        if (task == null || url == null || url.trim().isEmpty()) return;
        synchronized (task) {
            if (task.attemptedUrls.size() >= 50) {
                task.attemptedUrls.remove(0);
            }
            task.attemptedUrls.add(url);
            task.updatedAt = System.currentTimeMillis();
        }
    }

    private static void touchTask(DownloadTask task) {
        if (task != null) {
            task.updatedAt = System.currentTimeMillis();
        }
    }

    private static void cancelTask(DownloadTask task, String reason) {
        if (task == null) return;
        if (isTerminalStatus(task.status)) {
            return;
        }
        task.cancelRequested = true;
        task.status = "cancelled";
        task.message = reason;
        task.finishedAt = System.currentTimeMillis();
        touchTask(task);
        addTaskLog(task, "任务取消: " + reason);
        Process p = task.activeProcess;
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Exception ignored) {
                // ignore
            }
        }
        Call call = task.activeCall;
        if (call != null) {
            try {
                call.cancel();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static void cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        long ttlMs = TimeUnit.MINUTES.toMillis(TASK_TTL_MINUTES);
        List<String> removeKeys = new ArrayList<>();
        for (Map.Entry<String, DownloadTask> entry : downloadTasks.entrySet()) {
            DownloadTask task = entry.getValue();
            if (task == null) {
                removeKeys.add(entry.getKey());
                continue;
            }
            boolean terminal = isTerminalStatus(task.status);
            long base = task.finishedAt > 0 ? task.finishedAt : task.updatedAt;
            if (terminal && now - base > ttlMs) {
                removeKeys.add(entry.getKey());
            }
        }
        for (String key : removeKeys) {
            downloadTasks.remove(key);
        }
    }

    private static Map<String, Object> buildTaskSnapshot(DownloadTask task) {
        Map<String, Object> json = new LinkedHashMap<>();
        synchronized (task) {
            json.put("taskId", task.taskId);
            json.put("status", task.status);
            json.put("downloadMode", task.downloadMode);
            json.put("total", task.total);
            json.put("current", task.current);
            json.put("success", task.success);
            json.put("failed", task.failed);
            json.put("currentFile", valueOrEmpty(task.currentFile));
            json.put("message", valueOrEmpty(task.message));
            json.put("totalBytes", task.totalBytes);
            json.put("expectedBytes", task.expectedBytes);
            json.put("totalDownloadedBytes", task.totalDownloadedBytes);
            json.put("files", new ArrayList<>(task.downloadedFiles));
            int logStart = Math.max(0, task.logs.size() - 10);
            json.put("logs", new ArrayList<>(task.logs.subList(logStart, task.logs.size())));
            json.put("timeMode", task.timeMode);
            json.put("timeBasis", task.timeBasis);
            json.put("deviceTimeZone", task.deviceTimeZone);
            json.put("normalizedStart", task.normalizedStart);
            json.put("normalizedEnd", task.normalizedEnd);
            json.put("attemptedUrls", new ArrayList<>(task.attemptedUrls));
            json.put("cancelRequested", task.cancelRequested);
            json.put("requestedMethod", valueOrEmpty(task.requestedMethod));
            json.put("effectiveMethod", valueOrEmpty(task.effectiveMethod));
            json.put("fallbackUsed", task.fallbackUsed);
        }
        return json;
    }

    private static boolean isTimeoutFailure(Exception e) {
        if (e == null) return false;
        String message = e.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("timeout") || message.contains("超时");
    }

    private static boolean isCancellationException(Throwable e) {
        if (e == null) return false;
        String message = e.getMessage();
        if (message != null) {
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("cancelled") || lower.contains("canceled") || message.contains("取消")) {
                return true;
            }
        }
        Throwable cause = e.getCause();
        return cause != null && cause != e && isCancellationException(cause);
    }

    private static boolean isTerminalStatus(String status) {
        if (status == null) return false;
        return "completed".equals(status) || "cancelled".equals(status) || isFailedStatus(status);
    }

    private static boolean isFailedStatus(String status) {
        return status != null && status.startsWith("failed");
    }

    private static Map<String, Object> errorResponse(String code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", false);
        map.put("code", code);
        map.put("message", valueOrEmpty(message));
        map.put("error", valueOrEmpty(message));
        return map;
    }

    private static Map<String, Object> successResponse(String message, String taskId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", true);
        map.put("message", valueOrEmpty(message));
        map.put("taskId", valueOrEmpty(taskId));
        return map;
    }

    private static Integer parseNullableInt(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.trim().isEmpty()) {
            return params;
        }
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                try {
                    params.put(URLDecoder.decode(pair[0], "UTF-8"), URLDecoder.decode(pair[1], "UTF-8"));
                } catch (Exception ignored) {
                    // ignore bad field
                }
            }
        }
        return params;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static int getEnvInt(String key, int defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean getEnvBool(String key, boolean defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        return "1".equals(raw.trim()) || "true".equalsIgnoreCase(raw.trim()) || "yes".equalsIgnoreCase(raw.trim());
    }

    private static Map<String, String> parseFormData(HttpExchange exchange) throws IOException {
        Map<String, String> params = new HashMap<>();
        String body = readInputStream(exchange.getRequestBody());
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(URLDecoder.decode(kv[0], "UTF-8"),
                        URLDecoder.decode(kv[1], "UTF-8"));
            }
        }
        return params;
    }

    // JDK 8 兼容的读取 InputStream 方法
    private static String readInputStream(InputStream is) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString("UTF-8");
    }

    private static void sendJson(HttpExchange exchange, int code, Object data) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // HTML页面 - 从文件读取
    private static String getHtmlPage() {
        try {
            return new String(Files.readAllBytes(Paths.get("index.html")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<!DOCTYPE html><html><body><h1>Error: index.html not found</h1><p>Please make sure index.html is in the same directory as the server.</p></body></html>";
        }
    }

    // ==================== 新增处理器 ====================

    // 获取设备信息
    static class DeviceInfoHandler implements HttpHandler {
        private final Logger log = Logger.getLogger(DeviceInfoHandler.class);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorResponse("METHOD_NOT_ALLOWED", "Method not allowed"));
                return;
            }

            try {
                Map<String, String> params = parseFormData(exchange);
                String deviceIp = params.get("deviceIp");
                int port = Integer.parseInt(params.getOrDefault("port", "80"));
                String username = params.get("username");
                String password = params.get("password");

                log.info("[设备信息] 连接 %s:%d", deviceIp, port);
                ISAPIClient client = new ISAPIClient(deviceIp, port, username, password);
                ISAPIClient.DeviceInfo info = client.getDeviceInfo();

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("data", info);
                sendJson(exchange, 200, response);

            } catch (Exception e) {
                log.error("获取设备信息失败: " + e.getMessage(), e);
                sendJson(exchange, 500, errorResponse("DEVICE_INFO_FAILED", e.getMessage()));
            }
        }
    }

    // 获取通道列表
    static class ChannelsHandler implements HttpHandler {
        private final Logger log = Logger.getLogger(ChannelsHandler.class);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorResponse("METHOD_NOT_ALLOWED", "Method not allowed"));
                return;
            }

            try {
                Map<String, String> params = parseFormData(exchange);
                String deviceIp = params.get("deviceIp");
                int port = Integer.parseInt(params.getOrDefault("port", "80"));
                String username = params.get("username");
                String password = params.get("password");

                log.info("[通道列表] 连接 %s:%d", deviceIp, port);
                ISAPIClient client = new ISAPIClient(deviceIp, port, username, password);
                java.util.List<ISAPIClient.ChannelInfo> channels = client.getChannels();

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("count", channels.size());
                response.put("channels", channels);
                sendJson(exchange, 200, response);

            } catch (Exception e) {
                log.error("获取通道列表失败: " + e.getMessage(), e);
                sendJson(exchange, 500, errorResponse("CHANNELS_FAILED", e.getMessage()));
            }
        }
    }

    // 获取 RTSP 地址
    static class RtspUrlHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorResponse("METHOD_NOT_ALLOWED", "Method not allowed"));
                return;
            }

            try {
                Map<String, String> params = parseFormData(exchange);
                String deviceIp = params.get("deviceIp");
                int port = Integer.parseInt(params.getOrDefault("port", "80"));
                String username = params.get("username");
                String password = params.get("password");
                int channelId = Integer.parseInt(params.getOrDefault("channelId", "1"));
                int streamType = Integer.parseInt(params.getOrDefault("streamType", "1"));

                ISAPIClient client = new ISAPIClient(deviceIp, port, username, password);
                String rtspUrl = client.getRtspUrl(channelId, streamType);
                String httpFlvUrl = client.getHttpFlvUrl(channelId, streamType);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("rtspUrl", valueOrEmpty(rtspUrl));
                response.put("httpFlvUrl", valueOrEmpty(httpFlvUrl));
                sendJson(exchange, 200, response);

            } catch (Exception e) {
                sendJson(exchange, 500, errorResponse("RTSP_URL_FAILED", e.getMessage()));
            }
        }
    }

    // 获取存储状态
    static class StorageHandler implements HttpHandler {
        private final Logger log = Logger.getLogger(StorageHandler.class);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorResponse("METHOD_NOT_ALLOWED", "Method not allowed"));
                return;
            }

            try {
                Map<String, String> params = parseFormData(exchange);
                String deviceIp = params.get("deviceIp");
                int port = Integer.parseInt(params.getOrDefault("port", "80"));
                String username = params.get("username");
                String password = params.get("password");

                log.info("[存储状态] 连接 %s:%d", deviceIp, port);
                ISAPIClient client = new ISAPIClient(deviceIp, port, username, password);
                java.util.List<ISAPIClient.StorageInfo> storages = client.getStorageStatus();

                // 同时获取设备时间
                String deviceTime = "";
                try {
                    deviceTime = client.getDeviceTime();
                } catch (Exception e) {
                    // ignore
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("storages", storages);
                response.put("deviceTime", valueOrEmpty(deviceTime));
                sendJson(exchange, 200, response);

            } catch (Exception e) {
                log.error("获取存储状态失败: " + e.getMessage(), e);
                sendJson(exchange, 500, errorResponse("STORAGE_STATUS_FAILED", e.getMessage()));
            }
        }
    }

    // PTZ 控制
    static class PtzHandler implements HttpHandler {
        private final Logger log = Logger.getLogger(PtzHandler.class);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorResponse("METHOD_NOT_ALLOWED", "Method not allowed"));
                return;
            }

            try {
                Map<String, String> params = parseFormData(exchange);
                String deviceIp = params.get("deviceIp");
                int port = Integer.parseInt(params.getOrDefault("port", "80"));
                String username = params.get("username");
                String password = params.get("password");
                int channelId = Integer.parseInt(params.getOrDefault("channelId", "1"));
                String action = params.getOrDefault("action", "stop");
                int speed = Integer.parseInt(params.getOrDefault("speed", "4"));

                log.info("[PTZ] 通道=%d, 动作=%s, 速度=%d", channelId, action, speed);
                ISAPIClient client = new ISAPIClient(deviceIp, port, username, password);

                if (action.startsWith("preset_")) {
                    // 预置点调用
                    int presetId = Integer.parseInt(action.substring(7));
                    client.gotoPreset(channelId, presetId);
                } else {
                    client.ptzControl(channelId, action, speed);
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                sendJson(exchange, 200, response);

            } catch (Exception e) {
                log.error("PTZ 控制失败: " + e.getMessage(), e);
                sendJson(exchange, 500, errorResponse("PTZ_FAILED", e.getMessage()));
            }
        }
    }
}
