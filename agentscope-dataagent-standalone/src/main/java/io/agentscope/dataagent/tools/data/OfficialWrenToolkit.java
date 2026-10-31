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
import io.agentscope.dataagent.dataset.FactQueryWindowPolicy;
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
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Permission-only argv adapter to the installed official CLI; never implements Wren operations. */
public final class OfficialWrenToolkit {
    private final Map<String, BoundConfig> boundConfigs = new ConcurrentHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(OfficialWrenToolkit.class);
    private final WrenProjectAccess access;
    private final WrenCli cli;
    private final WrenProperties props;
    private final MdlPublishService publish;
    private final ModelingWorkflowStore checks;
    private final FactQueryWindowPolicy windowPolicy;
    private final boolean skillsMounted;
    private final java.util.function.Function<String, Set<String>> uploadTables;

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
        this(access, cli, props, publish, checks, null);
    }

    public OfficialWrenToolkit(
            WrenProjectAccess access,
            WrenCli cli,
            WrenProperties props,
            MdlPublishService publish,
            ModelingWorkflowStore checks,
            FactQueryWindowPolicy windowPolicy) {
        this(access, cli, props, publish, checks, windowPolicy, false);
    }

    /**
     * @param skillsMounted when the official skills are mounted on the agent, the CLI skills
     *     channel is closed so the same SKILL.md is not loaded twice per session
     */
    public OfficialWrenToolkit(
            WrenProjectAccess access,
            WrenCli cli,
            WrenProperties props,
            MdlPublishService publish,
            ModelingWorkflowStore checks,
            FactQueryWindowPolicy windowPolicy,
            boolean skillsMounted) {
        this(access, cli, props, publish, checks, windowPolicy, skillsMounted, null);
    }

    /**
     * @param uploadTables model names of uploaded tables per group; every other referenced table
     *     is treated as an external source and gets the probe guard. {@code null} disables it.
     */
    public OfficialWrenToolkit(
            WrenProjectAccess access,
            WrenCli cli,
            WrenProperties props,
            MdlPublishService publish,
            ModelingWorkflowStore checks,
            FactQueryWindowPolicy windowPolicy,
            boolean skillsMounted,
            java.util.function.Function<String, Set<String>> uploadTables) {
        this.access = access;
        this.cli = cli;
        this.props = props;
        this.publish = publish;
        this.checks = checks;
        this.windowPolicy = windowPolicy;
        this.skillsMounted = skillsMounted;
        this.uploadTables = uploadTables;
    }

    @Tool(
            name = "wren_cli",
            description =
                    """
                    在当前会话已授权的 Wren 草稿工程执行官方 CLI。arguments 是可执行文件后面的参数数组，不是 shell 文本。
                    可用命令及选项（无需再查 --help）：
                    context show [-o json|summary]；context validate [--strict] [--level 级别]；context build；context instructions；
                    query --sql "SELECT ..." [--limit N] [-o table|json|csv]；dry-plan --sql "SELECT ..."；dry-run --sql "SELECT ..."；
                    query 默认 table 输出可读日期时间；-o json 会把日期时间输出为毫秒时间戳，查询日期时间时用默认输出，确需 json 时在 SQL 中用 DATE_FORMAT 转为字符串，不要为换算格式重复查询；
                    memory index --no-seed；memory recall -q "问题" [-l N] [-o json]；memory status/check/describe/list/fetch；
                    utils parse-type --type 类型 / parse-types --input 工程内文件；技能挂载不可用时才用 skills list/get。
                    基础语义建模阶段不开放 cube 命令。SQL 中使用模型名（models/<模型>/metadata.yml 的 name），不要使用 table_reference 中的物理库表名。每轮建模最多执行 10 条 query；SQL 禁止 CROSS JOIN 和 FROM 后逗号并列多表的笛卡尔积，多表必须 JOIN … ON。
                    命令在同一知识库上逐条排队执行：不要在同一轮同时发多条；数据探查尽量合并为一条 SQL，一次核对多项。
                    使用官方技能决定步骤和文件内容。工程/MDL/连接/记忆路径由服务端绑定，不能切换 profile 或指定其他路径。
                    SQL 仅允许单条只读 SELECT/WITH，参数契约遵循安装版官方 CLI。
                    基础语义建模不开放 memory store：问答对只在用户逐题确认后由平台保存，用户问题写入 knowledge/questions/<ID>.yml。
                    返回官方退出码和原始输出；构建草稿不会发布或替换线上版本。\
                    """)
    public String execute(
            RuntimeContext context,
            @ToolParam(name = "arguments", description = "官方 wren 参数数组，每个参数作为独立字符串")
                    List<String> arguments) {
        try {
            Project p = access.resolve(context);
            // Argument, Cube and time-window checks run before the project lock: refusals need no
            // lock and the window gate's model call must not hold other calls on this project.
            List<String> command = command(p, arguments);
            refuseQueryInQuestionTurn(context, command);
            // The external-table refusal needs no model call, so it runs before the window gate.
            refuseExternalQuery(p, command);
            validateQueryBoundary(context, p, command);
            reserveQuery(context, p, command);
            String op = operation(command);
            long waitingAt = System.nanoTime();
            return access.locked(
                    p,
                    () -> {
                        timed(context, "wren_cli.lock_wait", op, waitingAt, "SUCCESS");
                        access.checkTree(p);
                        String autoBuilt = "";
                        boolean missing = !Files.isRegularFile(p.root().resolve("target/mdl.json"));
                        if (needsCompiledMdl(command) && (missing || compiledMdlStale(p.root()))) {
                            // Query-class commands need the current draft compiled; build here so
                            // the agent never runs a separate context build (each CLI run costs
                            // seconds) and never plans against an outdated MDL.
                            List<String> buildArgs = List.of("context", "build");
                            long buildingAt = System.nanoTime();
                            WrenCli.Result built =
                                    cli.run(p.root(), props.timeout(), command(p, buildArgs));
                            timed(context, "wren_cli.auto_build", op, buildingAt, status(built));
                            String buildWarning = syncDisplayState(p, buildArgs, built);
                            if (!built.ok())
                                return "error: 草稿尚未编译，平台自动执行 context build 失败，未执行 "
                                        + op
                                        + buildWarning
                                        + "\n"
                                        + built.output();
                            autoBuilt =
                                    missing
                                            ? "\n（草稿此前未编译，平台已先自动执行 context build）"
                                            : "\n（草稿已修改，平台已先自动重新执行 context build）";
                        }
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
                                long bindingAt = System.nanoTime();
                                BoundConfig known = boundConfigs.get(p.groupId());
                                if (known != null && known.matches(originalConfig, profile)) {
                                    projectConfig(p, known.bound());
                                    timed(
                                            context,
                                            "wren_cli.bind_profile",
                                            op,
                                            bindingAt,
                                            "CACHED");
                                } else {
                                    WrenCli.Result pinned =
                                            cli.run(
                                                    p.root(),
                                                    props.timeout(),
                                                    List.of("context", "set-profile", profile));
                                    timed(
                                            context,
                                            "wren_cli.bind_profile",
                                            op,
                                            bindingAt,
                                            status(pinned));
                                    if (!pinned.ok())
                                        return "error: 当前知识库连接绑定失败，未执行查询\n" + pinned.output();
                                    boundConfigs.put(
                                            p.groupId(),
                                            new BoundConfig(
                                                    originalConfig,
                                                    profile,
                                                    projectConfig(p, null)));
                                }
                            }
                            log.info(
                                    "[official-wren] owner={} group={} operation={} project={}",
                                    p.ownerId(),
                                    p.groupId(),
                                    operation(command),
                                    p.root());
                            long executingAt = System.nanoTime();
                            WrenCli.Result result = cli.run(p.root(), props.timeout(), command);
                            timed(context, "wren_cli.execute", op, executingAt, status(result));
                            if (result.ok() && isStore(arguments)) access.changed(p);
                            String stateWarning = syncDisplayState(p, arguments, result);
                            log.info(
                                    "[official-wren] group={} operation={} exitCode={}"
                                            + " executeMs={}",
                                    p.groupId(),
                                    operation(command),
                                    result.exitCode(),
                                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                            System.nanoTime() - executingAt));
                            return "Wren 官方 CLI｜知识库："
                                    + p.name()
                                    + "｜草稿工程：/project\n退出码："
                                    + result.exitCode()
                                    + autoBuilt
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

    /**
     * What the official {@code set-profile} wrote for this project and profile. Each query restores
     * the project afterwards, so without this every query starts a CLI process just to bind again.
     */
    private record BoundConfig(String original, String profile, String bound) {
        boolean matches(String currentConfig, String currentProfile) {
            return original.equals(currentConfig) && profile.equals(currentProfile);
        }
    }

    /** Splits a wren_cli call into lock wait, profile binding and CLI execution in timing logs. */
    private static void timed(
            RuntimeContext context, String phase, String op, long started, String status) {
        io.agentscope.dataagent.web.middleware.ExecutionTimingMiddleware.record(
                context, phase, op, started, status);
    }

    /** Commands that read target/mdl.json and fail when the draft has never been built. */
    static boolean needsCompiledMdl(List<String> command) {
        if (command.isEmpty() || command.contains("--help")) return false;
        String top = command.get(0);
        return Set.of("query", "dry-plan", "dry-run").contains(top)
                || (top.equals("memory")
                        && command.size() > 1
                        && command.get(1).equals("describe"));
    }

    private static String status(WrenCli.Result result) {
        if (result.ok()) return "SUCCESS";
        return result.exitCode() == -1 && result.output().contains("执行超时") ? "TIMEOUT" : "ERROR";
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
            List<String> command = command(p, values.stream().map(String.class::cast).toList());
            // A store refused by the time window must fail before the user is asked to approve it.
            validateQueryBoundary(context, p, command);
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
        if (skillsMounted && top.equals("skills")) {
            throw new DatasetException(
                    "官方技能已挂载，请用"
                        + " load_skill_through_path(skillId=\"<技能名>_filesystem-wren_skills_content\","
                        + " path=\"SKILL.md\" 或 \"references/文件名.md\") 读取；本会话已加载的技能直接复用，不要重复获取",
                    400);
        }
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
            } else
                throw new DatasetException(
                        "未开放的官方选项或路径："
                                + arg
                                + "；"
                                + op
                                + " 可用选项："
                                + (flags.size() + valued.size() == 1
                                        ? "无（仅 --help）"
                                        : String.join(
                                                " ",
                                                new java.util.TreeSet<>(
                                                        java.util.stream.Stream.concat(
                                                                        flags.stream(),
                                                                        valued.stream())
                                                                .toList()))),
                        403);
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

    /** Data-reading queries allowed per modeling turn; probes and sample checks share it. */
    public static final int MAX_QUERIES_PER_TURN = 10;

    /**
     * Turn-scoped hard cap on data-reading queries. The RuntimeContext is rebuilt every turn, so
     * the count never carries over; only commands that pass every other check are counted.
     */
    private static void reserveQuery(
            RuntimeContext context, Project project, List<String> command) {
        if (context == null || !command.get(0).equals("query") || command.contains("--help"))
            return;
        synchronized (context) {
            String key = "wrenCli.queries." + project.groupId();
            int used = context.get(key) instanceof Integer count ? count : 0;
            if (used >= MAX_QUERIES_PER_TURN)
                throw new DatasetException(
                        "本轮建模已执行 "
                                + used
                                + " 条查询，达到单轮上限，未执行此查询。请基于已有结果继续完成建模：发现的数据问题写入描述或 caveat，"
                                + "未能验证的样例说明尚未验证，不要改写成其它查询重试。",
                        429);
            context.put(key, used + 1);
        }
    }

    private static final java.util.regex.Pattern TABLE_REF =
            java.util.regex.Pattern.compile(
                    "(?i)\\b(?:FROM|JOIN)\\s+[\\\"`]?([\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*)");

    /**
     * True when a build input (model, view, relationship or knowledge file) is newer than the
     * compiled draft, so query-class commands rebuild instead of using an old MDL. wren_project.yml
     * is left out: every query rewrites it to pin the connection profile, which no build reads.
     */
    static boolean compiledMdlStale(java.nio.file.Path root) {
        java.nio.file.Path compiled = root.resolve("target/mdl.json");
        try {
            var builtAt = Files.getLastModifiedTime(compiled);
            for (String input : List.of("relationships.yml")) {
                java.nio.file.Path file = root.resolve(input);
                if (Files.isRegularFile(file)
                        && Files.getLastModifiedTime(file).compareTo(builtAt) > 0) return true;
            }
            for (String dir : List.of("models", "views", "knowledge")) {
                java.nio.file.Path folder = root.resolve(dir);
                if (!Files.isDirectory(folder)) continue;
                try (var files = Files.walk(folder)) {
                    if (files.filter(Files::isRegularFile)
                            .anyMatch(
                                    f -> {
                                        try {
                                            return Files.getLastModifiedTime(f).compareTo(builtAt)
                                                    > 0;
                                        } catch (java.io.IOException e) {
                                            return true;
                                        }
                                    })) return true;
                }
            }
            return false;
        } catch (java.io.IOException e) {
            return true;
        }
    }

    /**
     * Trial questions are checked by reading the SQL, not by running it (2026-10-25): the
     * assistant plans the SQL with dry-plan, which never touches the database, and the user
     * confirms it in the trial panel.
     */
    static void refuseQueryInQuestionTurn(RuntimeContext context, List<String> command) {
        if (context == null || command.isEmpty() || !command.get(0).equals("query")) return;
        var purpose = context.get(io.agentscope.dataagent.dataset.ModelingTurnPurpose.class);
        if (purpose != null && purpose.question())
            throw new DatasetException(
                    "试问不查询数据，未执行查询。请用 dry-plan 检查 SQL 能否生成，写入问题文件后由用户在试问面板核对。", 403);
    }

    /**
     * External-source tables are never queried while modeling (rule violated repeatedly by
     * prompt alone, so it is enforced here): basic modeling reuses sampling and metadata, and
     * trial questions are only planned with dry-plan. Uploaded tables keep their limited probes.
     */
    private void refuseExternalQuery(Project project, List<String> command) {
        if (uploadTables == null || command.isEmpty() || !command.get(0).equals("query")) return;
        String sql = sqlOf(command);
        if (sql == null) return;
        String code = sql.replaceAll("(?s)'(?:''|[^'])*'|/\\*.*?\\*/|--[^\\r\\n]*", " ");
        Set<String> uploads = new java.util.HashSet<>();
        for (String name : uploadTables.apply(project.groupId())) uploads.add(name.toLowerCase());
        Set<String> external = new java.util.TreeSet<>();
        var refs = TABLE_REF.matcher(code);
        while (refs.find()) {
            String name = refs.group(1);
            name = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
            if (!uploads.contains(name)) external.add(name);
        }
        if (external.isEmpty()) return;
        throw new DatasetException(
                "基础建模只使用采样资料与元数据，不查询外部数据源表（"
                        + String.join("、", external)
                        + "），未执行查询。候选关系用 request_user_confirmation 请用户确定后再写入；无法验证的唯一性、空值或样例写为未确认并记入"
                        + " caveat；试问 SQL 用 dry-plan 检查，由用户在试问面板核对。",
                403);
    }

    private static String sqlOf(List<String> command) {
        for (int i = 0; i + 1 < command.size(); i++)
            if (Set.of("--sql", "-s").contains(command.get(i))) return command.get(i + 1);
        return null;
    }

    private void validateQueryBoundary(
            RuntimeContext context, Project project, List<String> command) {
        if (command.isEmpty() || command.contains("--help")) return;
        // spec090: Q&A pairs are written only by the platform after the user confirms a question.
        if (command.get(0).equals("memory") && command.size() > 1 && command.get(1).equals("store"))
            throw new IllegalArgumentException(
                    "基础语义建模不生成问答对，未执行保存。问答由用户问题确认流程保存：请把问题与 SQL 写入"
                            + " knowledge/questions/<ID>.yml，由用户在试问面板确认正确。");
        if (command.get(0).equals("cube"))
            throw new IllegalArgumentException("当前语义建模阶段不执行 Cube 构建；请在指标建模模块中单独创建和验证 Cube");
        if (windowPolicy == null) return;
        String op = command.get(0);
        boolean sqlOperation =
                // dry-plan only compiles against the MDL and reads no data, so it needs no window.
                Set.of("query", "dry-run").contains(op)
                        || (op.equals("memory")
                                && command.size() > 1
                                && command.get(1).equals("store"));
        if (!sqlOperation) return;
        for (int i = 0; i + 1 < command.size(); i++) {
            if (Set.of("--sql", "-s").contains(command.get(i))) {
                windowPolicy.requireAllowed(
                        context,
                        project.groupId(),
                        command.get(i + 1),
                        "wren_cli " + operation(command));
                return;
            }
        }
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
        if (CROSS_JOIN.matcher(tokens).find() || IMPLICIT_JOIN.matcher(tokens).find()) {
            throw new DatasetException(
                    "禁止 CROSS JOIN 或 FROM 后用逗号并列多表的隐式连接（笛卡尔积），未执行查询；请用 JOIN … ON"
                            + " 写明连接条件，或分别对单表统计后再比较",
                    400);
        }
    }

    // Lexical guards on the literal/comment-stripped text; quoted identifiers are blanked there,
    // so a table name may be absent between FROM and the comma.
    private static final java.util.regex.Pattern CROSS_JOIN =
            java.util.regex.Pattern.compile("(?i)\\bCROSS\\s+JOIN\\b");
    private static final String NOT_KEYWORD =
            "(?!(?:WHERE|(?:GROUP|ORDER)\\s+BY|BY|LIMIT|OFFSET|HAVING|JOIN|LEFT|RIGHT|INNER|FULL|OUTER|CROSS"
                + "|UNION|ON|USING|WINDOW|QUALIFY)\\b)";
    private static final java.util.regex.Pattern IMPLICIT_JOIN =
            java.util.regex.Pattern.compile(
                    "(?i)\\bFROM\\s+(?:"
                            + NOT_KEYWORD
                            + "[^\\s,()]+\\s*)?(?:(?:AS\\s+)?"
                            + NOT_KEYWORD
                            + "[^\\s,()]+\\s*)?,");
}
