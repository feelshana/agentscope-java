package io.agentscope.dataagent.runtime.session;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.tools.data.AnalysisTurn;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Mono;

/**
 * spec102: tells the query agent what the user's analysis choice means for this turn. Without a
 * choice the analysis skills stay unused, so the ordinary query flow is unchanged; with one the
 * turn first proposes parameters, and only a later turn carrying the confirmed parameters queries
 * and runs the fixed script.
 */
public final class AnalysisSkillMiddleware implements HarnessRuntimeMiddleware {

    static final String NOT_SELECTED =
            "\n\n分析技能（attribution / anomaly / forecast）只在本轮上下文写明了用户选择的分析方法时使用；"
                    + "用户没有选择时不要加载这些技能，也不要调用 propose_analysis，按普通问数回答。\n";

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        String base = prompt == null ? "" : prompt;
        AnalysisTurn turn = context == null ? null : context.get(AnalysisTurn.class);
        return Mono.just(base + (turn == null ? NOT_SELECTED : instruction(turn)));
    }

    static String instruction(AnalysisTurn turn) {
        if (!turn.confirmed())
            return "\n\n# 本轮分析方法：《"
                    + turn.name()
                    + "》（待确认参数）\n"
                    + "用户在问数页面选择了《"
                    + turn.name()
                    + "》。本轮只确认参数：加载技能 "
                    + turn.method()
                    + "，按技能中“参数确认”一节预填参数（需要时用 describe_model 了解可分组字段和时间字段），"
                    + "调用一次 propose_analysis 提交，然后用一句话请用户确认并结束本轮。"
                    + "本轮不执行 run_sql、query_cube，不运行 Python；平台会拒绝本轮的查询。\n";
        return "\n\n# 本轮分析方法：《"
                + turn.name()
                + "》（参数已确认）\n"
                + "用户已在参数确认卡中确认以下参数（JSON，以此为准）：\n"
                + turn.confirmedParams()
                + "\n加载技能 "
                + turn.method()
                + "，按技能流程用 run_sql 取数，再用 run_python 运行固定脚本 skills/"
                + turn.method()
                + "/"
                + turn.method()
                + ".py，最后按技能的报告结构回答。不要再次调用 propose_analysis，不要自行编写分析算法。\n";
    }
}
