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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Wraps the Alibaba Cloud NLS one-sentence ASR REST API.
 *
 * <p>Audio bytes (WebM/Opus from MediaRecorder, or WAV/PCM) are POST-ed
 * to the NLS gateway as an octet-stream; the response contains the
 * recognised text in JSON.
 *
 * <p>Supported audio formats by the NLS gateway:
 * PCM, WAV, OGG(OPUS/SPEEX), AMR, MP3, AAC.
 * We ask the browser to record as "audio/webm;codecs=opus" (Chrome default),
 * which maps to OGG-OPUS on the wire – pass format=opus to the API.
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    private final AliyunNlsTokenService tokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${aliyun.nls.app-key:}")
    private String appKey;

    @Value("${aliyun.nls.asr-url:https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr}")
    private String asrUrl;

    public AsrService(AliyunNlsTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Recognises speech from raw audio bytes.
     *
     * @param audioBytes audio binary data (WebM/Opus or WAV/PCM)
     * @param format     audio format hint: "opus", "wav", "pcm", "mp3", etc.
     *                   Pass "opus" for Chrome/Edge WebM recordings.
     * @return recognised text, or empty string if recognition yields nothing
     * @throws Exception on network error or API error
     */
    public String recognize(byte[] audioBytes, String format) throws Exception {
        if (appKey == null || appKey.isEmpty()) {
            throw new IllegalStateException(
                    "aliyun.nls.app-key is not configured");
        }

        String token = tokenService.getToken();

        // Build URL with query params
        String url = asrUrl
                + "?appkey=" + appKey
                + "&format=" + format
                + "&sample_rate=16000"
                + "&enable_punctuation_prediction=true"
                + "&enable_inverse_text_normalization=true";

        log.info("Calling NLS ASR, format={}, bytes={}", format, audioBytes.length);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("X-NLS-Token", token);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("Content-Length", String.valueOf(audioBytes.length));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(audioBytes);
        }

        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        String body = sb.toString();
        log.info("NLS ASR response status={}, body={}", status, body);

        if (status != 200) {
            throw new RuntimeException("NLS ASR failed, status=" + status + ", body=" + body);
        }

        // Response JSON: { "status": 20000000, "task_id": "...", "result": "识别结果" }
        JsonNode root = objectMapper.readTree(body);
        int nlsStatus = root.path("status").asInt();
        if (nlsStatus != 20000000) {
            String msg = root.path("message").asText(body);
            log.warn("NLS ASR returned non-success status={}, message={}", nlsStatus, msg);
            return "";
        }
        return root.path("result").asText("");
    }
}
