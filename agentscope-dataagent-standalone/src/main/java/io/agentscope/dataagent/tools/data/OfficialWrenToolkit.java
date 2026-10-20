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
package io.agentscope.dataagent.tools.data;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.ModelingWorkflowStore;
import io.agentscope.dataagent.dataset.WrenCli;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import io.agentscope.dataagent.dataset.WrenProjectAccess.Project;
import io.agentscope.dataagent.dataset.WrenProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Permission-only argv adapter to the installed official CLI; never implements Wren operations. */
public final class OfficialWrenToolkit {
    private static final Logger log = LoggerFactory.getLogger(OfficialWrenToolkit.class);
    private final WrenProjectAccess access;
    private final WrenCli cli;
    private final WrenProperties props;
    private final MdlPublishService publish;
    private final ModelingWorkflowStore checks;

    public OfficialWrenToolkit(
            WrenProjectAccess access,
            WrenCli cli,
            WrenProperties props,
            MdlPublishService publish) {
        this(access, cli, props, publish, null);
    }

    public OfficialWrenToolkit(
            WrenProjectAccess access,
            WrenCli cli,
            WrenProperties props,
            MdlPublishService publish,
            ModelingWorkflowStore checks) {
        this.access = access;
        this.cli = cli;
        this.props = props;
        this.publish = publish;
        this.checks = checks;
    }

    @Tool(
            name = "wren_cli",
            description =
                    """
                    在当前会话已授权的 Wren 草稿工程执行官方 CLI。arguments 是可执行文件后面的参数数组，不是 shell 文本。
                    例如 ["context","show","-o","json"]、["context","validate","--strict"]、["context","build"]、
                    ["dry-plan","--sql","SELECT ..."]、["query","--sql","SELECT ...","--limit","10"]、
                    ["memory","status"]、["memory","recall","-q","问题","-o","json"]。
                    可用命令为 context show/validate/build/instructions、skills list/get、utils parse-type/parse-types、
                    query、dry-plan、dry-run、cube list/describe/query、memory status/describe/fetch/recall/index/store/list/check，以及帮助和版本。
                    使用官方技能决定步骤和文件内容。工程/MDL/连接/记忆路径由服务端绑定，不能切换 profile 或指定其他路径。
                    SQL 仅允许单条只读 SELECT/WITH，参数契约遵循安装版官方 CLI。
                    memory store 保存工程示例前由原生确认卡请求授权；这不改变问数的点赞保存策略。
                    返回官方退出码和原始输出；构建草稿不会发布或替换线上版本。\
                    """)
    public String execute(
            RuntimeContext context,
            @ToolParam(name = "arguments", description = "官方 wren 参数数组，每个参数作为独立字符串")
                    List<String> arguments) {
        try {
            Project p = access.resolve(context);
            long waiting = System.nanoTime();
            return access.locked(
                    p,
                    () -> {
                        log.info(
                                "[wren-timing] operation=modeling_cli group={}"
                                        + " phase=workspace_lock_wait elapsedMs={}",
                                p.groupId(),
                                (System.nanoTime() - waiting) / 1_000_000.0);
                        access.checkTree(p);
                        List<String> command = command(p, arguments);
                        boolean bindConnection =
                                !arguments.contains("--help")
                                        && (command.get(0).equals("query")
                                                || command.get(0).equals("dry-run")
                                                || (command.get(0).equals("cube")
                                                        && command.size() > 1
                                                        && command.get(1).equals("query")
                                                        && !command.contains("--sql-only")));
                        String originalConfig = bindConnection ? projectConfig(p, null) : null;
                        try {
                            if (bindConnection) {
                                String profile = publish.draftCliProfile(p.ownerId(), p.groupId());
                                WrenCli.Result pinned =
                                        cli.run(
                                                p.root(),
                                                props.timeout(),
                                                List.of("context", "set-profile", profile));
                                if (!pinned.ok())
                                    return "error: 当前知识库连接绑定失败，未执行查询\n" + pinned.output();
                            }
                            log.info(
                                    "[official-wren] owner={} group={} operation={} project={}",
                                    p.ownerId(),
                                    p.groupId(),
                                    operation(command),
                                    p.root());
                            WrenCli.Result result = cli.run(p.root(), props.timeout(), command);
                            if (result.ok() && isStore(arguments)) access.changed(p);
                            String stateWarning = syncDisplayState(p, arguments, result);
                            log.info(
                                    "[official-wren] group={} operation={} exitCode={}",
                                    p.groupId(),
                                    operation(command),
                                    result.exitCode());
                            return "Wren 官方 CLI｜知识库："
                                    + p.name()
                                    + "｜草稿工程：/project\n退出码："
                                    + result.exitCode()
                                    + stateWarning
                                    + "\n"
                                    + result.output();
                        } finally {
                            if (originalConfig != null) projectConfig(p, originalConfig);
                        }
                    });
        } catch (RuntimeException e) {
            return "error: " + e.getMessage();
        }
    }

