package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import org.junit.jupiter.api.Test;

/** spec101: query results reach Python through data files, not copied literals. */
class RunPythonToolTest {

    @Test
    void analysisRequiresConfirmationBeforeStartingPython() {
        var fs = mock(AbstractSandboxFilesystem.class);
        var rc = io.agentscope.core.agent.RuntimeContext.builder().userId("alice").build();
        rc.put(AnalysisTurn.class, new AnalysisTurn("forecast", null));
        assertThat(new RunPythonTool(fs).runPython(rc, "print(1)", "预测"))
                .startsWith("error:")
                .contains("确认");
        verifyNoInteractions(fs);
    }

    @Test
    void copiedResultRowsAreRefusedBeforeTouchingTheSandbox() {
        StringBuilder code = new StringBuilder("import pandas as pd\ndata = [\n");
        for (int d = 1; d <= 25; d++)
            code.append("    [\"2026-10-").append(String.format("%02d", d)).append("\", 750],\n");
        code.append("]\ndf = pd.DataFrame(data)\n");
        var fs = mock(AbstractSandboxFilesystem.class);

        String out = new RunPythonTool(fs).runPython(null, code.toString(), "抄写数据");

        assertThat(out).startsWith("error: 代码中写入了超过 20 行的数据字面量").contains("pd.read_csv");
        verifyNoInteractions(fs);
    }

    @Test
    void readingDataFilesAndSmallConstantsAreNotCountedAsCopiedRows() {
        String code =
                """
                import pandas as pd
                import matplotlib.pyplot as plt
                plt.rcParams['font.sans-serif'] = ['Noto Sans CJK SC', 'DejaVu Sans']
                df = pd.read_csv('data/3f9a1c07b2e4.csv')
                fig, ax = plt.subplots(figsize=(12, 6))
                ax.plot(df['日期'], df['访问次数'])
                colors = {'报表': '#3B6FB6', '仪表盘': '#E8743B'}
                """;
        assertThat(RunPythonTool.literalRows(code)).isLessThanOrEqualTo(2);
        assertThat(RunPythonTool.literalRows(code)).isLessThan(RunPythonTool.MAX_LITERAL_ROWS);
    }

    /** spec102: calling an analysis script installs the platform's copies into the session. */
    @Test
    void analysisScriptsAreInstalledOnlyWhenTheCodeCallsThem() {
        var fs = mock(AbstractSandboxFilesystem.class);
        var uploaded = new java.util.ArrayList<String>();
        org.mockito.Mockito.when(
                        fs.execute(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(
                        new io.agentscope.harness.agent.filesystem.model.ExecuteResponse(
                                "", 1, false));
        org.mockito.Mockito.when(
                        fs.uploadFiles(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            for (Object e : (java.util.List<?>) invocation.getArgument(1))
                                uploaded.add(((java.util.Map.Entry<?, ?>) e).getKey().toString());
                            return java.util.List.of();
                        });
        var rc = mock(io.agentscope.core.agent.RuntimeContext.class);
        org.mockito.Mockito.when(rc.getSessionId()).thenReturn("s-1");
        var tool = new RunPythonTool(fs);

        tool.runPython(rc, "print('普通分析')", "普通");
        assertThat(uploaded).noneMatch(p -> p.contains("/skills/"));

        tool.runPython(
                rc,
                "import subprocess, sys\n"
                        + "subprocess.run([sys.executable, 'skills/anomaly/anomaly.py'])",
                "异常检测");
        assertThat(uploaded)
                .contains(
                        "/workspace/runpython/s-1/skills/attribution/attribution.py",
                        "/workspace/runpython/s-1/skills/anomaly/anomaly.py",
                        "/workspace/runpython/s-1/skills/forecast/forecast.py");
    }

    @Test
    void dictRowsCountLikeListRows() {
        StringBuilder code = new StringBuilder("rows = [\n");
        for (int i = 0; i < 21; i++)
            code.append("  {'部门': '部门").append(i).append("', '次数': ").append(i).append("},\n");
        code.append("]\n");
        assertThat(RunPythonTool.literalRows(code.toString())).isEqualTo(21);
    }
}
