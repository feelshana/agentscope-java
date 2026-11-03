package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Read-only, credential-free projection of the fixed BI mirror's private task state. */
@Service
public class BiSyncStatusService {
    private static final String OWNER = "dataagent-bi-sync-v2-basic";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final DatasetStoreProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ConnectionSource connections;

    @FunctionalInterface
    interface ConnectionSource {
        Connection open() throws SQLException;
    }

    public record LastRun(
            String outcome,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            String message) {}

    public record View(
            String state,
            String message,
            boolean running,
            boolean paused,
            String freshness,
            String completedThrough,
            String reportThrough,
            String dashboardThrough,
            Instant lastSuccessAt,
            LastRun lastRun) {}

    @Autowired
    public BiSyncStatusService(DatasetStoreProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = Clock.systemUTC();
        this.connections =
                () -> {
                    var options = new Properties();
                    options.setProperty("user", properties.username());
                    options.setProperty("password", properties.password());
                    options.setProperty("connectTimeout", "5000");
                    options.setProperty("socketTimeout", "5000");
                    return DriverManager.getConnection(properties.url(), options);
                };
    }

    BiSyncStatusService(ConnectionSource connections, ObjectMapper mapper, Clock clock) {
        this.properties = null;
        this.connections = connections;
        this.mapper = mapper;
        this.clock = clock;
    }

    public View view() {
        if (properties != null
                && (properties.url() == null || !properties.url().startsWith("jdbc:mysql:")))
            return empty("NOT_CONFIGURED", "当前环境未配置MySQL数据集库。");
        try (var connection = connections.open()) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var rows =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                        + " table_schema=DATABASE() AND"
                                        + " table_name='bi_sync_control'")) {
                    if (!rows.next() || rows.getInt(1) == 0)
                        return empty("NOT_INITIALIZED", "尚未初始化BI同步任务，请先按部署指引完成准备。");
                }
                Map<String, String> values = new HashMap<>();
                try (var rows =
                        statement.executeQuery(
                                "SELECT name,value FROM bi_sync_control WHERE name IN "
                                    + "('owner','paused','last_run','last_success_at','success_until','bi_report_log','bi_dashboard_log')")) {
                    while (rows.next()) values.put(rows.getString(1), rows.getString(2));
                }
                if (!OWNER.equals(values.get("owner")))
                    return empty("UNSUPPORTED", "同步任务版本不匹配，请先核对服务器部署版本。");
                boolean running;
                try (var rows =
                        statement.executeQuery("SELECT IS_USED_LOCK('dataagent-bi-sync-v1')")) {
                    running = rows.next() && rows.getObject(1) != null;
                }
                return project(values, running);
            }
        } catch (SQLException error) {
            // Driver errors may contain connection URLs or credentials; never return or log them.
            return empty("UNAVAILABLE", "暂时无法读取同步状态，请检查目标数据库连接或稍后重试。");
        } catch (Exception error) {
            return empty("UNAVAILABLE", "同步状态记录无法解析，请由运维核对任务版本和日志。");
        }
    }

    View project(Map<String, String> values, boolean running) throws Exception {
        boolean paused = "true".equals(values.get("paused"));
        LocalDate cutoff = cutoff(values.get("success_until"));
        LastRun last = lastRun(values.get("last_run"));
        String state;
        String message;
        if (running) {
            state = paused ? "PAUSING" : "RUNNING";
            message = paused ? "已请求暂停，运行中的任务将在安全点退出。" : "同步任务正在运行，已完成数据仍可查询。";
        } else if (paused) {
            state = "PAUSED";
            message = "同步已暂停，已有数据和进度保留。";
        } else if (last == null) {
            state = "NOT_STARTED";
            message = "尚无执行记录，请先在服务器完成首次同步和核验。";
        } else {
            state =
                    switch (last.outcome()) {
                        case "SUCCESS" -> "SUCCESS";
                        case "FAILED" -> "FAILED";
                        case "RUNNING", "INTERRUPTED" -> "INTERRUPTED";
                        default -> "IDLE";
                    };
            message =
                    switch (state) {
                        case "SUCCESS" -> "最近一次同步已完成，请同时核对数据截止日期。";
                        case "FAILED" -> last.message();
                        case "INTERRUPTED" -> "任务未正常结束，请在服务器核对并续跑。";
                        default -> "当前允许同步，上一次任务已暂停；不会自动立即补跑。";
                    };
        }
        return new View(
                state,
                message,
                running,
                paused,
                freshness(cutoff),
                through(cutoff),
                through(cutoff(values.get("bi_report_log"))),
                through(cutoff(values.get("bi_dashboard_log"))),
                instant(values.get("last_success_at")),
                last);
    }

    private LastRun lastRun(String raw) throws Exception {
        if (raw == null) return null;
        if (raw.length() > 8192) throw new IllegalArgumentException("Invalid metadata size");
        JsonNode node = mapper.readTree(raw);
        String outcome = node.path("outcome").asText();
        if (!java.util.Set.of("RUNNING", "SUCCESS", "FAILED", "PAUSED", "INTERRUPTED")
                .contains(outcome)) throw new IllegalArgumentException("Invalid outcome");
        Instant started = instant(node.path("started_at").asText(null));
        Instant finished = instant(node.path("finished_at").asText(null));
        if (started == null || (finished != null && finished.isBefore(started)))
            throw new IllegalArgumentException("Invalid timestamps");
        Long duration =
                node.path("duration_ms").isIntegralNumber()
                        ? node.path("duration_ms").longValue()
                        : null;
        if (duration != null && duration < 0)
            throw new IllegalArgumentException("Invalid duration");
        String message =
                switch (node.path("failure_phase").asText()) {
                    case "SOURCE_CONNECT" -> "源数据库连接失败，请检查网络和账号配置。";
                    case "SOURCE_SCHEMA" -> "源字段或既有索引检查失败，请核对源表结构。";
                    case "DIMENSIONS" -> "基础表刷新未完成，未提交的变更已回滚。";
                    case "REPORT_LOG" -> "报表日志同步未完成，已提交进度保留，可核对后续跑。";
                    case "DASHBOARD_LOG" -> "仪表盘日志同步未完成，已提交进度保留，可核对后续跑。";
                    case "FINALIZE" -> "任务收尾未正常完成，请核对同步截止日期和日志。";
                    default -> "任务未正常完成，请由运维核对日志。";
                };
        return new LastRun(
                outcome,
                started,
                finished,
                duration,
                java.util.Set.of("FAILED", "INTERRUPTED").contains(outcome) ? message : null);
    }

    private LocalDate cutoff(String value) {
        if (value == null) return null;
        LocalDate date = LocalDate.parse(value);
        if (date.isAfter(LocalDate.now(clock.withZone(ZONE))))
            throw new IllegalArgumentException("Future cutoff");
        return date;
    }

    private String freshness(LocalDate cutoff) {
        if (cutoff == null) return "NONE";
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZONE));
        if (!cutoff.isBefore(now.toLocalDate())) return "CURRENT";
        if (now.getHour() < 3 && !cutoff.isBefore(now.toLocalDate().minusDays(1))) return "WAITING";
        return "STALE";
    }

    private static String through(LocalDate cutoff) {
        return cutoff == null ? null : cutoff.minusDays(1).toString();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static View empty(String state, String message) {
        return new View(state, message, false, false, "UNKNOWN", null, null, null, null, null);
    }
}
