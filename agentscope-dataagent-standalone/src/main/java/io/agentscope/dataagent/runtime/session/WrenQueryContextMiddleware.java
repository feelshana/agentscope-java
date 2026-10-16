package io.agentscope.dataagent.runtime.session;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Injects authorized project identities; all semantics are obtained from official Wren tools. */
public final class WrenQueryContextMiddleware implements HarnessRuntimeMiddleware {
    private final WrenQueryAccess access;

    public WrenQueryContextMiddleware(WrenQueryAccess access) {
        this.access = access;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.fromCallable(
                        () -> {
                            StringBuilder text = new StringBuilder(prompt == null ? "" : prompt);
                            text.append("\n\n# 已授权 Wren 知识库\n");
                            for (var group : access.visible(context)) {
                                text.append("- ")
                                        .append(group.getName())
                                        .append("：group_id=")
                                        .append(group.getId())
                                        .append("，已发布版本=")
                                        .append(group.getMdlVersion())
                                        .append("，状态=")
                                        .append(group.getMdlState())
                                        .append('\n');
                            }
                            text.append("字段、业务口径、视图、Cube 与示例按需从官方工具读取；DIRTY 表示草稿未发布，问数仍使用已发布版本。\n");
                            text.append(
                                    """
                                    本轮只能查询当前选中的已授权知识库。未选库时作为普通聊天，不查询历史曾选的库；历史不能扩大本轮查询范围。
                                    多库问题分别按 group_id 调用各库工具并说明来源，不把不同工程模型写入同一 SQL，不猜测关联键。
                                    调用节奏：先遵循官方 usage。当前会话中已有的技能正文、工具 schema、工程说明和模型字段可以复用，
                                    不必每个问题重新 load_skill、wren_list_tools、get_instructions 或列出全部模型/Cube。
                                    仅在上下文缺失、知识库/发布版本变化或工具明确报告失效时重新获取；不同知识库的信息不能混用。
                                    每个新问题仍通过官方 recall_queries 召回相关示例，按需调用官方 get_instructions、describe_model 或 describe_cube；不要用旧查询结果替代本次执行。
                                    结果已足够支持答案时直接回答或分析；仅在错误、疑点、缺失证据或用户要求核验时增加诊断查询。
                                    不为凑步骤查询全表统计，也不省略会影响答案正确性的必要验证。
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
}
