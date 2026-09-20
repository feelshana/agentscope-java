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
package io.agentscope.dataagent.tools.data;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.service.TtsService;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent tool for generating video reports from analysis results.
 *
 * <p>Combines TTS-generated narration audio with chart images produced by previous {@code
 * run_python} calls into an MP4 video slideshow. The video is synthesised inside the per-user
 * Docker sandbox using a Python script that pre-processes images with PIL and encodes
 * the video with ffmpeg directly for maximum speed and browser compatibility.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Agent calls this tool with the narration text extracted from its analysis.</li>
 *   <li>{@link TtsService} generates a WAV narration audio file via DashScope TTS.</li>
 *   <li>The tool scans the sandbox for image artifacts from previous {@code run_python} calls in
 *       the current session.</li>
 *   <li>A Python script is generated and executed in the sandbox to combine images + audio into
 *       an MP4 video.</li>
 *   <li>The video path is returned so the agent can reference it and the frontend can display
 *       it via the workspace binary API.</li>
 * </ol>
 */
public final class VideoReportTool {

    private static final Logger log = LoggerFactory.getLogger(VideoReportTool.class);
    private static final int VIDEO_TIMEOUT_SECONDS = 300;

    private final TtsService ttsService;
    private final AbstractSandboxFilesystem filesystem;

    public VideoReportTool(TtsService ttsService, AbstractSandboxFilesystem filesystem) {
        this.ttsService = ttsService;
        this.filesystem = filesystem;
    }

    @Tool(
            name = "generate_video_report",
            description =
                    """
                    基于当前会话的分析结果生成视频报告。将分析图表（由之前的 run_python 生成）\
                    与旁白语音合成为 MP4 视频。仅在用户明确要求生成视频报告时调用。\
                    narration_text 应为分析结论的简洁摘要，作为视频的配音旁白。\
                    控制在 100-300 字以内，不超过 500 字。\
                    """)
    public String generateVideoReport(
            RuntimeContext rc,
            @ToolParam(
                            name = "narration_text",
                            description = "视频旁白文本，即分析结论的简洁摘要（简体中文，100-300 字，不超过 500 字）")
                    String narrationText) {

        if (narrationText == null || narrationText.isBlank()) {
            return "error: narration_text 不能为空";
        }

        // Enforce max narration length to keep video duration reasonable (target ≤ ~30s audio)
        int maxChars = 500;
        if (narrationText.length() > maxChars) {
            // Truncate at the last sentence boundary before the limit
            String truncated = narrationText.substring(0, maxChars);
            int lastBoundary = Math.max(
                    Math.max(truncated.lastIndexOf('。'), truncated.lastIndexOf('！')),
                    Math.max(truncated.lastIndexOf('？'), truncated.lastIndexOf('.')));
            if (lastBoundary > maxChars / 2) {
                truncated = truncated.substring(0, lastBoundary + 1);
            }
            log.info("[VideoReport] narration text truncated from {} to {} chars",
                    narrationText.length(), truncated.length());
            narrationText = truncated;
        }

        String sessionId =
                rc != null && rc.getSessionId() != null ? rc.getSessionId() : "default";
        String sanitizedSession = sessionId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        String workDir = "/workspace/runpython/" + sanitizedSession;
        String outputsDir = workDir + "/outputs";
        String videoDir = workDir + "/video";

        // ── Step 1: Generate TTS narration audio ──
        log.info("[VideoReport] step 1: generating TTS audio ({} chars)", narrationText.length());
        Path wavFile = ttsService.synthesizeToWav(narrationText);
        if (wavFile == null) {
            return "error: TTS 音频生成失败，请检查 DashScope API 配置";
        }

        try {
            byte[] wavBytes = Files.readAllBytes(wavFile);
            Files.deleteIfExists(wavFile);

            // Upload WAV to sandbox
            String wavPath = videoDir + "/narration.wav";
            ensureDir(rc, videoDir);
            List<FileUploadResponse> uploadResults =
                    filesystem.uploadFiles(
                            rc,
                            List.of(new AbstractMap.SimpleEntry<>(wavPath, wavBytes)));
            if (!uploadResults.isEmpty() && !uploadResults.get(0).isSuccess()) {
                // Fallback: upload via base64 exec
                String b64 = Base64.getEncoder().encodeToString(wavBytes);
                uploadViaBase64(rc, wavPath, b64);
            }
            log.info("[VideoReport] uploaded WAV to sandbox: {} ({} bytes)", wavPath, wavBytes.length);

            // ── Step 2: Discover image artifacts in the sandbox ──
            log.info("[VideoReport] step 2: scanning for image artifacts in {}", outputsDir);
            ExecuteResponse listResp =
                    filesystem.execute(
                            rc,
                            "ls -1 " + outputsDir + "/*.png " + outputsDir + "/*.jpg "
                                    + outputsDir + "/*.jpeg 2>/dev/null || true",
                            10);
            String rawLs = listResp.output() != null ? listResp.output().strip() : "";
            if (rawLs.isEmpty()) {
                return "error: 未找到图表图片。请先生成包含图表的分析结果，再生成视频报告。";
            }
            String[] imagePaths = rawLs.split("\n");
            log.info("[VideoReport] found {} image(s)", imagePaths.length);

            // ── Step 3: Generate and run Python video synthesis script ──
            log.info("[VideoReport] step 3: generating video synthesis script");
            String pythonScript = buildVideoScript(videoDir, outputsDir, imagePaths);

            String scriptPath = videoDir + "/make_video.py";
            byte[] scriptBytes = pythonScript.getBytes(StandardCharsets.UTF_8);
            List<FileUploadResponse> scriptUpload =
                    filesystem.uploadFiles(
                            rc,
                            List.of(new AbstractMap.SimpleEntry<>(scriptPath, scriptBytes)));
            if (!scriptUpload.isEmpty() && !scriptUpload.get(0).isSuccess()) {
                String b64 = Base64.getEncoder().encodeToString(scriptBytes);
                uploadViaBase64(rc, scriptPath, b64);
            }

            log.info("[VideoReport] executing video synthesis script");
            ExecuteResponse execResp =
                    filesystem.execute(
                            rc,
                            "cd " + videoDir + " && python3 " + scriptPath,
                            VIDEO_TIMEOUT_SECONDS);

            if (execResp.exitCode() != 0) {
                log.error(
                        "[VideoReport] video synthesis failed: exitCode={}, output={}",
                        execResp.exitCode(),
                        execResp.output());
                return "error: 视频合成失败 (exit=" + execResp.exitCode() + "): "
                        + (execResp.output() != null ? execResp.output().substring(0, Math.min(500, execResp.output().length())) : "");
            }

            // ── Step 4: Verify video file exists ──
            String videoPath = videoDir + "/report.mp4";
            ExecuteResponse statResp =
                    filesystem.execute(rc, "stat -c %s '" + videoPath + "' 2>/dev/null || echo 0", 5);
            long videoSize = 0;
            try {
                videoSize = Long.parseLong(statResp.output().strip());
            } catch (NumberFormatException e) {
                // ignore
            }

            if (videoSize <= 0) {
                return "error: 视频文件未生成，请检查沙箱环境是否支持 ffmpeg";
            }

            log.info("[VideoReport] video generated: {}, {} bytes", videoPath, videoSize);

            // Return structured result with video path reference
            return "### 视频报告已生成\n"
                    + "- path: " + videoPath + "\n"
                    + "- size: " + videoSize + " bytes\n"
                    + "- video_ref: ![" + "视频报告" + "](" + videoPath + ")\n";

        } catch (Exception e) {
            log.error("[VideoReport] unexpected error: {}", e.getMessage(), e);
            return "error: " + e.getMessage();
        }
    }

