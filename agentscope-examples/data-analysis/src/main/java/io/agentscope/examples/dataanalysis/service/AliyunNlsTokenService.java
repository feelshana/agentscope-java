/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.dataanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fetches and caches an Alibaba Cloud NLS access token.
 *
 * <p>Tokens are valid for ~12 h. This service caches the current token and
 * auto-refreshes it 5 minutes before expiry.
 */
@Service
public class AliyunNlsTokenService {

    private static final Logger log = LoggerFactory.getLogger(AliyunNlsTokenService.class);

    @Value("${aliyun.nls.access-key-id:}")
    private String accessKeyId;

    @Value("${aliyun.nls.access-key-secret:}")
    private String accessKeySecret;

    @Value("${aliyun.nls.token-url:https://nls-meta.cn-shanghai.aliyuncs.com/}")
    private String tokenUrl;

    private volatile String cachedToken;
    private volatile long expireTime; // epoch seconds

    /**
     * Returns a valid NLS token, refreshing from Alibaba Cloud if necessary.
     */
    public synchronized String getToken() throws Exception {
        long nowSec = System.currentTimeMillis() / 1000L;
        // refresh 5 minutes before expiry
        if (cachedToken == null || nowSec >= expireTime - 300) {
            refreshToken();
        }
        return cachedToken;
    }

    // ─────────────────────────── internal ───────────────────────────

    private void refreshToken() throws Exception {
        if (accessKeyId == null
                || accessKeyId.isEmpty()
                || accessKeySecret == null
                || accessKeySecret.isEmpty()) {
            throw new IllegalStateException(
                    "aliyun.nls.access-key-id / access-key-secret are not configured");
        }

        // Build query params required by Alibaba Cloud POP token API
        Map<String, String> params = new HashMap<>();
        params.put("AccessKeyId", accessKeyId);
        params.put("Action", "CreateToken");
        params.put("Format", "JSON");
        params.put("RegionId", "cn-shanghai");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", utcTimestamp());
        params.put("Version", "2019-02-28");

        String signature = sign(params, accessKeySecret);
        params.put("Signature", signature);

        String queryStr = buildQueryString(params);
        String fullUrl = tokenUrl + "?" + queryStr;

        log.debug("Fetching NLS token from {}", fullUrl);
        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br =
                new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        String body = sb.toString();
        if (code != 200) {
            throw new RuntimeException(
                    "NLS Token request failed, status=" + code + ", body=" + body);
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(body);
        JsonNode tokenNode = root.path("Token");
        if (tokenNode.isMissingNode()) {
            throw new RuntimeException("NLS Token response missing 'Token' field: " + body);
        }
        this.cachedToken = tokenNode.path("Id").asText();
        this.expireTime = tokenNode.path("ExpireTime").asLong();
        log.info("NLS Token refreshed, expireTime(epoch-s)={}", expireTime);
    }

    // ─────────────────── Alibaba Cloud POP signature ───────────────────

    private static String sign(Map<String, String> params, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException {
        // Sort by key and percent-encode
        String[] keys = params.keySet().toArray(new String[0]);
        Arrays.sort(keys);
        StringBuilder canonicalized = new StringBuilder();
        for (String key : keys) {
            if (canonicalized.length() > 0) canonicalized.append('&');
            canonicalized
                    .append(percentEncode(key))
                    .append('=')
                    .append(percentEncode(params.get(key)));
        }
        String stringToSign =
                "GET&" + percentEncode("/") + "&" + percentEncode(canonicalized.toString());
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] hmac = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hmac);
    }

    private static String percentEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (Exception e) {
            return value;
        }
    }

    private static String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach(
                (k, v) -> {
                    if (sb.length() > 0) sb.append('&');
                    sb.append(percentEncode(k)).append('=').append(percentEncode(v));
                });
        return sb.toString();
    }

    private static String utcTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }
}
