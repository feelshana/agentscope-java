package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowEntity;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Resolves independent database table configurations for SQL execution. */
@Service
public class FactQueryWindowPolicy {
    public static final int DEFAULT_DAYS = 31;
    private final FactQueryWindowRepository tableWindows;
    private final io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasets;
    private final SqlWindowGate gate;
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int APPROVED_LIMIT = 500;
    static final java.time.Duration APPROVED_TTL = java.time.Duration.ofHours(1);

    /**
     * Approvals shared across requests, so one SQL is judged once: a trial run, setting it as an
     * example and a query session no longer get different answers from the gate model. Only
     * approvals are kept; the key includes the registered rules, so a changed window re-checks.
     */
    private final java.util.Map<String, Approved> approved =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Approved> eldest) {
                    return size() > APPROVED_LIMIT;
                }
            };

    private record Approved(SqlWindowGate.Decision decision, long at) {}

    public FactQueryWindowPolicy(
            FactQueryWindowRepository tableWindows,
            io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasets,
            SqlWindowGate gate) {
        this.tableWindows = tableWindows;
        this.datasets = datasets;
        this.gate = gate;
    }

    /** Resolves only registered, limited configurations in this knowledge base. */
    public List<SqlWindowGate.Rule> rules(String groupId) {
        return tableWindows.findByGroupId(groupId).stream()
                .filter(e -> "LIMITED".equals(e.getMode()))
                .map(
                        e -> {
                            var dataset =
                                    datasets.findById(e.getDatasetId())
                                            .filter(d -> groupId.equals(d.getGroupId()))
                                            .orElse(null);
                            if (dataset == null
                                    || "DIMENSION"
                                            .equals(
                                                    DatasetSampling.role(
                                                            DatasetSampling.decode(
                                                                    dataset
                                                                            .getSampleSummaryJson()))))
                                return null;
                            List<String> names = columns(e);
                            if (names.isEmpty()
                                    || e.getWindowDays() == null
                                    || e.getWindowDays() < 1)
                                throw new DatasetException("已登记事实表的时间配置无效，请重新保存表配置", 422);
                            return new SqlWindowGate.Rule(
                                    dataset.getName(), names, e.getWindowDays());
                        })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private record Refusal(
            String message, SqlWindowGate.Decision decision, List<SqlWindowGate.Rule> rules) {}

    public void requireAllowed(
            io.agentscope.core.agent.RuntimeContext context, String groupId, String sql) {
        requireAllowed(context, groupId, sql, "SQL");
    }

    /** Refusals and approvals are invocation-local; SQL and user questions stay unchanged. */
    public void requireAllowed(
            io.agentscope.core.agent.RuntimeContext context,
            String groupId,
            String sql,
            String operation) {
        synchronized (context) {
            long started = System.nanoTime();
            String key = "sqlWindow.refused." + groupId;
            Object refused = context.get(key);
            if (refused instanceof Refusal previous) {
                audit(
                        context,
                        groupId,
                        operation,
                        sql,
                        previous.rules(),
                        previous.decision(),
                        "BLOCKED_AFTER_REFUSAL",
                        started);
                throw new DatasetException(previous.message(), 422);
            }
            List<SqlWindowGate.Rule> registered = rules(groupId);
            if (registered.isEmpty()) {
                audit(
                        context,
                        groupId,
                        operation,
                        sql,
                        registered,
                        new SqlWindowGate.Decision(true, List.of(), "当前知识库没有已登记的受限事实表"),
                        "SKIPPED_NO_LIMIT",
                        started);
                return;
            }
            String cacheKey = "sqlWindow.allowed." + groupId + ":" + registered + ":" + sql;
            if (context.get(cacheKey) instanceof SqlWindowGate.Decision previous) {
                audit(context, groupId, operation, sql, registered, previous, "CACHE_HIT", started);
                return;
            }
            SqlWindowGate.Decision shared = approved(cacheKey);
            if (shared != null) {
                audit(context, groupId, operation, sql, registered, shared, "CACHE_HIT", started);
                context.put(cacheKey, shared);
                return;
            }
            SqlWindowGate.Decision decision = gate.check(sql, registered);
            audit(
                    context,
                    groupId,
                    operation,
                    sql,
                    registered,
                    decision,
                    decision.allowed() ? "ALLOWED" : "DENIED",
                    started);
            if (!decision.allowed()) {
                String message =
                        "SQL_TIME_WINDOW_REJECTED\n"
                                + SqlWindowGate.json(decision)
                                + "\n"
                                + "本轮停止所有SQL尝试。展示真实拒绝原因和逐表明细。用户请求的日期范围缺失或超限时，请用户确认范围或调整配置，不自行缩小、拆分或切换工具绕过。自拟技术样例或可选探查被拒绝时停止验证并说明限制，不要求用户替样例或探查选择日期。检测服务异常时说明检查未完成、查询未执行，请稍后重试，不描述为数据库拒绝或用户日期错误。不要自行修改原问题。";
                context.put(key, new Refusal(message, decision, registered));
                throw new DatasetException(message, 422);
            }
            context.put(cacheKey, decision);
            synchronized (approved) {
                approved.put(cacheKey, new Approved(decision, System.currentTimeMillis()));
            }
        }
    }

    private SqlWindowGate.Decision approved(String key) {
        synchronized (approved) {
            Approved entry = approved.get(key);
            if (entry == null) return null;
            if (System.currentTimeMillis() - entry.at() > APPROVED_TTL.toMillis()) {
                approved.remove(key);
                return null;
            }
            return entry.decision();
        }
    }

    private static void audit(
            io.agentscope.core.agent.RuntimeContext context,
            String groupId,
            String operation,
            String sql,
            List<SqlWindowGate.Rule> rules,
            SqlWindowGate.Decision decision,
            String status,
            long started) {
        io.agentscope.dataagent.web.middleware.ExecutionTimingMiddleware.record(
                context, "sql_window_gate", operation, started, status);
        try {
            var event = new java.util.LinkedHashMap<String, Object>();
            event.put("at", java.time.Instant.now().toString());
            event.put(
                    "traceId",
                    io.agentscope.dataagent.web.middleware.ExecutionTimingMiddleware.traceId(
                            context));
            event.put("sessionId", java.util.Objects.toString(context.getSessionId(), ""));
            event.put("groupId", groupId);
            event.put("operation", operation);
            event.put(
                    "sqlHash",
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    sql.getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8))));
            event.put("status", status);
            event.put("durationMs", (System.nanoTime() - started) / 1_000_000);
            event.put("rules", rules);
            event.put("decision", decision);
            org.slf4j.LoggerFactory.getLogger("SQL_WINDOW_GATE")
                    .info(JSON.writeValueAsString(event));
        } catch (Exception ignored) {
            // Audit failures must never change a gate decision.
        }
    }

    private List<String> columns(FactQueryWindowEntity entity) {
        try {
            return JSON.readValue(
                    entity.getTemporalColumnsJson(),
                    JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
