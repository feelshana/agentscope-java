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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link WrenProperties#resolveExecutable()}: explicit paths win verbatim, the default
 * probes well-known install locations per platform (Linux pip install sites) before falling back
 * to PATH, and {@link WrenProperties#unavailableMessage} carries the cross-platform installation
 * guidance both spawn sites surface on failure.
 */
class WrenPropertiesResolveTest {

    @TempDir Path tmp;

    @Test
    void explicitValueIsUsedVerbatim() {
        WrenProperties props = props("C:\\venvs\\wren\\Scripts\\wren.exe", true, List.of());

        assertThat(props.resolveExecutable()).isEqualTo("C:\\venvs\\wren\\Scripts\\wren.exe");
    }

    @Test
    void defaultFallsBackToPlainNameWhenNoCandidateMatches() {
        // Empty candidates → the launcher resolves "wren" against PATH by itself.
        assertThat(props("wren", true, List.of()).resolveExecutable())
                .isEqualTo(WrenProperties.DEFAULT_EXECUTABLE);
        assertThat(props("wren", false, List.of(tmp.resolve("empty"))).resolveExecutable())
                .isEqualTo(WrenProperties.DEFAULT_EXECUTABLE);
    }

    @Test
    void windowsProbesWrenExe() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("winvenv"));
        Path exe = dir.resolve("wren.exe");
        Files.writeString(exe, "fake");

        assertThat(props("wren", true, List.of(tmp.resolve("empty"), dir)).resolveExecutable())
                .isEqualTo(exe.toAbsolutePath().normalize().toString());
    }

    @Test
    void linuxProbesWren() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("linuxbin"));
        Path wren = dir.resolve("wren");
        Files.writeString(wren, "fake");

        assertThat(props("wren", false, List.of(tmp.resolve("empty"), dir)).resolveExecutable())
                .isEqualTo(wren.toAbsolutePath().normalize().toString());
    }

    @Test
    void linuxIgnoresWindowsStyleBinary() throws Exception {
        // A stray wren.exe in a Linux candidate dir must not be picked up.
        Path dir = Files.createDirectories(tmp.resolve("linuxbin"));
        Files.writeString(dir.resolve("wren.exe"), "fake");

        assertThat(props("wren", false, List.of(dir)).resolveExecutable())
                .isEqualTo(WrenProperties.DEFAULT_EXECUTABLE);
    }

    @Test
    void unavailableMessageCarriesCrossPlatformGuidance() {
        String msg = WrenProperties.unavailableMessage("wren", "CreateProcess error=2");

        assertThat(msg)
                .contains("wren 可执行文件「wren」无法启动")
                .contains("pip install 'wrenai[mysql,mcp]' 'mcp<2'")
                .contains("~/.local/bin")
                .contains("DATAAGENT_WREN_EXECUTABLE")
                .contains("原始错误：CreateProcess error=2");
    }

    /** Test double pinning the platform and candidate list so both branches are deterministic. */
    private static WrenProperties props(String executable, boolean windows, List<Path> dirs) {
        return new WrenProperties(executable, "", "dataagent", "mysql", 10) {
            @Override
            boolean windows() {
                return windows;
            }

            @Override
            List<Path> candidateDirs() {
                return dirs;
            }
        };
    }
}
