package com.comp.testISAPI;
/**
 * build digest not basic by ren
 */

import okhttp3.*;
import org.apache.commons.codec.digest.DigestUtils;


import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DigestAuthenticator implements Authenticator {
    private static final Logger log = Logger.getLogger(DigestAuthenticator.class);
    
    private static final Pattern PARAM_PATTERN = Pattern.compile(
            "(\\w+)\\s*=\\s*\"?([^\",]+)\"?"
    );

    private final String username;
    private final String password;
    private String nonce;
    private String realm;
    private String qop = "auth";
    private int nc = 0;
    private final String cnonce = generateCnonce();
    private final Map<String, String> lastAuthParams = new HashMap<>();

    public DigestAuthenticator(String username, String password) {
        this.username = username;
        this.password = password;
        log.debug("[认证] 创建 DigestAuthenticator, 用户: %s", username);
    }

    @Override
    public Request authenticate(Route route, Response response) throws IOException {
        if (response.code() == 401) {
            log.debug("[认证] 收到 401 响应，开始 Digest 认证");
            
            String wwwAuthHeader = response.header("WWW-Authenticate");
            if (wwwAuthHeader == null || !wwwAuthHeader.startsWith("Digest")) {
                log.warn("[认证] 不支持的认证方式: %s", wwwAuthHeader);
                return null;
            }

            Map<String, String> authParams = parseAuthHeader(wwwAuthHeader);
            if (authParams.isEmpty()) {
                log.error("[认证] 解析认证头失败: %s", wwwAuthHeader);
                return null;
            }
            log.debug("[认证] 解析参数: %s", authParams);


            this.realm = authParams.get("realm");
            this.nonce = authParams.get("nonce");
            this.qop = authParams.getOrDefault("qop", "auth");
            this.lastAuthParams.clear();
            this.lastAuthParams.putAll(authParams);


            String uri = getUriFromUrl(response.request().url());
            String method = response.request().method();
            String authHeader = generateAuthHeader(method, uri);

            log.debug("[认证] realm: %s, method: %s", realm != null ? realm : "unknown", method);
            log.debug("[认证] 生成 Authorization 头");
            
            return response.request().newBuilder()
                    .header("Authorization", authHeader)
                    .build();
        }
        return null;
    }


    private Map<String, String> parseAuthHeader(String header) {
        Map<String, String> params = new HashMap<>();

        try {
            String paramStr = header.substring(header.indexOf(" ") + 1).trim();

            Matcher matcher = PARAM_PATTERN.matcher(paramStr);
            while (matcher.find()) {
                String key = matcher.group(1).toLowerCase();
                String value = matcher.group(2);

                try {
                    value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    // ignore decode error
                }
                params.put(key, value);
                log.debug("[认证] 参数: %s = %s", key, value);
            }


            if (!params.containsKey("realm") || !params.containsKey("nonce")) {
                log.warn("[认证] 缺少必要参数 realm 或 nonce");
                return new HashMap<>();
            }

        } catch (Exception e) {
            log.error("[认证] 解析失败: %s", e.getMessage());
        }

        return params;
    }

    private String generateAuthHeader(String method, String uri) {
        String ha1Input = username + ":" + realm + ":" + password;
        String ha1 = md5Hex(ha1Input);

        String ha2Input = method + ":" + uri;
        String ha2 = md5Hex(ha2Input);

        String ncValue = String.format("%08x", ++nc);

        String responseInput;
        if ("auth".equals(qop) || "auth-int".equals(qop)) {
            responseInput = ha1 + ":" + nonce + ":" + ncValue + ":" + cnonce + ":" + qop + ":" + ha2;
        } else {
            responseInput = ha1 + ":" + nonce + ":" + ha2;
        }
        String response = md5Hex(responseInput);

        StringBuilder authHeader = new StringBuilder("Digest ");
        authHeader.append("username=\"").append(username).append("\", ");
        authHeader.append("realm=\"").append(realm).append("\", ");
        authHeader.append("nonce=\"").append(nonce).append("\", ");
        authHeader.append("uri=\"").append(uri).append("\", ");
        authHeader.append("response=\"").append(response).append("\"");

        if ("auth".equals(qop) || "auth-int".equals(qop)) {
            authHeader.append(", qop=").append(qop);
            authHeader.append(", nc=").append(ncValue);
            authHeader.append(", cnonce=\"").append(cnonce).append("\"");
        }

        if (lastAuthParams.containsKey("algorithm")) {
            authHeader.append(", algorithm=").append(lastAuthParams.get("algorithm"));
        }

        if (lastAuthParams.containsKey("opaque")) {
            authHeader.append(", opaque=\"").append(lastAuthParams.get("opaque")).append("\"");
        }

        return authHeader.toString();
    }

    private String getUriFromUrl(HttpUrl url) {
        String path = url.encodedPath();
        String query = url.encodedQuery();
        return query != null ? path + "?" + query : path;
    }

    private String md5Hex(String data) {
        return DigestUtils.md5Hex(data).toLowerCase();
    }

    private String generateCnonce() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return DigestUtils.md5Hex(new String(bytes, StandardCharsets.UTF_8));
    }
}