    /**
     * Builds the Python script that combines image slides with narration audio into an MP4 video.
     *
     * <p>Uses PIL for image pre-processing and ffmpeg directly (via subprocess) for video encoding.
     * Each image is resized to fit a 1280x720 white background with PIL, then encoded as a short
     * H.264 segment. Segments are concatenated with the narration audio using the ffmpeg concat
     * demuxer. This approach is significantly faster than moviepy (seconds vs minutes) and
     * produces browser-compatible output (yuv420p + faststart).
     *
     * @see <a href="https://help.aliyun.com/zh/model-studio/use-llm-to-convert-document-to-video">
     *     使用大模型将文档自动生成视频的最佳实践</a>
     */
    private static String buildVideoScript(String videoDir, String outputsDir, String[] imagePaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("# -*- coding: utf-8 -*-\n");
        sb.append("import os, sys, math, subprocess\n");
        sb.append("from PIL import Image\n\n");

        // Image paths
        sb.append("images = [\n");
        for (String img : imagePaths) {
            String trimmed = img.strip();
            if (!trimmed.isEmpty()) {
                sb.append("    '").append(trimmed.replace("'", "\\'")).append("',\n");
            }
        }
        sb.append("]\n\n");

        String escapedDir = videoDir.replace("'", "\\'");
        sb.append("audio_path = '").append(escapedDir).append("/narration.wav'\n");
        sb.append("output_path = '").append(escapedDir).append("/report.mp4'\n");
        sb.append("prep_dir = '").append(escapedDir).append("/prepared'\n");
        sb.append("seg_dir = '").append(escapedDir).append("/segments'\n\n");

        sb.append("if not images:\n");
        sb.append("    print('error: no images found', file=sys.stderr)\n");
        sb.append("    sys.exit(1)\n\n");

        // Get audio duration via ffprobe
        sb.append("TARGET_W, TARGET_H = 1280, 720\n");
        sb.append("FPS = 10\n");
        sb.append("out = subprocess.check_output(\n");
        sb.append("    ['ffprobe', '-v', 'error', '-show_entries', 'format=duration',\n");
        sb.append("     '-of', 'default=noprint_wrappers=1:nokey=1', audio_path])\n");
        sb.append("audio_duration = float(out.strip())\n");
        sb.append("dur_per_img = audio_duration / len(images)\n");
        sb.append("print(f'audio={audio_duration:.1f}s, per_image={dur_per_img:.1f}s, images={len(images)}')\n\n");

        // Pre-process images with PIL: resize to fit 1280x720, center on white background
        sb.append("os.makedirs(prep_dir, exist_ok=True)\n");
        sb.append("os.makedirs(seg_dir, exist_ok=True)\n");
        sb.append("prepared = []\n");
        sb.append("for i, img_path in enumerate(images):\n");
        sb.append("    img = Image.open(img_path).convert('RGB')\n");
        sb.append("    w, h = img.size\n");
        sb.append("    ratio = w / h\n");
        sb.append("    if ratio > TARGET_W / TARGET_H:\n");
        sb.append("        new_w = TARGET_W\n");
        sb.append("        new_h = math.floor(new_w / ratio)\n");
        sb.append("    else:\n");
        sb.append("        new_h = TARGET_H\n");
        sb.append("        new_w = math.floor(new_h * ratio)\n");
        sb.append("    img = img.resize((new_w, new_h), Image.Resampling.LANCZOS)\n");
        sb.append("    bg = Image.new('RGB', (TARGET_W, TARGET_H), (255, 255, 255))\n");
        sb.append("    bg.paste(img, ((TARGET_W - new_w) // 2, (TARGET_H - new_h) // 2))\n");
        sb.append("    out_path = os.path.join(prep_dir, f'{i:04d}.png')\n");
        sb.append("    bg.save(out_path)\n");
        sb.append("    prepared.append(out_path)\n\n");

        // Encode each prepared image as a short video segment via ffmpeg
        sb.append("segments = []\n");
        sb.append("for i, p in enumerate(prepared):\n");
        sb.append("    seg = os.path.join(seg_dir, f'{i:04d}.mp4')\n");
        sb.append("    n_frames = max(1, math.floor(dur_per_img * FPS))\n");
        sb.append("    cmd = [\n");
        sb.append("        'ffmpeg', '-y', '-loglevel', 'error',\n");
        sb.append("        '-loop', '1', '-framerate', str(FPS), '-i', p,\n");
        sb.append("        '-frames:v', str(n_frames),\n");
        sb.append("        '-c:v', 'libx264', '-preset', 'ultrafast',\n");
        sb.append("        '-pix_fmt', 'yuv420p', '-r', str(FPS), seg]\n");
        sb.append("    subprocess.run(cmd, check=True)\n");
        sb.append("    segments.append(seg)\n\n");

        // Write concat list and combine segments + audio into final video
        sb.append("list_path = os.path.join(seg_dir, 'list.txt')\n");
        sb.append("with open(list_path, 'w') as f:\n");
        sb.append("    for s in segments:\n");
        sb.append("        f.write(f\"file '{s}'\\n\")\n\n");

        sb.append("cmd = [\n");
        sb.append("    'ffmpeg', '-y', '-loglevel', 'error',\n");
        sb.append("    '-f', 'concat', '-safe', '0', '-i', list_path,\n");
        sb.append("    '-i', audio_path,\n");
        sb.append("    '-c:v', 'copy', '-c:a', 'aac', '-b:a', '128k', '-movflags', '+faststart',\n");
        sb.append("    output_path]\n");
        sb.append("subprocess.run(cmd, check=True)\n\n");

        // Clean up temp dirs
        sb.append("import shutil\n");
        sb.append("shutil.rmtree(prep_dir, ignore_errors=True)\n");
        sb.append("shutil.rmtree(seg_dir, ignore_errors=True)\n\n");

        sb.append("size = os.path.getsize(output_path)\n");
        sb.append("print(f'video generated: {output_path} ({size} bytes)')\n");

        return sb.toString();
    }

    private void ensureDir(RuntimeContext rc, String dir) {
        filesystem.execute(rc, "mkdir -p " + dir, 5);
    }

    private void uploadViaBase64(RuntimeContext rc, String path, String b64) {
        int lastSlash = path.lastIndexOf('/');
        String dir = lastSlash > 0 ? path.substring(0, lastSlash) : ".";
        ExecuteResponse resp =
                filesystem.execute(
                        rc,
                        "mkdir -p " + dir + " && echo '" + b64 + "' | base64 -d > " + path,
                        30);
        if (resp.exitCode() != 0) {
            log.warn("[VideoReport] base64 upload fallback failed for {}: {}", path, resp.output());
        }
    }
}
