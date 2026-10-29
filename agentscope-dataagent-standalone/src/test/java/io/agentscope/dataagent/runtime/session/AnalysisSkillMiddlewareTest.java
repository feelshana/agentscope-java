package io.agentscope.dataagent.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.tools.data.AnalysisTurn;
import org.junit.jupiter.api.Test;

/** spec102: the prompt follows the user's analysis choice, and nothing else changes. */
class AnalysisSkillMiddlewareTest {
    private final AnalysisSkillMiddleware middleware = new AnalysisSkillMiddleware();

    private String prompt(AnalysisTurn turn) {
        RuntimeContext context = mock(RuntimeContext.class);
        when(context.get(AnalysisTurn.class)).thenReturn(turn);
        return middleware.onSystemPrompt(null, context, "基础提示").block();
    }

    @Test
    void anOrdinaryQueryOnlyLearnsNotToUseTheAnalysisSkills() {
        assertThat(prompt(null))
                .startsWith("基础提示")
                .contains("用户没有选择时不要加载这些技能", "不要调用 propose_analysis")
                .doesNotContain("本轮分析方法");
    }

    @Test
    void anUnconfirmedChoiceOnlyProposesParameters() {
        assertThat(prompt(new AnalysisTurn("anomaly", null)))
                .contains("《异常检测》（待确认参数）", "加载技能 anomaly", "调用一次 propose_analysis")
                .contains("本轮不执行 run_sql");
    }

    @Test
    void confirmedParametersRunTheFixedScript() {
        assertThat(prompt(new AnalysisTurn("forecast", "{\"horizon\":7}")))
                .contains("《趋势预测》（参数已确认）", "{\"horizon\":7}", "skills/forecast/forecast.py")
                .contains("不要再次调用 propose_analysis");
    }
}
