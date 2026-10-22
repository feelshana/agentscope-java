package io.agentscope.dataagent.runtime.session;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.time.Clock;
import java.time.ZonedDateTime;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Injects authorized project identities; all semantics are obtained from official Wren tools. */
public final class WrenQueryContextMiddleware implements HarnessRuntimeMiddleware {
    private final WrenQueryAccess access;
    private final io.agentscope.dataagent.dataset.FactQueryWindowPolicy windows;
    private final Clock clock;
    private final java.util.function.Function<String, java.nio.file.Path> publishedRoot;

    public WrenQueryContextMiddleware(WrenQueryAccess access) {
        this(access, null);
    }

    public WrenQueryContextMiddleware(
            WrenQueryAccess access, io.agentscope.dataagent.dataset.FactQueryWindowPolicy windows) {
        this(access, windows, Clock.systemDefaultZone(), null);
    }

    /** {@code publishedRoot} locates a group's published snapshot for its time-column formats. */
    public WrenQueryContextMiddleware(
            WrenQueryAccess access,
            io.agentscope.dataagent.dataset.FactQueryWindowPolicy windows,
            java.util.function.Function<String, java.nio.file.Path> publishedRoot) {
        this(access, windows, Clock.systemDefaultZone(), publishedRoot);
    }

    WrenQueryContextMiddleware(
            WrenQueryAccess access,
            io.agentscope.dataagent.dataset.FactQueryWindowPolicy windows,
            Clock clock) {
        this(access, windows, clock, null);
    }

