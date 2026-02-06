package com.comp.testISAPI;

import okhttp3.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class ISAPIQueryRecMain {

    private static final Logger log = Logger.getLogger(ISAPIQueryRecMain.class);

    // 录像信息类
    public static class RecordingInfo {
        public String trackId;
        public String startTime;
        public String endTime;
        public String eventType;
        public String downloadPath;

        @Override
        public String toString() {
            return String.format("通道:%s | 时间:%s 至 %s | 类型:%s",
                    trackId, startTime.substring(0, 19), endTime.substring(0, 19), eventType);
        }
    }

    public static void main(String[] args) {
        // ==================== 配置参数 ====================
        String deviceIp = "192.168.7.99";
        int port = 80;
        String username = "admin";
        String password = "higer12345";
        String channelId = "101"; // 1 = 101

        // 下载保存目录
        String downloadDir = "./recordings";

        // ==================== 时间段配置 ====================
        // 方式1: 指定具体的开始时间和结束时间（格式: yyyy-MM-dd HH:mm:ss）
        String startTimeStr = "2026-01-29 10:00:00";  // 修改为你需要的开始时间
        String endTimeStr = "2026-01-29 11:00:00";    // 修改为你需要的结束时间（建议不超过1小时）

        // 方式2: 从当前时间往前推N分钟（如果使用方式1，注释掉下面两行）
        // int minutesAgo = 60; // 往前推60分钟
        // startTimeStr = null; endTimeStr = null; // 设为null使用方式2

        // ===================================================

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        SimpleDateFormat inputSdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getDefault());
        inputSdf.setTimeZone(TimeZone.getDefault());

        String startTime;
        String endTime;

        try {
            if (startTimeStr != null && endTimeStr != null) {
                // 方式1: 使用指定的时间段
                Date startDate = inputSdf.parse(startTimeStr);
                Date endDate = inputSdf.parse(endTimeStr);

                // 检查时间段是否超过1小时
                long durationMs = endDate.getTime() - startDate.getTime();
                if (durationMs > 3600 * 1000) {
                    log.warn("时间段超过1小时 (%d 分钟)，建议缩短时间范围", durationMs / 60000);
                }
                if (durationMs <= 0) {
                    log.error("结束时间必须大于开始时间");
                    return;
                }

                startTime = sdf.format(startDate);
                endTime = sdf.format(endDate);
            } else {
                // 方式2: 从当前时间往前推
                int minutesAgo = 60;
                endTime = sdf.format(new Date());
                startTime = sdf.format(new Date(System.currentTimeMillis() - minutesAgo * 60 * 1000));
            }
        } catch (Exception e) {
            log.error("时间格式解析错误: " + e.getMessage(), e);
            return;
        }

        log.info("========================================");
        log.info("查询时间段: %s 至 %s", startTime, endTime);
        log.info("========================================");

        // 创建下载目录
        File dir = new File(downloadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        log.debug("下载目录: %s", dir.getAbsolutePath());

        // 使用较长的超时时间用于下载
        log.debug("创建 HTTP 客户端，读取超时: 600秒");
        OkHttpClient client = createDigestAuthClient(username, password, 30, 600); // 10分钟读取超时

        String response = null;
        try {
            log.info("开始搜索录像...");
            response = searchRecordings(client, deviceIp, port, channelId, startTime, endTime);
            log.debug("搜索请求完成");
        } catch (IOException e) {
            log.error("搜索录像失败: " + e.getMessage(), e);
            response = null;
        }

        try {
            if (null != response) {
                List<RecordingInfo> recordings = parseRecordingResponse(response);
                log.info("找到 %d 条录像记录", recordings.size());

                // 下载该时间段内的所有录像
                if (!recordings.isEmpty()) {
                    log.info("========================================");
                    log.info("开始下载所有录像，共 %d 个文件...", recordings.size());
                    log.info("========================================");

                    int successCount = 0;
                    int failCount = 0;

                    for (int i = 0; i < recordings.size(); i++) {
                        RecordingInfo rec = recordings.get(i);
                        String fileName = generateFileName(rec, i);
                        String savePath = downloadDir + "/" + fileName;

                        log.info("----------------------------------------");
                        log.info("下载第 %d/%d 个录像: %s", i + 1, recordings.size(), fileName);
                        log.debug("录像信息: %s", rec);
                        log.debug("下载路径: %s", rec.downloadPath);

                        try {
                            long startMs = System.currentTimeMillis();
                            downloadRecording(client, deviceIp, port, rec.downloadPath, savePath);
                            long elapsed = System.currentTimeMillis() - startMs;
                            
                            File file = new File(savePath);
                            double sizeMB = file.length() / 1024.0 / 1024.0;
                            log.info("下载成功: %s (%.2f MB, 耗时 %d ms)", fileName, sizeMB, elapsed);
                            successCount++;
                        } catch (IOException e) {
                            log.error("下载失败: %s - %s", fileName, e.getMessage());
                            failCount++;
                        }
                    }

                    log.info("========================================");
                    log.info("下载完成！成功: %d 个，失败: %d 个", successCount, failCount);
                    log.info("保存目录: %s", new File(downloadDir).getAbsolutePath());
                    log.info("========================================");
                } else {
                    log.warn("该时间段内没有找到录像");
                }
            }

        } catch (Exception e) {
            log.error("处理录像失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成下载文件名
     */
    private static String generateFileName(RecordingInfo rec, int index) {
        String timeStr = rec.startTime.substring(0, 19)
                .replace(":", "-")
                .replace("T", "_");
        return String.format("ch%s_%s_%d.mp4", rec.trackId, timeStr, index);
    }

    /**
     * 创建带Digest认证的HTTP客户端
     */
    private static OkHttpClient createDigestAuthClient(String username, String password,
                                                        int connectTimeoutSec, int readTimeoutSec) {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .authenticator(new DigestAuthenticator(username, password))
                .build();
    }

    // 保持向后兼容
    private static OkHttpClient createDigestAuthClient(String username, String password) {
        return createDigestAuthClient(username, password, 10, 30);
    }

    /**
     * 下载录像文件
     */
    private static void downloadRecording(OkHttpClient client, String deviceIp, int port,
                                          String downloadPath, String saveFilePath) throws IOException {
        // 构建完整URL
        String url;
        if (downloadPath.startsWith("http")) {
            url = downloadPath;
        } else {
            url = String.format("http://%s:%d%s", deviceIp, port, downloadPath);
        }

        log.debug("下载URL: %s", url);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("下载失败: " + response.code() + " - " + response.message());
            }

            // 获取文件大小（如果服务器提供）
            long contentLength = response.body().contentLength();
            String sizeInfo = contentLength > 0 ?
                    String.format("%.2f MB", contentLength / 1024.0 / 1024.0) : "未知大小";
            log.debug("文件大小: %s", sizeInfo);

            // 保存到文件
            try (InputStream inputStream = response.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(saveFilePath)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                long lastLogTime = System.currentTimeMillis();

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;

                    // 每5秒记录一次进度到日志
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime >= 5000) {
                        if (contentLength > 0) {
                            double percent = (totalBytes * 100.0) / contentLength;
                            log.debug("下载进度: %.1f%% (%.2f / %.2f MB)",
                                    percent, totalBytes / 1024.0 / 1024.0, contentLength / 1024.0 / 1024.0);
                        } else {
                            log.debug("已下载: %.2f MB", totalBytes / 1024.0 / 1024.0);
                        }
                        lastLogTime = now;
                    }
                }

                log.debug("文件保存完成: %s (%.2f MB)", saveFilePath, totalBytes / 1024.0 / 1024.0);
            }
        }
    }



    private static String searchRecordings(OkHttpClient client, String ip, int port,
                                           String channelId, String start, String end) throws IOException {
        // searchID
        String searchId = "T-" + System.currentTimeMillis();
        log.debug("搜索ID: %s", searchId);

        // xml
        String xmlBody = String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<CMSearchDescription>\n" +
                        "  <searchID>%s</searchID>\n" +
                        "  <trackList>\n" +
                        "    <trackID>%s</trackID>\n" +
                        "  </trackList>\n" +
                        "  <timeSpanList>\n" +
                        "    <timeSpan>\n" +
                        "      <startTime>%s</startTime>\n" +
                        "      <endTime>%s</endTime>\n" +
                        "    </timeSpan>\n" +
                        "  </timeSpanList>\n" +
                        "  <contentTypeList>\n" +
                        "    <contentType>video</contentType>\n" +
                        "  </contentTypeList>\n" +
                        "  <maxResults>50</maxResults>\n" +
                        "</CMSearchDescription>",
                searchId, channelId, start, end
        );

        String url = String.format("http://%s:%d/ISAPI/ContentMgmt/search", ip, port);
        log.debug("搜索请求URL: %s", url);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create( MediaType.parse("application/xml; charset=utf-8"),xmlBody))
                .build();

        try (Response response = client.newCall(request).execute()) {
            log.debug("搜索响应状态: %d", response.code());
            if (!response.isSuccessful()) {
                String errBody = response.body().string();
                log.error("搜索请求失败: %d - %s", response.code(), errBody);
                throw new IOException("Request failed: " + response.code() + " - " + errBody);
            }
            String body = response.body().string();
            log.debug("搜索响应长度: %d 字节", body.length());
            return body;
        }
    }


    /**
     * 解析录像搜索响应，返回录像信息列表
     */
    private static List<RecordingInfo> parseRecordingResponse(String xml) throws Exception {
        List<RecordingInfo> recordings = new ArrayList<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        NodeList items = doc.getElementsByTagName("searchMatchItem");
        log.info("搜索到录像数量: %d", items.getLength());

        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);

            RecordingInfo rec = new RecordingInfo();
            rec.trackId = getElementText(item, "trackID");
            rec.startTime = getElementText(item, "startTime");
            rec.endTime = getElementText(item, "endTime");
            rec.eventType = getElementText(item, "eventType");
            rec.downloadPath = getElementText(item, "downloadPath");

            recordings.add(rec);

            log.debug("[%d] 通道 %s | 时间: %s 至 %s | 类型: %s",
                    i + 1,
                    rec.trackId,
                    rec.startTime.substring(0, 19),
                    rec.endTime.substring(0, 19),
                    rec.eventType);
        }

        return recordings;
    }

    /**
     * 安全获取XML元素文本
     */
    private static String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0 && nodes.item(0) != null) {
            return nodes.item(0).getTextContent();
        }
        return "";
    }


}
