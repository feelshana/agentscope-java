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
                                    调用节奏：先遵循官方 usage。当前会话中已有的技能正文、工具 schema、工程说明和模型字段可以复用，
                                    不必每个问题重新 load_skill、wren_list_tools、get_instructions 或列出全部模型/Cube。
                                    仅在上下文缺失、知识库/发布版本变化或工具明确报告失效时重新获取；不同知识库的信息不能混用。
                                    每个新问题仍按官方技能召回相关示例，按需获取相关模型和业务口径；不要用旧查询结果替代本次执行。
                                    结果已足够支持答案时直接回答或分析；仅在错误、疑点、缺失证据或用户要求核验时增加诊断查询。
                                    不为凑步骤查询全表统计，也不省略会影响答案正确性的必要验证。
                                    """);
                            return text.toString();
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
