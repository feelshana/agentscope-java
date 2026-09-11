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
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent-facing tool for executing Python data-analysis code inside the sandbox.
 *
 * <p>The agent writes Python code that uses pandas, matplotlib, scipy etc., and this tool
 * handles the full lifecycle: write the script into the sandbox, execute it, collect stdout and
 * any generated artifacts (PNG charts, CSV files, markdown reports). The result is returned as a
 * structured markdown report that the frontend parses to render code blocks and inline artifacts.
 *
 * <p>Relies on the sandbox having Python 3 with the required scientific libraries pre-installed.
 * The Docker image used by the per-user sandboxes (built from {@code docker/sandbox.Dockerfile})
 * includes pandas, matplotlib, scipy, numpy and Noto CJK fonts. The sandbox runs with
 * {@code --network=none}, so everything must be baked into the image.
 */
public final class RunPythonTool {

    private static final Logger log = LoggerFactory.getLogger(RunPythonTool.class);
    private static final int EXEC_TIMEOUT_SECONDS = 120;
    private static final String OUTPUT_DIR = "outputs";

    /** File extensions recognized as displayable artifacts. */
    private static final Set<String> ARTIFACT_EXTENSIONS =
            new LinkedHashSet<>(List.of(".png", ".jpg", ".jpeg", ".svg", ".csv", ".txt", ".md"));

    private final AbstractSandboxFilesystem filesystem;

    public RunPythonTool(AbstractSandboxFilesystem filesystem) {
        this.filesystem = filesystem;
    }

