package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** spec097: built-in analysis skills and the parameter-card tool. */
class AnalysisSkillsTest {
    @TempDir Path tmp;

    @Test
    void onlyBuiltInMethodsAreAccepted() {
        assertThat(AnalysisSkills.normalize(" Attribution ")).isEqualTo("attribution");
        assertThat(AnalysisSkills.normalize("anomaly")).isEqualTo("anomaly");
        assertThat(AnalysisSkills.normalize("../usage")).isNull();
        assertThat(AnalysisSkills.normalize(null)).isNull();
    }

    @Test
    void everySkillShipsItsInstructionsAndOneScript() throws Exception {
        assertThat(AnalysisSkills.scripts())
                .containsOnlyKeys(
                        "attribution/attribution.py", "anomaly/anomaly.py", "forecast/forecast.py");
        Path root = AnalysisSkills.materialize(tmp.resolve("skills"));
        for (String id : AnalysisSkills.IDS) {
            String skill = Files.readString(root.resolve(id + "/SKILL.md"), StandardCharsets.UTF_8);
            // The harness filters skills by name, so the front matter must use the id.
            assertThat(skill).startsWith("---\nname: " + id + "\n");
            assertThat(skill)
                    .contains("propose_analysis", "skills/" + id + "/" + id + ".py", "分析计算暂不可用")
                    .contains("推断");
        }
    }

    @Test
    void materialisingReplacesAnEarlierVersion() throws Exception {
        Path target = tmp.resolve("skills");
        Files.createDirectories(target.resolve("old"));
        Files.writeString(target.resolve("old/SKILL.md"), "stale");
        AnalysisSkills.materialize(target);
        assertThat(target.resolve("old")).doesNotExist();
        assertThat(target.resolve("forecast/forecast.py")).exists();
    }

    @Test
    void theParameterCardIsOnlyForAnUnconfirmedAnalysisTurn() {
        var tool = new AnalysisToolkit();
        RuntimeContext none = mock(RuntimeContext.class);
        assertThat(propose(tool, none, "attribution")).startsWith("error: 本轮用户没有选择分析方法");

        RuntimeContext pending = mock(RuntimeContext.class);
        when(pending.get(AnalysisTurn.class)).thenReturn(new AnalysisTurn("attribution", null));
        assertThat(propose(tool, pending, "forecast")).startsWith("error: method 必须是");
        assertThat(propose(tool, pending, "attribution")).contains("参数确认卡", "不要执行查询");

        RuntimeContext confirmed = mock(RuntimeContext.class);
        when(confirmed.get(AnalysisTurn.class))
                .thenReturn(new AnalysisTurn("attribution", "{\"metric\":\"访问次数\"}"));
        assertThat(propose(tool, confirmed, "attribution")).startsWith("error: 参数已经确认");
    }

    private static String propose(AnalysisToolkit tool, RuntimeContext context, String method) {
        return tool.proposeAnalysis(
                context,
                method,
                "访问次数（报表访问明细记录行数）",
                null,
                "2026-10-01 至 2026-10-07",
                "2026-09-24 至 2026-09-30",
                "day",
                List.of("部门"),
                List.of("部门", "项目"),
                2,
                null,
                List.of());
    }
}