    WrenQueryContextMiddleware(
            WrenQueryAccess access,
            io.agentscope.dataagent.dataset.FactQueryWindowPolicy windows,
            Clock clock,
            java.util.function.Function<String, java.nio.file.Path> publishedRoot) {
        this.access = access;
        this.windows = windows;
        this.clock = clock;
        this.publishedRoot = publishedRoot;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.fromCallable(
                        () -> {
                            StringBuilder text = new StringBuilder(prompt == null ? "" : prompt);
                            text.append(serviceTime(clock));
                            text.append("\n\n# 已授权 Wren 知识库\n");
                            java.util.List<String> limitedTables = new java.util.ArrayList<>();
                            var visible = access.visible(context);
                            if (visible.isEmpty())
                                // 2026-10-22 log: with no knowledge base selected the model tried
                                // the wren CLI through run_python and blamed the sandbox.
                                text.append(
                                        "本轮未选择知识库，没有可查询的数据。用户问到数据时，直接请用户先在问数页面选择一个已发布的知识库；"
                                                + "不要用 run_python、命令行或其它方式自行连接 Wren 或数据库。\n");
                            for (var group : visible) {
                                text.append("- ")
                                        .append(group.getName())
                                        .append("：group_id=")
                                        .append(group.getId())
                                        .append("，已发布版本=")
                                        .append(group.getMdlVersion())
                                        .append("，状态=")
                                        .append(group.getMdlState())
                                        .append('\n');
                                if (windows != null) {
                                    var rules = windows.rules(group.getId());
                                    rules.forEach(rule -> limitedTables.add(rule.table()));
                                    if (publishedRoot != null)
                                        text.append(
                                                io.agentscope.dataagent.dataset.TemporalCondition
                                                        .describe(
                                                                publishedRoot.apply(group.getId()),
                                                                rules));
                                    text.append("当前数据库时间策略（仅运行参考，不写入MDL）：")
                                            .append(
                                                    new com.fasterxml.jackson.databind
                                                                    .ObjectMapper()
                                                            .writeValueAsString(rules))
                                            .append('\n');
                                }
                            }
                            text.append(dateRules(limitedTables));
                            text.append(
                                    "数据库策略是当前平台窗口唯一来源，历史MDL或描述中的固定天数不能覆盖它。策略列表为空表示没有登记的受限表，不能凭历史描述推断限制。修改窗口到知识库事实表时间配置，无需修改MDL或重新发布。\n");
                            text.append("字段、业务口径、视图、Cube 与示例按需从官方工具读取；DIRTY 表示草稿未发布，问数仍使用已发布版本。\n");
                            text.append(
                                    """
                                    本轮只能查询当前选中的已授权知识库。未选库时作为普通聊天，不查询历史曾选的库；历史不能扩大本轮查询范围。
                                    多库问题分别按 group_id 调用各库工具并说明来源，不把不同工程模型写入同一 SQL，不猜测关联键。
                                    日期明确或问题不需要日期后，调用节奏遵循官方 usage。当前会话中已有的技能正文、工具 schema、工程说明和模型字段可以复用，
                                    不必每个问题重新 load_skill、get_instructions 或列出全部模型/Cube。
                                    仅在上下文缺失、知识库/发布版本变化或工具明确报告失效时重新获取；不同知识库的信息不能混用。
                                    进入实际分析的新问题通过官方 recall_queries 召回相关示例，按需调用官方 get_instructions、describe_model 或 describe_cube；不要用旧查询结果替代本次执行。
                                    结果已足够支持答案时直接回答或分析；仅在错误、疑点、缺失证据或用户要求核验时增加诊断查询。
                                    不为凑步骤查询全表统计，也不省略会影响答案正确性的必要验证。
                                    SQL执行成功但结果为空是正常结果，不因无数据而改日期或追加探查。可选探查被时间门禁拒绝时停止本轮SQL尝试，根据已有证据回答或说明限制，不要求用户替探查选择日期。
                                    简单排名问题直接查询所需排名；结果已回答问题时，不额外统计全库总数、名称去重数或反复改写同一聚合。
                                    复杂比较先确定共同的时间窗口、用户粒度和少量核心指标，已有结果足够时收敛回答。连接明细与分层视图时，两侧都应使用一致时间范围；不要把分层视图的时间范围误当成明细表也已过滤。
                                    占比应在过滤分子之前确定完整分母，不把过滤后分组自身作为分母；保留实际数据覆盖和空值，不将局部Top榜推断为全库结论。
                                    简单图表使用 render_chart，只有确需程序分析时才使用 run_python。分组、计数、求和、占比等统计在 SQL 中完成，不在回答中手工汇总多行结果。沙箱不可用时不要重复尝试同类操作；需要的统计改用一条聚合 SQL 取得，仍无法取得时说明限制，不手工计算。
                                    Top N 或单条结果优先使用官方 query_cube 的 order_by 与 limit；没有合适 Cube 时使用 run_sql 的标准 SQL（可按官方语法使用 LIMIT/OFFSET）。不要把 View（例如 valid_orders）传给 describe_model；View 用 get_mdl/describe_schema，Cube 用 describe_cube。
                                    """);
                            text.append("\n# 采样使用约束\n")
                                    .append(
                                            io.agentscope.dataagent.dataset.DatasetSampleDocuments
                                                    .POLICY)
                                    .append(
                                            "样本与规则仅从当前已发布工程获取，可通过官方 get_instructions"
                                                    + " 读取；不得使用未发布草稿。\n");
                            return text.toString();
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Date-first clarification applies only to registered limited fact tables. Emitting it for
     * knowledge bases without any made the model ask for dates (and years) it did not need.
     */
    static String dateRules(java.util.List<String> limitedTables) {
        if (limitedTables.isEmpty())
            return "\n# 受限事实表日期约束\n当前选中知识库没有登记受限事实表：问题未写日期时不因此追问，按问题直接分析，并在答案中说明所用数据范围。"
                    + "用户写了月日未写年份时按当前年份理解，不再确认。\n";
        return "\n# 问数最高优先级：受限事实表缺少日期先询问\n受限事实表："
                + String.join("、", new java.util.LinkedHashSet<>(limitedTables))
                + "\n"
                + "分析涉及以上表时，先根据用户问题和已有对话判断是否已提供日期。未提供时直接询问日期范围，本轮不执行分析SQL或日期探查SQL；这一约束优先于自主探索、技能加载和样例召回。禁止为补足用户未提供的日期执行MIN/MAX、日期排序取首尾记录或其它数据探查，也不得自行选一个日期范围代替用户决定。用户已写明月日、月份（如\"10月\"即该月整月）或相对日期即视为已提供日期，未写年份按当前年份理解，不再确认。用户给出的范围超过该表时间策略的最大天数（例如整年）时，本轮不执行分析SQL，直接回复：该表单次查询最多多少天，请给出不超过上限的具体日期范围；不提出拆分成多次查询再汇总的方案。只涉及其它表、结构查询或业务规则解释时不强制询问日期。直接给出面向用户的回复，不输出推理过程。\n";
    }

    /** Service clock block shared by query and modeling contexts; matches the SQL gate's zone. */
    static String serviceTime(Clock clock) {
        var now = ZonedDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return "\n\n# 当前服务时间\n当前时间："
                + now.toOffsetDateTime()
                + "\n当前日期："
                + now.toLocalDate()
                + "\n服务时区："
                + now.getZone().getId()
                + '\n';
    }
}