    /** Keeps temporary connection pinning from changing the platform-owned project or UI hash. */
    private String projectConfig(Project project, String original) {
        try {
            var file = project.root().resolve("wren_project.yml");
            if (original == null) return Files.readString(file, StandardCharsets.UTF_8);
            Files.writeString(file, original, StandardCharsets.UTF_8);
            return original;
        } catch (IOException e) {
            throw new DatasetException("绑定连接时读取或恢复平台工程配置失败", e);
        }
    }

    /** Mirrors official results into existing UI guidance; publication still checks the draft. */
    private String syncDisplayState(Project project, List<String> args, WrenCli.Result result) {
        if (checks == null
                || args.size() < 2
                || !args.get(0).equals("context")
                || args.contains("--help")
                || !(args.get(1).equals("build")
                        || (args.get(1).equals("validate") && !result.ok()))) return "";
        try {
            checks.record(
                    project.groupId(),
                    new MdlPublishService.MdlValidation(
                            result.ok(),
                            result.ok()
                                    ? List.of()
                                    : List.of(
                                            new MdlPublishService.MdlIssue(
                                                    "error",
                                                    result.output(),
                                                    "wren_cli " + args.get(1))),
                            result.output(),
                            List.of()));
            return "";
        } catch (RuntimeException e) {
            log.warn(
                    "[official-wren] group={} UI check state could not be updated: {}",
                    project.groupId(),
                    e.getMessage());
            return "\n页面工程状态同步失败，请重新检查后发布：" + e.getMessage();
        }
    }

