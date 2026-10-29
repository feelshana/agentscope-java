package io.agentscope.dataagent.tools.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands a successful query result to {@code run_python} as a CSV in the session sandbox
 * (specs/016, ADR 0030; spec101 restores it for the official Wren tools). Shared by the legacy
 * {@link WrenToolkit} and the invocation-scoped official {@code run_sql}/{@code query_cube}.
 */
public final class QueryDataFiles {
    private static final Logger log = LoggerFactory.getLogger(QueryDataFiles.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Upper bound for the persisted CSV; larger results skip the file handoff. */
    static final int MAX_DATA_FILE_BYTES = 10 * 1024 * 1024;

    /** Session workspace root shared with {@link RunPythonTool} — code runs with cwd here. */
    private static final String RUNPYTHON_DIR = "/workspace/runpython/";

    private final AbstractSandboxFilesystem sandboxFilesystem;
    private final io.agentscope.dataagent.web.artifact.ArtifactStore artifactStore;

    public QueryDataFiles(
            AbstractSandboxFilesystem sandboxFilesystem,
            io.agentscope.dataagent.web.artifact.ArtifactStore artifactStore) {
        this.sandboxFilesystem = sandboxFilesystem;
        this.artifactStore = artifactStore;
    }

    /**
     * Persists the query result as a CSV inside the session's sandbox workspace and returns the
     * reference line appended after the markdown table, so {@code run_python} reads the data with
     * {@code pd.read_csv('data/<file>')} instead of the model copying rows through the context.
     *
     * <p>Best-effort by contract: {@code null}/{@code rc}/{@code sessionId} missing, non-tabular
     * payload, write failure and oversized results all return an empty string (or the oversized
     * hint) — the markdown table remains the only result, never an error.
     *
     * <p>The file name is content-addressed (first 12 hex chars of the payload SHA-256), so a
     * re-run of the same query overwrites the same file instead of accumulating copies.
     */
    public String note(RuntimeContext rc, String payload) {
        if (sandboxFilesystem == null || rc == null || rc.getSessionId() == null) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode cols = root.path("columns");
            JsonNode rows = root.path("rows");
            if (!cols.isArray() || !rows.isArray()) {
                return "";
            }
            byte[] csv = renderCsv(cols, rows).getBytes(StandardCharsets.UTF_8);
            if (csv.length > MAX_DATA_FILE_BYTES) {
                return "\n*(结果过大未生成数据文件，请加聚合或筛选条件，或调小 limit 后重试)*\n";
            }
            String fileName = sha256Hex(payload).substring(0, 12) + ".csv";
            String path = RUNPYTHON_DIR + sanitize(rc.getSessionId()) + "/data/" + fileName;
            String downloadNote = "";
            if (artifactStore != null) {
                var saved =
                        artifactStore.save(
                                rc,
                                java.util.UUID.randomUUID().toString(),
                                fileName,
                                path,
                                csv,
                                true);
                downloadNote = "\n[下载查询数据](" + artifactStore.reference(saved) + ")\n";
                // Python restores this input on demand. A SQL-only answer must not create a
                // sandbox.
            } else {
                List<FileUploadResponse> uploads =
                        sandboxFilesystem.uploadFiles(
                                rc, List.of(new AbstractMap.SimpleEntry<>(path, csv)));
                if (uploads.isEmpty() || !uploads.get(0).isSuccess()) {
                    log.warn(
                            "[query-data] CSV handoff upload failed for session {}",
                            rc.getSessionId());
                    return "";
                }
            }
            return "\n**数据文件：** data/"
                    + fileName
                    + "（"
                    + rows.size()
                    + " 行 × "
                    + cols.size()
                    + " 列；run_python 中 pd.read_csv('data/"
                    + fileName
                    + "') 读取）\n"
                    + downloadNote;
        } catch (Exception e) {
            log.warn(
                    "[query-data] CSV handoff degraded for session {}: {}",
                    rc.getSessionId(),
                    e.getMessage());
            String reason =
                    e
                                    instanceof
                                    io.agentscope.dataagent.web.artifact.ArtifactStore
                                            .CapacityException
                            ? e.getMessage()
                            : "请联系管理员检查附件存储";
            return "\n*(查询成功，但数据文件保存失败：" + reason + "。)*\n";
        }
    }

    /** RFC 4180 CSV rendering: cells with commas/quotes/newlines are quoted, NULL becomes empty. */
    private static String renderCsv(JsonNode cols, JsonNode rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendCsvCell(sb, csvCell(cols.get(i)));
        }
        sb.append('\n');
        for (JsonNode row : rows) {
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                // Official results keep object rows; the compact form uses arrays in column order.
                JsonNode cell = row.isArray() ? row.path(i) : row.path(cols.get(i).asText());
                appendCsvCell(sb, csvCell(cell));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Plain cell text: NULL becomes empty, nested values keep their JSON form. */
    private static String csvCell(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static void appendCsvCell(StringBuilder sb, String value) {
        if (value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            sb.append('"').append(value.replace("\"", "\"\"")).append('"');
        } else {
            sb.append(value);
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Same session-directory sanitisation as {@link RunPythonTool} so both share one root. */
    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
