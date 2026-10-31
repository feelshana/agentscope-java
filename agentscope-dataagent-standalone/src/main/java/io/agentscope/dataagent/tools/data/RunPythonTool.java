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

    /**
     * Minimal preamble: ensures Agg backend before any matplotlib import,
     * then monkey-patches plt.savefig to force CJK fonts before every render.
     * This intercepts savefig calls regardless of when the agent's code invokes them,
     * guaranteeing Chinese labels render correctly even if the agent sets non-CJK fonts.
     */
    private static final String MATPLOTLIB_PREAMBLE =
            """
            import matplotlib
            matplotlib.use('Agg')
            import matplotlib.pyplot as _plt
            import os, glob

            # ── Force CJK fonts and patch savefig to re-apply before every render ──
            def _ensure_cjk_fonts():
                _cache_dir = __import__('matplotlib').get_cachedir()
                for _f in glob.glob(os.path.join(_cache_dir, 'fontlist-*.json')):
                    try: os.remove(_f)
                    except: pass
                _plt.rcParams['font.sans-serif'] = [
                    'Noto Sans CJK SC', 'Noto Sans SC',
                    'WenQuanYi Micro Hei', 'WenQuanYi Zen Hei',
                    'SimHei', 'AR PL UMing CN', 'DejaVu Sans'
                ]
                _plt.rcParams['axes.unicode_minus'] = False
                import matplotlib.font_manager as _fm
                _cjk_paths = (glob.glob('/usr/share/fonts/**/Noto*CJK*SC*.ttf', recursive=True)
                            + glob.glob('/usr/share/fonts/**/NotoSans*CJK*.ttf', recursive=True)
                            + glob.glob('/usr/share/fonts/**/WenQuanYi*.ttf', recursive=True))
                if _cjk_paths:
                    _fp = _fm.FontProperties(fname=_cjk_paths[0])
                    _plt.rcParams['font.family'] = _fp.get_name()
                _fm.fontManager = _fm.FontManager()

            _ensure_cjk_fonts()

            _orig_savefig = _plt.savefig
            def _patched_savefig(*args, **kwargs):
                _ensure_cjk_fonts()
                return _orig_savefig(*args, **kwargs)
            _plt.savefig = _patched_savefig
            """;

    /** File extensions recognized as displayable artifacts. */
    private static final Set<String> ARTIFACT_EXTENSIONS =
            new LinkedHashSet<>(List.of(".png", ".jpg", ".jpeg", ".svg", ".csv", ".txt", ".md"));

    private final AbstractSandboxFilesystem filesystem;
    private final io.agentscope.dataagent.web.artifact.ArtifactStore artifactStore;

    public RunPythonTool(AbstractSandboxFilesystem filesystem) {
        this(filesystem, null);
    }

    public RunPythonTool(
            AbstractSandboxFilesystem filesystem,
            io.agentscope.dataagent.web.artifact.ArtifactStore artifactStore) {
        this.filesystem = filesystem;
        this.artifactStore = artifactStore;
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
                    在沙箱中执行 Python 数据分析代码。只在需要对 SQL 结果再做统计计算时使用：\
                    回归与趋势线、预测、分布与分位数、异常识别、多个查询结果合并后派生指标，\
                    或用户明确要求导出文件、制作多图报告。只需展示 SQL 结果（趋势、排行、对比、占比、\
                    堆叠、双轴、参考线、数据标签）时用 render_chart，不用本工具。每个问题最多调用 2 次。\
                    官方 run_sql / query_cube 返回带「数据文件： data/<文件名>.csv」行时，\
                    直接用 pd.read_csv('data/<文件名>.csv') 读取（工作目录即沙箱会话目录）；\
                    禁止把查询结果抄写成 Python 字面量，超过 20 行的数据字面量会被拒绝执行。\
                    沙箱中没有 wren 包，不要 import wren，也不要连接数据库。\
                    默认仅自动恢复最近的有限数量分析输入；历史文件缺失时调用 restore_artifact 后重试。\
                    产物保存到 outputs/ 下并用主题命名（如 outputs/活跃用户趋势.png）。\
                    回答中由 Python 计算得出的数值须注明计算方法。返回包含代码、执行输出和产物列表的结构化报告。\
                    【重要】当产物中有图片时，你必须从下面的"图片引用"行中复制完整的 ![...](...)\
                    到你的最终分析回复中，不要自行猜测文件名或路径。\
                    【强制】图表中所有可见文字（标题、坐标轴标签、图例、注释、参考线标注等）必须使用中文，\
                    禁止使用英文。例如 ax.set_title('活跃用户趋势') 而非 ax.set_title('Active User Trend')。\
                    """)
    public String runPython(
            RuntimeContext rc,
            @ToolParam(name = "code", description = "Python 源代码") String code,
            @ToolParam(name = "description", description = "代码功能简述（中文）", required = false)
                    String description) {

        var budget =
                rc == null
                        ? null
                        : rc.get(io.agentscope.dataagent.web.chat.ChatCapacity.Lease.class);
        try (var operation = budget == null ? null : budget.operation()) {
            return executePython(rc, code, description);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Python 执行失败", error);
        }
    }

    private String executePython(RuntimeContext rc, String code, String description) {

        var analysis = rc == null ? null : rc.get(AnalysisTurn.class);
        if (analysis != null && !analysis.confirmed()) {
            return "error: 请先通过分析参数卡确认本次分析范围，再执行计算。";
        }
        if (code == null || code.isBlank()) {
            return "error: code 不能为空";
        }
        if (literalRows(code) > MAX_LITERAL_ROWS) {
            return "error: 代码中写入了超过 "
                    + MAX_LITERAL_ROWS
                    + " 行的数据字面量，未执行。请用 pd.read_csv('data/<文件名>.csv') 读取查询返回的数据文件；"
                    + "没有数据文件时重新执行查询获取。";
        }

        String sessionId = rc != null && rc.getSessionId() != null ? rc.getSessionId() : "default";
        String sessionDir = "/workspace/runpython/" + sanitize(sessionId);
        String runId = java.util.UUID.randomUUID().toString();
        String workDir = sessionDir + "/runs/" + runId;
        String restoreNotice = "";
        if (artifactStore != null) {
            try {
                restoreNotice = artifactStore.restoreInputs(rc, filesystem);
            } catch (Exception e) {
                return "error: 分析无法开始：" + e.getMessage();
            }
        }

        // ── Step 1: Write the Python script into the sandbox ─
        String scriptPath = workDir + "/analysis.py";
        String fullCode = MATPLOTLIB_PREAMBLE + "\n" + code;
        byte[] codeBytes = fullCode.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Each invocation owns an immutable output directory; share only the session input data.
        filesystem.execute(
                rc,
                "mkdir -p "
                        + workDir
                        + "/outputs "
                        + sessionDir
                        + "/data && ln -s "
                        + sessionDir
                        + "/data "
                        + workDir
                        + "/data",
                10);

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
        String skillsNotice = installAnalysisScripts(rc, code, sessionDir, workDir);
        if (skillsNotice != null) return skillsNotice;

        // ── Step 2: Execute the script ──
        ExecuteResponse execResp =
                filesystem.execute(
                        rc,
                        // Keep timeout/Python in DockerSandbox's managed process group so
                        // cancelling a turn also stops Python and its subprocesses.
                        "cd "
                                + workDir
                                + " && timeout --foreground --kill-after=5s 120s python3 "
                                + scriptPath,
                        EXEC_TIMEOUT_SECONDS + 10);

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

            try {
                long size = fileSizeFromSandbox(rc, fullPath);
                long max = artifactStore != null ? artifactStore.maxBytes() : 20L * 1024 * 1024;
                if (size <= 0 || size > max) throw new IllegalStateException("文件为空或超过保存上限");
                byte[] content =
                        io.agentscope.dataagent.web.artifact.SandboxArtifactReader.read(
                                rc, filesystem, fullPath, size);
                String reference = fullPath;
                if (artifactStore != null) {
                    var saved = artifactStore.save(rc, runId, trimmed, fullPath, content, false);
                    reference = artifactStore.reference(saved);
                }
                artifacts.append("\n### artifact:").append(trimmed).append('\n');
                artifacts.append("- type: ").append(artifactType(ext)).append('\n');
                artifacts.append("- size: ").append(content.length).append('\n');
                artifacts.append("- path: ").append(reference).append('\n');
                if (isImageExt(ext)) {
                    artifacts
                            .append("- image_ref: ![")
                            .append(trimmed.replace("[", "").replace("]", ""))
                            .append("](")
                            .append(reference)
                            .append(")\n");
                } else {
                    String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                    if (text.length() > 8000) text = text.substring(0, 8000) + "\n...(truncated)";
                    artifacts.append("- content:\n```\n").append(text).append("\n```\n");
                }
            } catch (Exception e) {
                log.warn("[run_python] artifact persistence failed: {}", trimmed, e);
                artifacts
                        .append("\n文件 ")
                        .append(trimmed)
                        .append(" 保存失败：")
                        .append(e.getMessage())
                        .append("。未生成永久下载链接，请处理存储问题后重新执行。\n");
            }
        }

        // Durable links no longer depend on this temporary run directory.
        if (artifactStore != null) filesystem.execute(rc, "rm -rf -- " + workDir, 10);

        // ── Step 4: Build structured report ──
        StringBuilder sb = new StringBuilder();
        if (!restoreNotice.isBlank()) sb.append(restoreNotice).append('\n');
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

    @Tool(
            name = "restore_artifact",
            description =
                    "继续分析以前生成的附件时，传入 /api/artifacts/{id}/content 中的 id，将文件恢复到当前分析目录。返回的相对路径可直接用于"
                            + " run_python。")
    public String restoreArtifact(
            RuntimeContext rc,
            @ToolParam(name = "artifact_id", description = "历史附件的 UUID") String artifactId) {
        if (artifactStore == null) return "error: 未配置附件存储";
        try {
            return "已恢复：" + artifactStore.restore(rc, artifactId, filesystem);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
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
     * spec102: when the code calls a built-in analysis script, the platform copies the scripts into
     * the session ({@code skills/<id>/<id>.py}) so the model never writes or alters them. Ordinary
     * Python runs skip this. Returns an error text when the scripts cannot be installed.
     */
    private String installAnalysisScripts(
            RuntimeContext rc, String code, String sessionDir, String workDir) {
        if (!code.contains("skills/")) return null;
        var scripts = AnalysisSkills.scripts();
        if (scripts.isEmpty()) return "error: 分析脚本不可用，请联系管理员";
        StringBuilder dirs = new StringBuilder("mkdir -p");
        for (String id : AnalysisSkills.IDS)
            dirs.append(' ').append(sessionDir).append("/skills/").append(id);
        filesystem.execute(rc, dirs.toString(), 10);
        List<java.util.Map.Entry<String, byte[]>> files = new java.util.ArrayList<>();
        scripts.forEach(
                (path, bytes) ->
                        files.add(
                                new AbstractMap.SimpleEntry<>(
                                        sessionDir + "/skills/" + path, bytes)));
        List<FileUploadResponse> uploaded = filesystem.uploadFiles(rc, files);
        if (uploaded.stream().anyMatch(r -> !r.isSuccess())) return "error: 无法安装分析脚本，请稍后重试";
        filesystem.execute(rc, "ln -sfn " + sessionDir + "/skills " + workDir + "/skills", 10);
        return null;
    }

    /** spec096: more rows than this in literals means query results were copied into code. */
    static final int MAX_LITERAL_ROWS = 20;

    /** Innermost comma-separated list, or dict with a key, on one line: one copied data row. */
    private static final java.util.regex.Pattern LITERAL_ROW =
            java.util.regex.Pattern.compile(
                    "\\[[^\\[\\]\\n]*,[^\\[\\]\\n]*\\]|\\{[^{}\\n]*:[^{}\\n]*,[^{}\\n]*\\}");

    /**
     * Counts row-shaped literals (innermost lists or dicts with at least two values). Purely
     * lexical: a few constants such as a font list stay far below {@link #MAX_LITERAL_ROWS}.
     */
    static int literalRows(String code) {
        var matcher = LITERAL_ROW.matcher(code);
        int rows = 0;
        while (matcher.find()) rows++;
        return rows;
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
