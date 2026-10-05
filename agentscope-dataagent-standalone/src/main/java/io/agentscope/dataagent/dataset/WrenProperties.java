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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings for the WrenAI semantic layer (specs/010 M2): the CLI executable used to
 * assemble/validate/build MDL projects, where generated MDL projects live on disk, and which
 * connection profile / data source the generated {@code wren_project.yml} declares.
 *
 * <p>Bound from {@code dataagent.wren.*}; all keys are optional and overridable via
 * {@code DATAAGENT_WREN_*} env vars. The MDL home defaults to {@code
 * ~/.agentscope/dataagent/mdl} so runtime artifacts stay outside the project tree (same
 * convention as {@code DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT}).
 */
@Component
public class WrenProperties {

    /** Default CLI name; resolved against PATH by the process launcher when unconfigured. */
    public static final String DEFAULT_EXECUTABLE = "wren";

    @Value("${dataagent.wren.executable:wren}")
    private String executable = DEFAULT_EXECUTABLE;

    /** Blank = default {@code ~/.agentscope/dataagent/mdl}. */
    @Value("${dataagent.wren.mdl-home:}")
    private String mdlHome = "";

    /** Name of the connection profile the generated project references (registered in M3). */
    @Value("${dataagent.wren.profile:dataagent}")
    private String profile = "dataagent";

    /** wren data source kind (mysql for the bundled dataset store). */
    @Value("${dataagent.wren.data-source:mysql}")
    private String dataSource = "mysql";

    @Value("${dataagent.wren.timeout-seconds:180}")
    private int timeoutSeconds = 180;

    /**
     * Idle seconds after which a per-group {@code wren serve mcp} instance is closed by the
     * registry's reaper; a later query respawns it on demand (specs/010 M3, ADR D8).
     */
    @Value("${dataagent.wren.instance-idle-seconds:1800}")
    private int instanceIdleSeconds = 1800;

    /**
     * Explicit override for the official wren skills directory (the {@code skills_content} root
     * bundled inside the wrenai pip package, ADR 0035). Blank = inferred from the resolved
     * executable path by {@link WrenSkillsLocator}.
     */
    @Value("${dataagent.wren.skills-dir:}")
    private String skillsDir = "";

    public WrenProperties() {}

    /** Explicit-value constructor for tests (Spring binds the no-arg instance via @Value). */
    public WrenProperties(
            String executable, String mdlHome, String profile, String dataSource, int timeout) {
        this(executable, mdlHome, profile, dataSource, timeout, 1800);
    }

    /** Full constructor for tests that need to pin the instance idle TTL. */
    public WrenProperties(
            String executable,
            String mdlHome,
            String profile,
            String dataSource,
            int timeout,
            int instanceIdleSeconds) {
        this.executable = executable;
        this.mdlHome = mdlHome;
        this.profile = profile;
        this.dataSource = dataSource;
        this.timeoutSeconds = timeout;
        this.instanceIdleSeconds = instanceIdleSeconds;
    }

    public String executable() {
        return executable == null || executable.isBlank() ? DEFAULT_EXECUTABLE : executable;
    }

    /**
     * Resolves the wren executable for the current platform. An explicitly configured value (anything
     * but the default) is used verbatim. The default is first probed at well-known install locations
     * — so Linux deployments work out of the box after a system or user-level {@code pip install} —
     * and only then falls back to PATH resolution by the process launcher, whose failure is wrapped
     * into {@link #unavailableMessage} by the callers.
     */
    public String resolveExecutable() {
        String exe = executable();
        if (!DEFAULT_EXECUTABLE.equals(exe)) {
            return exe;
        }
        String fileName = windows() ? "wren.exe" : "wren";
        for (Path dir : candidateDirs()) {
            Path candidate = dir.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return exe;
    }

    /** os.name based platform check; non-private so tests can pin the platform. */
    boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Well-known install locations probed before PATH. Empty on Windows — venv layouts vary, so
     * Windows dev machines are expected to set {@code dataagent.wren.executable} explicitly.
     */
    List<Path> candidateDirs() {
        if (windows()) {
            return List.of();
        }
        return List.of(
                Paths.get(System.getProperty("user.home"), ".local", "bin"),
                Paths.get("/usr/local/bin"),
                Paths.get("/usr/bin"));
    }

    /**
     * Cross-platform installation hint shared by every spawn site (parse-types/validate/build CLI
     * and the {@code serve mcp} instance pool): the raw OS error alone sends operators hunting, so
     * callers surface this guidance together with the original failure. Written in Simplified
     * Chinese per project convention (LLM/user-facing text).
     */
    public static String unavailableMessage(String executable, String cause) {
        return "wren 可执行文件「"
                + executable
                + "」无法启动。wren CLI 是语义建模、发布与 wren 通道查询的必需前置（Python ≥ 3.11）。"
                + "Linux 安装：pip install 'wrenai[mysql,mcp]' 'mcp<2'，"
                + "wren 位于 ~/.local/bin、/usr/local/bin、/usr/bin 或 PATH 时自动识别，无需额外配置；"
                + "Windows 请设置 dataagent.wren.executable（环境变量 DATAAGENT_WREN_EXECUTABLE）"
                + "指向 wren.exe 的绝对路径。原始错误："
                + cause;
    }

    /** Blank = auto-infer from the resolved executable location. */
    public String skillsDir() {
        return skillsDir == null ? "" : skillsDir.trim();
    }

    /** Test/programmatic hook; Spring binds the field via {@code @Value} in production. */
    public void setSkillsDir(String skillsDir) {
        this.skillsDir = skillsDir == null ? "" : skillsDir.trim();
    }

    public String profile() {
        return profile == null || profile.isBlank() ? "dataagent" : profile;
    }

    public String dataSource() {
        return dataSource == null || dataSource.isBlank() ? "mysql" : dataSource;
    }

    public Duration timeout() {
        return Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    /** Root directory holding one generated MDL project per knowledge base. */
    public Path mdlRoot() {
        if (mdlHome == null || mdlHome.isBlank()) {
            return Paths.get(System.getProperty("user.home"), ".agentscope", "dataagent", "mdl");
        }
        return Paths.get(mdlHome).toAbsolutePath().normalize();
    }

    public Duration instanceIdle() {
        return Duration.ofSeconds(Math.max(1, instanceIdleSeconds));
    }

    /**
     * Platform-managed {@code WREN_HOME} for the runtime {@code serve mcp} subprocesses. Kept
     * apart from the operator's {@code ~/.wren} so the platform fully controls the generated
     * {@code profiles.yml} (registered connection profile, specs/010 M3).
     */
    public Path wrenHome() {
        return mdlRoot().resolve(".wren");
    }

    /** Root of a single group's MDL workspace: {@code <mdlRoot>/<groupId>}. */
    public Path groupRoot(String groupId) {
        if (groupId == null || !groupId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new DatasetException(
                    "Invalid knowledge base id for MDL storage: " + groupId, 400);
        }
        return mdlRoot().resolve(groupId);
    }
}
