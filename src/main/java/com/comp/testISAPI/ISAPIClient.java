package com.comp.testISAPI;

import okhttp3.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 海康威视 ISAPI 客户端
 * 支持常用接口：设备信息、通道列表、录像搜索、实时预览地址等
 */
public class ISAPIClient {

    private static final Logger log = Logger.getLogger(ISAPIClient.class);

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final OkHttpClient client;

    public ISAPIClient(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .authenticator(new DigestAuthenticator(username, password))
                .build();
        log.info("创建 ISAPI 客户端: %s:%d", host, port);
    }

    private String getBaseUrl() {
        return String.format("http://%s:%d", host, port);
    }

    // ==================== 设备信息 ====================

    /**
     * 获取设备基本信息
     */
    public DeviceInfo getDeviceInfo() throws IOException {
        log.info("获取设备信息...");
        String url = getBaseUrl() + "/ISAPI/System/deviceInfo";
        
        Request request = new Request.Builder().url(url).get().build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("获取设备信息失败: " + response.code());
            }
            
            String xml = response.body().string();
            log.debug("设备信息响应: %s", xml);
            
            DeviceInfo info = new DeviceInfo();
            Document doc = parseXml(xml);
            info.deviceName = getElementText(doc, "deviceName");
            info.deviceID = getElementText(doc, "deviceID");
            info.model = getElementText(doc, "model");
            info.serialNumber = getElementText(doc, "serialNumber");
            info.firmwareVersion = getElementText(doc, "firmwareVersion");
            info.macAddress = getElementText(doc, "macAddress");
            
