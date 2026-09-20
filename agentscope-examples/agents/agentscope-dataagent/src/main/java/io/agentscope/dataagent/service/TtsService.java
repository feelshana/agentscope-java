/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.service;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Text-to-Speech service that generates WAV audio files from text using the DashScope TTS API.
 *
 * <p>Uses the DashScope Java SDK's CosyVoice {@link SpeechSynthesizer} (ttsv2 API) to synthesise
 * narration audio. The generated audio file is suitable for embedding into video reports. The API
 * key is read from the same {@code dataagent.dashscope.api-key} property that configures the chat
 * model.
 *
 * <p>Reference: <a
 * href="https://help.aliyun.com/zh/model-studio/use-llm-to-convert-document-to-video">使用大模型将文档自动生成视频</a>
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    @Value("${dataagent.dashscope.api-key:}")
    private String dashscopeApiKey;

    /**
     * Synthesises the given text into a WAV audio file.
     *
     * @param text the narration text to synthesise (simplified Chinese recommended)
     * @return path to the generated WAV file in the system temp directory, or {@code null} if
     *     synthesis failed
     */
    public Path synthesizeToWav(String text) {
        if (text == null || text.isBlank()) {
            log.warn("[TtsService] empty narration text");
            return null;
        }
        if (dashscopeApiKey == null || dashscopeApiKey.isBlank()) {
            log.error("[TtsService] DashScope API key not configured");
            return null;
        }

        try {
            // CosyVoice ttsv2 API: model/voice/format in param, text via call(text)
            SpeechSynthesisParam param =
                    SpeechSynthesisParam.builder()
                            .apiKey(dashscopeApiKey)
                            .model("cosyvoice-v1")
                            .voice("longxiaochun")
                            .speechRate(1.2f)
                            .build();

            SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);
            log.info("[TtsService] synthesising {} chars of narration text", text.length());
            ByteBuffer byteBuffer = synthesizer.call(text);

            if (byteBuffer == null || byteBuffer.remaining() == 0) {
                log.error("[TtsService] TTS returned empty audio buffer");
                return null;
            }

            byte[] audioData = new byte[byteBuffer.remaining()];
            byteBuffer.get(audioData);

            // CosyVoice default output is MP3; ffmpeg in the sandbox handles both MP3 and WAV
            Path wavFile = Files.createTempFile("tts-narration-", ".mp3");
            Files.write(wavFile, audioData);
            log.info("[TtsService] wrote WAV file: {}, {} bytes", wavFile, audioData.length);
            return wavFile;

        } catch (IOException e) {
            log.error("[TtsService] failed to write WAV file: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("[TtsService] TTS synthesis failed: {}", e.getMessage(), e);
            return null;
        }
    }
}