    /**
     * Executes Python code in the sandbox and returns a structured markdown report.
     *
     * <p>The framework injects {@link RuntimeContext} (not an LLM argument) so the tool can
     * resolve the per-call sandbox binding.
     *
     * @param rc per-call runtime context (injected by the framework)
     * @param code the Python source code to execute
     * @param description a short Chinese description of what the code does
     */
    @Tool(
            name = "run_python",
            description =
                    """
                    在沙箱中执行 Python 数据分析代码。适用于：考核指标达成分析（目标参考线+\
                    达成率+数据标签+统计摘要）、多指标双 Y 轴对比图、需要逐点数据标签或文字标注的图表、\
                    堆叠柱状图/多子图等组合图表、pandas 数据透视等复杂数据转换、\
                    scipy 统计回归与趋势线、直方图等分布分析、CSV 数据导出。\
                    用户问趋势/对比/构成/完成得怎么样等视觉分析问题时，无需明说“画图”也应主动可视化：\
                    查到数据后直接用本工具（或 render_chart）生成图表，由问题性质判定而非字面提示。\
                    建议分两次调用：先传探查代码（shape/dtypes/head）确认列名与取值范围，再传正式分析代码。\
                    产物保存到 outputs/ 下并用主题命名（如 outputs/活跃用户趋势.png、\
                    outputs/活跃用户趋势_data.csv、outputs/活跃用户趋势_insights.md）。\
                    返回包含代码、执行输出和产物列表的结构化报告。\
                    只要一张无标注的简单图表时用 render_chart。\
                    【重要】当产物中有图片时，你必须从下面的“图片引用”行中复制完整的 ![...](...)\
                    到你的最终分析回复中，不要自行猜测文件名或路径。\
                    """)
    public String runPython(
            RuntimeContext rc,
            @ToolParam(name = "code", description = "Python 源代码") String code,
            @ToolParam(name = "description", description = "代码功能简述（中文）", required = false)
                    String description) {

        if (code == null || code.isBlank()) {
            return "error: code 不能为空";
        }

        String sessionId = rc != null && rc.getSessionId() != null ? rc.getSessionId() : "default";
        String workDir = "/workspace/runpython/" + sanitize(sessionId);

        // ── Step 1: Write the Python script into the sandbox ──
        String scriptPath = workDir + "/analysis.py";
        byte[] codeBytes = code.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<FileUploadResponse> uploadResults =
                filesystem.uploadFiles(
                        rc, List.of(new AbstractMap.SimpleEntry<>(scriptPath, codeBytes)));
        if (!uploadResults.isEmpty() && !uploadResults.get(0).isSuccess()) {
            // Fallback: write via exec with base64 encoding
            String b64 = Base64.getEncoder().encodeToString(codeBytes);
            ExecuteResponse writeResp =
                    filesystem.execute(
                            rc,
                            "mkdir -p "
                                    + workDir
                                    + "/outputs && echo '"
                                    + b64
                                    + "' | base64 -d > "
                                    + scriptPath,
                            30);
            if (writeResp.exitCode() != 0) {
                return "error: 无法写入 Python 脚本: " + writeResp.output();
            }
        } else {
            // Ensure output directory exists even when upload succeeded
            filesystem.execute(rc, "mkdir -p " + workDir + "/" + OUTPUT_DIR, 10);
        }

        // ── Step 2: Execute the script ──
        ExecuteResponse execResp =
                filesystem.execute(
                        rc, "cd " + workDir + " && python3 " + scriptPath, EXEC_TIMEOUT_SECONDS);

        // ── Step 3: Collect artifacts from outputs/ ──
        ExecuteResponse listResp =
                filesystem.execute(
                        rc, "ls -1 " + workDir + "/" + OUTPUT_DIR + " 2>/dev/null || true", 10);
        String rawLs = listResp.output() != null ? listResp.output().strip() : "";
        log.info("[run_python] ls outputs/ raw=[{}], exitCode={}", rawLs, listResp.exitCode());
        String[] artifactNames = rawLs.isEmpty() ? new String[0] : rawLs.split("\\n");
        log.info("[run_python] found {} artifact candidates", artifactNames.length);

        StringBuilder artifacts = new StringBuilder();
        for (String name : artifactNames) {
            String trimmed = name.strip();
            if (trimmed.isEmpty()) continue;
            String ext = extension(trimmed);
            if (!ARTIFACT_EXTENSIONS.contains(ext)) continue;

            String fullPath = workDir + "/" + OUTPUT_DIR + "/" + trimmed;

            if (isImageExt(ext)) {
                // Image artifacts: reference the sandbox file path instead of
                // embedding base64 data. This keeps the tool result lightweight
                // (~few KB) so it doesn't bloat LLM context or trigger eviction.
                // The frontend fetches the image via the workspace binary API.
                long fileSize = fileSizeFromSandbox(rc, fullPath);
                log.info(
                        "[run_python] image artifact: name={}, path={}, size={}",
                        trimmed,
                        fullPath,
                        fileSize);
                if (fileSize <= 0) {
                    log.warn("[run_python] skipping image {} (stat failed or size=0)", fullPath);
                    continue;
                }
                artifacts.append("\n### artifact:").append(trimmed).append('\n');
                artifacts.append("- type: ").append(artifactType(ext)).append('\n');
                artifacts.append("- size: ").append(fileSize).append('\n');
                artifacts.append("- path: ").append(fullPath).append('\n');
                // Ready-to-use markdown image reference for the LLM to copy verbatim
                artifacts
                        .append("- image_ref: ![")
                        .append(trimmed)
                        .append("](")
                        .append(fullPath)
                        .append(")\n");
            } else {
                // Text / CSV artifacts: download and include inline (typically small
                // and useful for the LLM to reference in subsequent reasoning).
                List<FileDownloadResponse> downloads =
                        filesystem.downloadFiles(rc, List.of(fullPath));
                if (downloads.isEmpty() || !downloads.get(0).isSuccess()) continue;

                byte[] content = downloads.get(0).content();
                if (content == null || content.length == 0) continue;

                artifacts.append("\n### artifact:").append(trimmed).append('\n');
                artifacts.append("- type: ").append(artifactType(ext)).append('\n');
                artifacts.append("- size: ").append(content.length).append('\n');
                String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                // Truncate large text artifacts
                if (text.length() > 8000) {
                    text = text.substring(0, 8000) + "\n...(truncated)";
                }
                artifacts.append("- content:\n```\n").append(text).append("\n```\n");
            }
        }

        // ── Step 4: Build structured report ──
        StringBuilder sb = new StringBuilder();
        if (description != null && !description.isBlank()) {
            sb.append("**描述：** ").append(description).append('\n');
        }
        sb.append("### executed_code\n```python\n").append(code).append("\n```\n");
        sb.append("### exit_code: ").append(execResp.exitCode()).append('\n');
        if (execResp.output() != null && !execResp.output().isBlank()) {
            sb.append("### stdout\n```\n").append(execResp.output()).append("\n```\n");
        }
        if (!artifacts.isEmpty()) {
            sb.append("### artifacts\n").append(artifacts);
        }
        String result = sb.toString();
        log.info(
                "[run_python] result length={}, hasArtifacts={}",
                result.length(),
                !artifacts.isEmpty());
        log.info(
                "[run_python] result preview (first 800 chars):\n{}",
                result.substring(0, Math.min(result.length(), 800)));
        return result;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }

    private static boolean isImageExt(String ext) {
        return ".png".equals(ext)
                || ".jpg".equals(ext)
                || ".jpeg".equals(ext)
                || ".svg".equals(ext);
    }

    private static String artifactType(String ext) {
        return switch (ext) {
            case ".png", ".jpg", ".jpeg", ".svg" -> "image";
            case ".csv" -> "csv";
            default -> "text";
        };
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /**
     * Returns the file size in bytes by querying the sandbox filesystem via
     * {@code stat -c %s}. Returns {@code -1} if the file does not exist or the
     * size cannot be determined.
     */
    private long fileSizeFromSandbox(RuntimeContext rc, String fullPath) {
        String escaped = fullPath.replace("'", "'\\''");
        ExecuteResponse resp = filesystem.execute(rc, "stat -c %s '" + escaped + "'", 5);
        if (resp == null || resp.exitCode() != 0 || resp.output() == null) {
            return -1;
        }
        try {
            return Long.parseLong(resp.output().strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