            log.info("设备: %s (%s), 固件: %s", info.deviceName, info.model, info.firmwareVersion);
            return info;
        } catch (Exception e) {
            log.error("获取设备信息失败: " + e.getMessage(), e);
            throw new IOException(e);
        }
    }

    // ==================== 通道信息 ====================

    /**
     * 获取所有视频输入通道
     */
    public List<ChannelInfo> getChannels() throws IOException {
        log.info("获取通道列表...");
        List<ChannelInfo> channels = new ArrayList<>();
        
        // 尝试获取模拟通道
        try {
            channels.addAll(getAnalogChannels());
        } catch (Exception e) {
            log.debug("无模拟通道或获取失败: %s", e.getMessage());
        }
        
        // 尝试获取数字通道 (IP通道)
        try {
            channels.addAll(getDigitalChannels());
        } catch (Exception e) {
            log.debug("无数字通道或获取失败: %s", e.getMessage());
        }
        
        // 如果都失败，尝试流通道
        if (channels.isEmpty()) {
            try {
                channels.addAll(getStreamingChannels());
            } catch (Exception e) {
                log.debug("获取流通道失败: %s", e.getMessage());
            }
        }
        
        log.info("共获取 %d 个通道", channels.size());
        return channels;
    }

    private List<ChannelInfo> getAnalogChannels() throws Exception {
        String url = getBaseUrl() + "/ISAPI/System/Video/inputs/channels";
        return parseChannelList(doGet(url), "VideoInputChannel");
    }

    private List<ChannelInfo> getDigitalChannels() throws Exception {
        String url = getBaseUrl() + "/ISAPI/ContentMgmt/InputProxy/channels";
        return parseChannelList(doGet(url), "InputProxyChannel");
    }

    private List<ChannelInfo> getStreamingChannels() throws Exception {
        String url = getBaseUrl() + "/ISAPI/Streaming/channels";
        return parseChannelList(doGet(url), "StreamingChannel");
    }

    private List<ChannelInfo> parseChannelList(String xml, String tagName) throws Exception {
        List<ChannelInfo> channels = new ArrayList<>();
        Document doc = parseXml(xml);
        NodeList nodes = doc.getElementsByTagName(tagName);
        
        for (int i = 0; i < nodes.getLength(); i++) {
            Element elem = (Element) nodes.item(i);
            ChannelInfo ch = new ChannelInfo();
            ch.id = getElementText(elem, "id");
            ch.name = getElementText(elem, "name");
            if (ch.name.isEmpty()) {
                ch.name = getElementText(elem, "channelName");
            }
            ch.enabled = "true".equalsIgnoreCase(getElementText(elem, "enabled"));
            
            // 计算通道号（用于录像搜索）
            try {
                int chNum = Integer.parseInt(ch.id);
                ch.trackId = String.valueOf(chNum * 100 + 1); // 101, 201, 301...
            } catch (NumberFormatException e) {
                ch.trackId = ch.id;
            }
            
            channels.add(ch);
            log.debug("通道: id=%s, name=%s, trackId=%s", ch.id, ch.name, ch.trackId);
        }
        return channels;
    }

    // ==================== 实时预览 ====================

    /**
     * 获取 RTSP 预览地址
     * @param channelId 通道ID（如 1, 2, 3）
     * @param streamType 流类型: 1=主码流, 2=子码流
     */
    public String getRtspUrl(int channelId, int streamType) {
        // 通道号计算: 通道1主码流=101, 通道1子码流=102, 通道2主码流=201...
        int streamId = channelId * 100 + streamType;
        String rtspUrl = String.format("rtsp://%s:%s@%s:%d/ISAPI/Streaming/channels/%d",
                username, password, host, 554, streamId);
        log.info("RTSP 地址 (通道%d, %s): %s", channelId, 
                streamType == 1 ? "主码流" : "子码流", rtspUrl);
        return rtspUrl;
    }

    /**
     * 获取 HTTP-FLV 预览地址（部分设备支持）
     */
    public String getHttpFlvUrl(int channelId, int streamType) {
        int streamId = channelId * 100 + streamType;
        return String.format("http://%s:%d/ISAPI/Streaming/channels/%d/httpPreview",
                host, port, streamId);
    }

    // ==================== 录像管理 ====================

    /**
     * 搜索录像
     */
    public List<RecordingInfo> searchRecordings(String trackId, String startTime, String endTime) throws IOException {
        log.info("搜索录像: 通道=%s, 时间=%s ~ %s", trackId, startTime, endTime);
        
        String searchId = "S-" + System.currentTimeMillis();
        String xmlBody = String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<CMSearchDescription>\n" +
                "  <searchID>%s</searchID>\n" +
                "  <trackList><trackID>%s</trackID></trackList>\n" +
                "  <timeSpanList><timeSpan>\n" +
                "    <startTime>%s</startTime>\n" +
                "    <endTime>%s</endTime>\n" +
                "  </timeSpan></timeSpanList>\n" +
                "  <contentTypeList><contentType>video</contentType></contentTypeList>\n" +
                "  <maxResults>100</maxResults>\n" +
                "</CMSearchDescription>",
                searchId, trackId, startTime, endTime
        );

        String url = getBaseUrl() + "/ISAPI/ContentMgmt/search";
        String xml = doPost(url, xmlBody, "application/xml");
        
        return parseRecordings(xml);
    }

    private List<RecordingInfo> parseRecordings(String xml) throws IOException {
        List<RecordingInfo> recordings = new ArrayList<>();
        try {
            Document doc = parseXml(xml);
            NodeList items = doc.getElementsByTagName("searchMatchItem");
            
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                RecordingInfo rec = new RecordingInfo();
                rec.trackId = getElementText(item, "trackID");
                rec.startTime = getElementText(item, "startTime");
                rec.endTime = getElementText(item, "endTime");
                rec.eventType = getElementText(item, "eventType");
                rec.downloadPath = getElementText(item, "downloadPath");
                
                // 获取 playbackURI
                NodeList mediaNodes = item.getElementsByTagName("playbackURI");
                if (mediaNodes.getLength() > 0) {
                    rec.playbackUri = mediaNodes.item(0).getTextContent();
                }
                
                recordings.add(rec);
            }
            log.info("找到 %d 条录像", recordings.size());
        } catch (Exception e) {
            log.error("解析录像列表失败: " + e.getMessage(), e);
            throw new IOException(e);
        }
        return recordings;
    }

    /**
     * 获取录像回放 RTSP 地址
     */
    public String getPlaybackRtspUrl(String playbackUri) {
        if (playbackUri == null || playbackUri.isEmpty()) {
            return null;
        }
        // playbackUri 格式: rtsp://ip/Streaming/tracks/101?starttime=...
        // 需要加上认证信息
        if (playbackUri.startsWith("rtsp://")) {
            return playbackUri.replace("rtsp://", 
                    String.format("rtsp://%s:%s@", username, password));
        }
        return playbackUri;
    }

    // ==================== PTZ 控制 ====================

    /**
     * PTZ 控制
     * @param channelId 通道ID
     * @param action 动作: left, right, up, down, zoomin, zoomout, stop
     * @param speed 速度 1-7
     */
    public void ptzControl(int channelId, String action, int speed) throws IOException {
        log.info("PTZ 控制: 通道=%d, 动作=%s, 速度=%d", channelId, action, speed);
        
        String pan = "0";
        String tilt = "0";
        String zoom = "0";
        
        switch (action.toLowerCase()) {
            case "left": pan = "-" + speed * 10; break;
            case "right": pan = String.valueOf(speed * 10); break;
            case "up": tilt = String.valueOf(speed * 10); break;
            case "down": tilt = "-" + speed * 10; break;
            case "zoomin": zoom = String.valueOf(speed * 10); break;
            case "zoomout": zoom = "-" + speed * 10; break;
            case "stop": break;
        }
        
        String xmlBody = String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<PTZData>\n" +
                "  <pan>%s</pan>\n" +
                "  <tilt>%s</tilt>\n" +
                "  <zoom>%s</zoom>\n" +
                "</PTZData>",
                pan, tilt, zoom
        );
        
        String url = getBaseUrl() + "/ISAPI/PTZCtrl/channels/" + channelId + "/continuous";
        doPut(url, xmlBody, "application/xml");
    }

    /**
     * PTZ 预置点调用
     */
    public void gotoPreset(int channelId, int presetId) throws IOException {
        log.info("调用预置点: 通道=%d, 预置点=%d", channelId, presetId);
        String url = getBaseUrl() + "/ISAPI/PTZCtrl/channels/" + channelId + "/presets/" + presetId + "/goto";
        doPut(url, "", "application/xml");
    }

    // ==================== 系统功能 ====================

    /**
     * 获取存储状态
     */
    public List<StorageInfo> getStorageStatus() throws IOException {
        log.info("获取存储状态...");
        List<StorageInfo> storages = new ArrayList<>();
        
        try {
            String url = getBaseUrl() + "/ISAPI/ContentMgmt/Storage";
            String xml = doGet(url);
            Document doc = parseXml(xml);
            NodeList nodes = doc.getElementsByTagName("hdd");
            
            for (int i = 0; i < nodes.getLength(); i++) {
                Element elem = (Element) nodes.item(i);
                StorageInfo storage = new StorageInfo();
                storage.id = getElementText(elem, "id");
                storage.name = getElementText(elem, "hddName");
                storage.status = getElementText(elem, "status");
                storage.capacity = getElementText(elem, "capacity");
                storage.freeSpace = getElementText(elem, "freeSpace");
                storages.add(storage);
                log.debug("存储: %s, 状态=%s, 容量=%s, 剩余=%s", 
                        storage.name, storage.status, storage.capacity, storage.freeSpace);
            }
        } catch (Exception e) {
            log.warn("获取存储状态失败: %s", e.getMessage());
        }
        
        return storages;
    }

    /**
     * 获取设备时间
     */
    public String getDeviceTime() throws IOException {
        String url = getBaseUrl() + "/ISAPI/System/time";
        String xml = doGet(url);
        try {
            Document doc = parseXml(xml);
            String localTime = getElementText(doc, "localTime");
            log.info("设备时间: %s", localTime);
            return localTime;
        } catch (Exception e) {
            throw new IOException("解析设备时间失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重启设备
     */
    public void reboot() throws IOException {
        log.warn("正在重启设备...");
        String url = getBaseUrl() + "/ISAPI/System/reboot";
        doPut(url, "", "application/xml");
    }

    // ==================== HTTP 工具方法 ====================

    private String doGet(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("GET 请求失败: " + response.code());
            }
            return response.body().string();
        }
    }

    private String doPost(String url, String body, String contentType) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(MediaType.parse(contentType), body))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("POST 请求失败: " + response.code());
            }
            return response.body().string();
        }
    }

    private void doPut(String url, String body, String contentType) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create(MediaType.parse(contentType), body))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("PUT 请求失败: " + response.code());
            }
        }
    }

    // ==================== XML 工具方法 ====================

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private String getElementText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
    }

    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
    }

    // ==================== 数据类 ====================

    public static class DeviceInfo {
        public String deviceName;
        public String deviceID;
        public String model;
        public String serialNumber;
        public String firmwareVersion;
        public String macAddress;

        public String toJson() {
            return String.format(
                "{\"deviceName\":\"%s\",\"deviceID\":\"%s\",\"model\":\"%s\"," +
                "\"serialNumber\":\"%s\",\"firmwareVersion\":\"%s\",\"macAddress\":\"%s\"}",
                deviceName, deviceID, model, serialNumber, firmwareVersion, macAddress
            );
        }
    }

    public static class ChannelInfo {
        public String id;
        public String name;
        public String trackId; // 用于录像搜索
        public boolean enabled;

        public String toJson() {
            return String.format(
                "{\"id\":\"%s\",\"name\":\"%s\",\"trackId\":\"%s\",\"enabled\":%b}",
                id, name, trackId, enabled
            );
        }
    }

    public static class RecordingInfo {
        public String trackId;
        public String startTime;
        public String endTime;
        public String eventType;
        public String downloadPath;
        public String playbackUri;

        public String toJson() {
            return String.format(
                "{\"trackId\":\"%s\",\"startTime\":\"%s\",\"endTime\":\"%s\"," +
                "\"eventType\":\"%s\",\"downloadPath\":\"%s\",\"playbackUri\":\"%s\"}",
                trackId, startTime, endTime, eventType, 
                escapeJson(downloadPath), escapeJson(playbackUri)
            );
        }

        private String escapeJson(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public static class StorageInfo {
        public String id;
        public String name;
        public String status;
        public String capacity;
        public String freeSpace;

        public String toJson() {
            return String.format(
                "{\"id\":\"%s\",\"name\":\"%s\",\"status\":\"%s\"," +
                "\"capacity\":\"%s\",\"freeSpace\":\"%s\"}",
                id, name, status, capacity, freeSpace
            );
        }
    }

    // ==================== 测试入口 ====================

    public static void main(String[] args) {
        // 使用示例
        ISAPIClient client = new ISAPIClient("192.168.7.99", 80, "admin", "higer12345");
        
        try {
            // 获取设备信息
            DeviceInfo device = client.getDeviceInfo();
            System.out.println("设备: " + device.deviceName + " (" + device.model + ")");
            
            // 获取通道列表
            List<ChannelInfo> channels = client.getChannels();
            for (ChannelInfo ch : channels) {
                System.out.println("通道: " + ch.id + " - " + ch.name + " (trackId=" + ch.trackId + ")");
            }
            
            // 获取 RTSP 地址
            String rtsp = client.getRtspUrl(1, 1);
            System.out.println("RTSP: " + rtsp);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
