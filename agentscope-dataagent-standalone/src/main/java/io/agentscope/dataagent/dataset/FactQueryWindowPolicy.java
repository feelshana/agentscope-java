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
    private final PublishedAssetWindows assets;
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
        this(tableWindows, datasets, gate, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FactQueryWindowPolicy(
            FactQueryWindowRepository tableWindows,
            io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasets,
            SqlWindowGate gate,
            PublishedAssetWindows assets) {
        this.tableWindows = tableWindows;
        this.datasets = datasets;
        this.gate = gate;
        this.assets = assets;
    }

    /** Limited fact tables plus the published assets that read them. */
    public List<SqlWindowGate.Rule> rules(String groupId) {
        List<SqlWindowGate.Rule> rules = new java.util.ArrayList<>(tableRules(groupId));
        rules.addAll(derived(groupId, rules).rules());
        return List.copyOf(rules);
    }

    /** The limited fact tables an asset can depend on. */
    public List<PublishedAssetWindows.Fact> facts(String groupId) {
        return facts(groupId, tableRules(groupId));
    }

    private List<PublishedAssetWindows.Fact> facts(
            String groupId, List<SqlWindowGate.Rule> tableRules) {
        if (tableRules.isEmpty()) return List.of();
        java.util.Map<String, String> physical = new java.util.HashMap<>();
        for (var dataset : datasets.findByGroupId(groupId))
            physical.put(dataset.getName(), dataset.getTableName());
        return tableRules.stream()
                .map(
                        r ->
                                new PublishedAssetWindows.Fact(
                                        r.table(), physical.get(r.table()), r.maxWindowDays()))
                .toList();
    }

    private PublishedAssetWindows.Derived derived(
            String groupId, List<SqlWindowGate.Rule> tableRules) {
        if (assets == null || tableRules.isEmpty()) return PublishedAssetWindows.Derived.NONE;
        return assets.derive(groupId, facts(groupId, tableRules));
    }

    /** Resolves only registered, limited configurations in this knowledge base. */
    private List<SqlWindowGate.Rule> tableRules(String groupId) {
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
        requireAllowed(context, groupId, sql, operation, null);
    }

    /** {@code draft} replaces the published assets when a draft asset is being verified. */
    public void requireAllowed(
            io.agentscope.core.agent.RuntimeContext context,
            String groupId,
            String sql,
            String operation,
            PublishedAssetWindows.Derived draft) {
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
            List<SqlWindowGate.Rule> tables = tableRules(groupId);
            PublishedAssetWindows.Derived derived =
                    draft == null ? derived(groupId, tables) : draft;
            List<SqlWindowGate.Rule> registered = new java.util.ArrayList<>(tables);
            registered.addAll(derived.rules());
            if (registered.isEmpty() && derived.unconfigured().isEmpty()) {
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
            java.util.Set<String> mentioned = PublishedAssetWindows.names(sql);
            List<String> unconfigured =
                    derived.unconfigured().stream()
                            .filter(a -> mentioned.contains(a.toLowerCase(java.util.Locale.ROOT)))
                            .toList();
            if (!unconfigured.isEmpty()) {
                // Unconfigured assets fail closed: without a time column no condition can be
                // checked, and the gate model must not guess one.
                SqlWindowGate.Decision decision =
                        new SqlWindowGate.Decision(
                                false,
                                List.of(),
                                "语义资产「"
                                        + String.join("、", unconfigured)
                                        + "」依赖受限事实表但未配置时间列，不能查询，请联系知识库管理者");
                audit(context, groupId, operation, sql, registered, decision, "DENIED", started);
                throw refuse(context, key, decision, registered);
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
            SqlWindowGate.Decision decision = gate.check(sql, registered, context);
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

    private static DatasetException refuse(
            io.agentscope.core.agent.RuntimeContext context,
            String key,
            SqlWindowGate.Decision decision,
            List<SqlWindowGate.Rule> rules) {
        String message =
                "SQL_TIME_WINDOW_REJECTED\n"
                        + SqlWindowGate.json(decision)
                        + "\n"
                        + "本轮停止所有SQL尝试。展示真实拒绝原因和逐表明细。用户请求的日期范围缺失或超限时，请用户确认范围或调整配置，不自行缩小、拆分或切换工具绕过。自拟技术样例或可选探查被拒绝时停止验证并说明限制，不要求用户替样例或探查选择日期。检测服务异常时说明检查未完成、查询未执行，请稍后重试，不描述为数据库拒绝或用户日期错误。不要自行修改原问题。";
        context.put(key, new Refusal(message, decision, rules));
        return new DatasetException(message, 422);
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