    public String preflight(RuntimeContext context, ToolUseBlock call) {
        try {
            Project p = access.resolve(context);
            access.checkTree(p);
            Object input = call.getInput().get("arguments");
            if (!(input instanceof List<?> values)
                    || values.stream().anyMatch(v -> !(v instanceof String))) {
                return "arguments 必须为字符串数组";
            }
            command(p, values.stream().map(String.class::cast).toList());
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    public static boolean requiresConfirmation(ToolUseBlock call) {
        return call.getName().equals("wren_cli")
                && call.getInput() != null
                && call.getInput().get("arguments") instanceof List<?> values
                && values.size() >= 2
                && !values.contains("--help")
                && values.get(0).equals("memory")
                && (values.get(1).equals("store")
                        || (values.get(1).equals("index") && !values.contains("--no-seed")));
    }

    private static boolean isStore(List<String> args) {
        return args.size() >= 2
                && !args.contains("--help")
                && args.get(0).equals("memory")
                && (args.get(1).equals("store")
                        || (args.get(1).equals("index") && !args.contains("--no-seed")));
    }

    private static String operation(List<String> args) {
        return args.get(0)
                + (args.size() > 1 && !args.get(1).startsWith("-") ? " " + args.get(1) : "");
    }

    /** Only option grammar and project confinement are checked; official CLI validates content. */
    List<String> command(Project p, List<String> args) {
        if (args == null
                || args.isEmpty()
                || args.size() > 64
                || args.stream()
                        .anyMatch(s -> s == null || s.indexOf('\0') >= 0 || s.length() > 65536)) {
            throw new DatasetException("arguments 必须为有效官方 CLI 参数数组", 400);
        }
        if (args.equals(List.of("--help"))
                || args.equals(List.of("--version"))
                || args.equals(List.of("version"))) {
            return args;
        }
        String top = args.get(0);
        boolean grouped =
                Set.of("context", "memory", "skills", "utils", "docs", "cube").contains(top);
        if (grouped && args.size() == 2 && args.get(1).equals("--help")) return args;
        String sub = grouped && args.size() > 1 ? args.get(1) : "";
        String op = grouped ? top + " " + sub : top;
        Set<String> flags = new HashSet<>(Set.of("--help"));
        Set<String> valued = new HashSet<>();
        boolean positional = false;
        switch (op) {
            case "context show" -> valued.addAll(Set.of("-o", "--output"));
            case "context validate" -> {
                flags.addAll(Set.of("--strict", "--verbose", "-v"));
                valued.add("--level");
            }
            case "context build" -> flags.addAll(Set.of("--validate", "--no-validate"));
            case "cube list" -> {}
            case "cube describe" -> positional = true;
            case "cube query" -> {
                flags.add("--sql-only");
                valued.addAll(
                        Set.of(
                                "--cube",
                                "-c",
                                "--measures",
                                "--dimensions",
                                "--time-dimension",
                                "--filter",
                                "--order-by",
                                "--limit",
                                "-l",
                                "--offset",
                                "--from",
                                "--output",
                                "-o"));
            }
            case "context instructions",
                    "skills list",
                    "memory status",
                    "memory describe",
                    "memory check" -> {}
            case "skills get" -> {
                flags.add("--full");
                valued.add("--script");
                positional = true;
            }
            case "utils parse-type" -> valued.addAll(Set.of("--type", "-t", "--dialect", "-d"));
            case "utils parse-types" -> {
                valued.addAll(Set.of("--input", "-i", "--dialect", "-d", "--type-field"));
                flags.add("--strict");
            }
            case "memory index" ->
                    flags.addAll(
                            Set.of(
                                    "--no-seed",
                                    "--no-queries",
                                    "--instructions",
                                    "--no-instructions"));
            case "memory store" ->
                    valued.addAll(Set.of("--nl", "--sql", "--datasource", "-d", "--tags"));
            case "memory recall" ->
                    valued.addAll(
                            Set.of(
                                    "--query",
                                    "-q",
                                    "--limit",
                                    "-l",
                                    "--datasource",
                                    "-d",
                                    "--output",
                                    "-o"));
            case "memory fetch" ->
                    valued.addAll(
                            Set.of(
                                    "--query",
                                    "-q",
                                    "--limit",
                                    "-l",
                                    "--type",
                                    "-t",
                                    "--model",
                                    "--threshold",
                                    "--output",
                                    "-o"));
            case "memory list" ->
                    valued.addAll(
                            Set.of(
                                    "--source",
                                    "-s",
                                    "--limit",
                                    "-n",
                                    "--offset",
                                    "--output",
                                    "-o"));
            case "query" -> {
                flags.addAll(Set.of("--quiet", "-q"));
                valued.addAll(Set.of("--sql", "-s", "--limit", "-l", "--output", "-o"));
            }
            case "dry-plan", "dry-run" -> valued.addAll(Set.of("--sql", "-s"));
            case "docs mdl", "docs sql-functions" -> {}
            default -> throw new DatasetException("未开放的 Wren 命令；不能切换工程、连接或发布范围", 403);
        }
        List<String> result = new ArrayList<>(args.subList(0, grouped ? 2 : 1));
        Map<String, String> seen = new HashMap<>();
        for (int i = grouped ? 2 : 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (flags.contains(arg)) {
                result.add(arg);
                continue;
            }
            if (valued.contains(arg)) {
                if (++i >= args.size()) throw new DatasetException("官方选项缺少参数：" + arg, 400);
                String value = args.get(i);
                if (arg.equals("--input") || arg.equals("-i") || arg.equals("--from")) {
                    value = access.path(p, value, false).toString();
                }
                if (arg.equals("--script") && !value.matches("[A-Za-z0-9_./-]+"))
                    throw new DatasetException("无效技能资源名", 400);
                if (arg.equals("--script") && (value.contains("..") || value.startsWith("/")))
                    throw new DatasetException("技能资源路径越界", 403);
                if (Set.of("--sql", "-s").contains(arg) && !op.equals("memory list")) {
                    requireReadOnly(value);
                }
                if (Set.of("--limit", "-l", "-n", "--threshold", "--offset").contains(arg)) {
                    try {
                        int limit = Integer.parseInt(value);
                        if (limit < (Set.of("--offset", "--limit", "-l").contains(arg) ? 0 : 1)
                                || (!arg.equals("--threshold")
                                        && !arg.equals("--offset")
                                        && limit > 10000)) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        throw new DatasetException("数值参数超出允许范围：" + arg, 400);
                    }
                }
                if (seen.put(arg, value) != null && !Set.of("--filter", "--order-by").contains(arg))
                    throw new DatasetException("不能重复覆盖官方选项：" + arg, 400);
                result.add(arg);
                result.add(value);
            } else if (positional && !arg.startsWith("-") && arg.matches("[A-Za-z0-9_-]+")) {
                result.add(arg);
                positional = false;
            } else throw new DatasetException("未开放的官方选项或路径：" + arg, 403);
        }
        if (top.equals("context")) result.addAll(List.of("--path", p.root().toString()));
        if (op.equals("utils parse-types")
                && !result.contains("--help")
                && !seen.containsKey("--input")
                && !seen.containsKey("-i")) {
            throw new DatasetException("批量类型转换需要当前工程内的 --input 文件，不读取宿主标准输入", 400);
        }
        if (top.equals("memory") && !sub.equals("describe"))
            result.addAll(List.of("--path", access.memoryPath(p).toString()));
        if (top.equals("memory") && Set.of("index", "describe", "fetch").contains(sub)) {
            result.addAll(List.of("--mdl", p.root().resolve("target/mdl.json").toString()));
        }
        if (Set.of("query", "dry-plan", "dry-run", "cube").contains(top)) {
            result.addAll(List.of("--mdl", p.root().resolve("target/mdl.json").toString()));
        }
        if (top.equals("dry-plan")) result.addAll(List.of("--datasource", props.dataSource()));
        if (top.equals("query") && !seen.containsKey("--limit") && !seen.containsKey("-l"))
            result.addAll(List.of("--limit", "1000"));
        return result;
    }

    public static void requireReadOnly(String sql) {
        // Lexical security policy only; Wren remains the SQL parser and semantic compiler.
        String tokens =
                sql.replaceAll(
                                "(?s)'(?:''|[^'])*'|\\\"(?:\\\"\\\"|[^\\\"])*\\\"|\u0060[^\u0060]*\u0060|/\\*.*?\\*/|--[^\\r"
                                    + "\\n"
                                    + "]*",
                                " ")
                        .strip();
        if (!tokens.matches("(?is)^(SELECT|WITH)\\b.*")
                || tokens.matches(
                        "(?is).*\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|CALL|INTO|LOAD_FILE|SLEEP)\\b.*")
                || tokens.contains(";")) {
            throw new DatasetException("SQL 仅允许单条只读 SELECT/WITH", 400);
        }
    }
}
