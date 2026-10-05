/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link ProcessWrenCli} never throws for a missing executable: the spawn failure comes
 * back as a non-zero {@link WrenCli.Result} carrying the cross-platform installation guidance, so
 * the modeling page surfaces an actionable message instead of a raw OS error.
 */
class ProcessWrenCliTest {

    @TempDir Path tmp;

    @Test
    void missingExecutableReturnsGuidedResult() {
        // Explicit (non-default) name → used verbatim → CreateProcess fails immediately on any OS.
        WrenProperties props =
                new WrenProperties("definitely-not-a-real-wren-xyz", "", "dataagent", "mysql", 10);
        ProcessWrenCli cli = new ProcessWrenCli(props);

        WrenCli.Result result = cli.run(tmp, Duration.ofSeconds(10), List.of("--version"));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.output())
                .contains("wren 可执行文件「definitely-not-a-real-wren-xyz」无法启动")
                .contains("pip install 'wrenai[mysql,mcp]' 'mcp<2'")
                .contains("DATAAGENT_WREN_EXECUTABLE");
    }
}
