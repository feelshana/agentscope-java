package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed, bounded read projection of extra_sync.py control records; never reads business rows. */
public final class BiExtraSyncStatus {
    private static final Map<String, String> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("bi_page_click_log", "页面点击日志");
        TABLES.put("bi_iam_user", "IAM用户");
        TABLES.put("bi_tenant_optimization_daily", "租户优化日报");
        TABLES.put("bi_project_capacity_daily", "项目容量日报");
        TABLES.put("bi_screen", "大屏对象");
        TABLES.put("bi_screen_visit_log", "大屏访问日志");
    }

    private static final Set<String> SNAPSHOTS = Set.of("bi_iam_user", "bi_screen");

    public record Gap(String from, String through, String reason) {}

    public record Table(
            String name,
            String label,
            boolean snapshot,
            String state,
            String requestedFrom,
            String availableFrom,
            String coverageFrom,
            String processedThrough,
            Instant refreshedAt,
            Long rowCount,
            int gapCount,
            List<Gap> gaps) {}

    public record View(
            String state,
            String message,
            boolean running,
            boolean paused,
            boolean hasGaps,
            String action,
            BiSyncStatusService.LastRun lastRun,
            List<Table> tables) {}

    private BiExtraSyncStatus() {}

    static View read(Connection connection, ObjectMapper mapper, Clock clock) throws Exception {
        connection.setReadOnly(true);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (var rows =
                    statement.executeQuery(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                    + " table_schema=DATABASE() AND"
                                    + " table_name='bi_extra_sync_control'")) {
                if (!rows.next() || rows.getInt(1) == 0)
                    return empty("NOT_INITIALIZED", "尚未初始化扩展任务，基础7表任务不受影响。");
            }
            Map<String, String> values = new LinkedHashMap<>();
            String keys =
                    TABLES.keySet().stream()
                            .map(n -> "'table:" + n + "'")
                            .collect(java.util.stream.Collectors.joining(","));
            try (var rows =
                    statement.executeQuery(
                            "SELECT name,value FROM bi_extra_sync_control WHERE name IN"
                                    + " ('owner','paused','last_run',"
                                    + keys
                                    + ")")) {
                while (rows.next()) values.put(rows.getString(1), rows.getString(2));
            }
            if (!"\"dataagent-bi-extra-v1\"".equals(values.get("owner")))
                return empty("UNSUPPORTED", "扩展任务归属或版本不匹配，请由运维核对。");
            boolean running;
            try (var rows =
                    statement.executeQuery("SELECT IS_USED_LOCK('dataagent-bi-extra-sync-v1')")) {
                running = rows.next() && rows.getObject(1) != null;
            }
            return project(values, running, mapper, clock);
        }
    }

    static View project(
            Map<String, String> values, boolean running, ObjectMapper mapper, Clock clock)
            throws Exception {
        List<Table> tables = new ArrayList<>();
        boolean hasGaps = false;
        LocalDate yesterday =
                LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).minusDays(1);
        for (var entry : TABLES.entrySet()) {
            JsonNode info = parse(values.get("table:" + entry.getKey()), mapper);
            boolean snapshot = SNAPSHOTS.contains(entry.getKey());
            List<Gap> gaps = new ArrayList<>();
            JsonNode sourceGaps = info.path("gaps");
            if (!sourceGaps.isMissingNode() && !sourceGaps.isArray())
                throw new IllegalArgumentException();
            for (JsonNode gap : sourceGaps) {
                if (gaps.size() == 20) break;
                String reason =
                        switch (gap.path("reason").asText()) {
                            case "SOURCE_HISTORY_UNAVAILABLE" -> "源历史已不可用";
                            case "SOURCE_SNAPSHOT_EMPTY" -> "源快照为空，已保留原数据";
                            case "NOT_PROCESSED" -> "尚未处理";
                            default -> "缺失范围待核对";
                        };
                gaps.add(new Gap(date(gap.path("from")), through(gap.path("until")), reason));
            }
            int gapCount = sourceGaps.isArray() ? sourceGaps.size() : 0;
            hasGaps |= gapCount > 0;
            Instant refreshed = instant(info.path("refreshed_at"));
            String processed = through(info.path("success_until"));
            if (processed != null && LocalDate.parse(processed).isAfter(yesterday))
                throw new IllegalArgumentException("Future progress");
            String state =
                    info.isMissingNode() || info.isNull()
                            ? "NOT_STARTED"
                            : gapCount > 0
                                    ? "GAPS"
                                    : snapshot
                                            ? refreshed == null
                                                    ? "UNKNOWN"
                                                    : refreshed
                                                                    .atZone(
                                                                            ZoneId.of(
                                                                                    "Asia/Shanghai"))
                                                                    .toLocalDate()
                                                                    .isBefore(yesterday.plusDays(1))
                                                            ? "STALE"
                                                            : "CURRENT"
                                            : processed == null
                                                    ? "NOT_STARTED"
                                                    : LocalDate.parse(processed).isBefore(yesterday)
                                                            ? "STALE"
                                                            : "CURRENT";
            Long count =
                    info.path("count").canConvertToLong() && info.path("count").asLong() >= 0
                            ? info.path("count").asLong()
                            : null;
            tables.add(
                    new Table(
                            entry.getKey(),
                            entry.getValue(),
                            snapshot,
                            state,
                            date(info.path("requested_from")),
                            date(info.path("available_from")),
                            date(info.path("coverage_from")),
                            processed,
                            refreshed,
                            count,
                            gapCount,
                            gaps));
        }
        JsonNode run = parse(values.get("last_run"), mapper);
        String outcome = run.path("outcome").asText("");
        if (!Set.of("", "RUNNING", "SUCCESS", "SUCCESS_WITH_GAPS", "FAILED", "PAUSED")
                .contains(outcome)) outcome = "UNKNOWN";
        String action =
                "verify".equals(run.path("action").asText())
                        ? "VERIFY"
                        : "sync".equals(run.path("action").asText()) ? "SYNC" : "UNKNOWN";
        Instant start = instant(run.path("started_at")), end = instant(run.path("finished_at"));
        String failure = "FAILED".equals(outcome) ? "任务未完成，已提交数据保留；请在服务器核对扩展任务日志后续跑。" : null;
        var last =
                run.isMissingNode() || run.isNull()
                        ? null
                        : new BiSyncStatusService.LastRun(
                                outcome,
                                start,
                                end,
                                start != null && end != null && !end.isBefore(start)
                                        ? Duration.between(start, end).toMillis()
                                        : null,
                                failure);
        boolean paused = "true".equals(values.get("paused"));
        String state =
                running
                        ? paused ? "PAUSING" : "RUNNING"
                        : paused
                                ? "PAUSED"
                                : last == null
                                        ? "NOT_STARTED"
                                        : "RUNNING".equals(outcome) ? "INTERRUPTED" : outcome;
        String message =
                switch (state) {
                    case "RUNNING" -> "扩展任务正在运行，已完成的数据保留。";
                    case "PAUSING" -> "已请求暂停，任务将在安全点退出。";
                    case "PAUSED" -> "扩展任务已暂停，已有数据与进度保留。";
                    case "SUCCESS", "SUCCESS_WITH_GAPS" -> "最近一次操作已完成；请逐表核对进度与缺口，部分补跑或核验不代表六表全量更新。";
                    case "FAILED", "INTERRUPTED" -> "最近一次操作未正常完成，请由运维核对；已提交进度保留。";
                    default -> "尚无可确认的执行结果，请核对服务器扩展任务。";
                };
        return new View(
                state, message, running, paused, hasGaps, action, last, List.copyOf(tables));
    }

    private static JsonNode parse(String raw, ObjectMapper mapper) throws Exception {
        if (raw == null) return mapper.missingNode();
        if (raw.length() > 262144) throw new IllegalArgumentException();
        return mapper.readTree(raw);
    }

    private static String date(JsonNode node) {
        return node.isMissingNode() || node.isNull()
                ? null
                : LocalDate.parse(node.asText()).toString();
    }

    private static String through(JsonNode node) {
        String value = date(node);
        return value == null ? null : LocalDate.parse(value).minusDays(1).toString();
    }

    private static Instant instant(JsonNode node) {
        return node.isMissingNode() || node.isNull()
                ? null
                : OffsetDateTime.parse(node.asText()).toInstant();
    }

    static View empty(String state, String message) {
        return new View(state, message, false, false, false, "UNKNOWN", null, List.of());
    }
}
