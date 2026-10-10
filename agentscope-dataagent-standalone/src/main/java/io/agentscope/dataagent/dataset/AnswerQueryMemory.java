package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.session.SessionTurnParser.TurnEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Stores explicit answer feedback and builds isolated official Wren query memory. */
@Service
public class AnswerQueryMemory {
    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeBaseOperations operations;

    private static final Logger log = LoggerFactory.getLogger(AnswerQueryMemory.class);
    private final WrenProperties properties;
    private final WrenCli cli;
    private final DatasetGroupService groups;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object[] locks =
            java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public AnswerQueryMemory(WrenProperties properties, WrenCli cli, DatasetGroupService groups) {
        this.properties = properties;
        this.cli = cli;
        this.groups = groups;
    }

    public record Pair(String groupId, String question, String sql, int limit) {
        public Pair(String groupId, String question, String sql) {
            this(groupId, question, sql, 1000);
        }

        public String memorySql() {
            return sql.stripTrailing() + "\nLIMIT " + limit;
        }
    }

    public record Feedback(String vote, List<Pair> pairs, String memoryStatus) {}

    public record State(Map<String, Feedback> answers, Map<String, String> projects) {}

    public record Result(String vote, int savedPairs, String message, String memoryStatus) {}

    public static String queryId(String groupId, String question, String sql) {
        return queryId(groupId, question, sql, 1000);
    }

    public static String queryId(String groupId, String question, String sql, int limit) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (groupId + "\n" + question + "\n" + sql + "\n" + limit)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Accepts only a final answer from the authenticated user's server-side transcript. */
    public Result feedback(
            String owner, String session, String answerId, String vote, List<TurnEntry> turns) {
        if (vote == null || !Set.of("UP", "DOWN").contains(vote))
            throw new DatasetException("反馈只能为 UP 或 DOWN", 400);
        List<Pair> candidates = candidates(owner, answerId, turns);
        synchronized (lock(owner)) {
            State old = read(owner);
            String key = queryId(session, answerId, "answer");
            Feedback previous = old.answers().get(key);
            if (previous != null
                    && previous.vote().equals(vote)
                    && !"FAILED".equals(previous.memoryStatus())) return result(previous);
            Map<String, Feedback> answers = new LinkedHashMap<>(old.answers());
            if ("DOWN".equals(vote)) {
                Feedback negative =
                        new Feedback(
                                vote,
                                previous == null ? List.of() : previous.pairs(),
                                previous == null ? "NONE" : previous.memoryStatus());
                answers.put(key, negative);
                write(owner, new State(answers, old.projects()));
                return result(negative);
            }
            if (previous != null && "SAVED".equals(previous.memoryStatus())) {
                Feedback liked = new Feedback(vote, previous.pairs(), "SAVED");
                answers.put(key, liked);
                write(owner, new State(answers, old.projects()));
                return result(liked);
            }
            Feedback pending =
                    new Feedback(vote, candidates, candidates.isEmpty() ? "NONE" : "FAILED");
            answers.put(key, pending);
            write(owner, new State(answers, old.projects()));
            try {
                for (Pair pair : candidates) {
                    java.util.function.Supplier<Void> save =
                            () -> {
                                Path project = project(owner, pair.groupId());
                                initialize(project);
                                store(project, pair.question(), pair.memorySql());
                                return null;
                            };
                    if (operations == null) save.get();
                    else operations.run(pair.groupId(), save);
                }
            } catch (DatasetException e) {
                return result(pending);
            }
            Feedback current =
                    new Feedback(vote, candidates, candidates.isEmpty() ? "NONE" : "SAVED");
            answers.put(key, current);
            write(owner, new State(answers, old.projects()));
            return result(current);
        }
    }

    public Result status(String owner, String session, String answerId) {
        synchronized (lock(owner)) {
            Feedback value = read(owner).answers().get(queryId(session, answerId, "answer"));
            return value == null ? new Result(null, 0, "尚未反馈", "NONE") : result(value);
        }
    }

    public String recall(String owner, String group, String question) {
        return recall(owner, group, question, List.of());
    }

    public String recall(String owner, String group, String question, List<String> confirmed) {
        groups.getGroup(owner, group);
        synchronized (lock(owner)) {
            Path project = project(owner, group);
            try {
                initialize(project);
                for (SqlExampleRecall.Example example : SqlExampleRecall.published(confirmed)) {
                    Path marker =
                            project.resolve(
                                    ".published-"
                                            + queryId(group, example.question(), example.sql()));
                    if (!Files.isRegularFile(marker)) {
                        store(project, example.question(), example.sql());
                        Files.writeString(marker, "stored", StandardCharsets.UTF_8);
                    }
                }
                WrenCli.Result status = cliRun(project, "status", memoryArgs(project, "status"));
                if (!status.ok()) return "召回不可用：官方 Wren 记忆状态读取失败，请检查 CLI 与记忆依赖。";
                String backend = backend(status.output());
                if (!hasExamples(project)) return "Wren 后端：" + backend + "\n当前知识库暂无可召回样例。";
                WrenCli.Result recalled =
                        cliRun(
                                project,
                                "recall",
                                memoryArgs(
                                        project,
                                        "recall",
                                        "-q",
                                        question,
                                        "--limit",
                                        "3",
                                        "--output",
                                        "json"));
                if (!recalled.ok())
                    return "Wren 后端：" + backend + "\n召回不可用：官方 Wren CLI 执行失败，请检查记忆依赖后重试。";
                List<SqlExampleRecall.Example> selected =
                        SqlExampleRecall.results(recalled.output());
                return "Wren 后端："
                        + backend
                        + "\n"
                        + (selected.isEmpty()
                                ? "官方召回成功，未找到匹配的业务问题—SQL 样例。"
                                : selected.stream()
                                        .map(SqlExampleRecall.Example::content)
                                        .collect(java.util.stream.Collectors.joining("\n\n")))
                        + "\n"
                        + "示例仅供参考，核对时间、口径和字段后重新查询；示例中的 LIMIT 转为 wren_run_sql 的 limit 参数，工具 SQL 中不写"
                        + " LIMIT。";
            } catch (Exception e) {
                return "召回不可用：官方 Wren 记忆初始化、同步或结果解析失败，请检查 CLI 与记忆依赖。";
            }
        }
    }

    private static String backend(String status) {
        var matcher = java.util.regex.Pattern.compile("(?im)^Backend:\\s*(\\S+)").matcher(status);
        return matcher.find() ? matcher.group(1) : "未知";
    }

    private boolean hasExamples(Path project) throws java.io.IOException {
        Path directory = project.resolve("knowledge/sql");
        if (!Files.isDirectory(directory)) return false;
        try (var paths = Files.list(directory)) {
            return paths.anyMatch(
                    path ->
                            Files.isRegularFile(path)
                                    && path.getFileName().toString().endsWith(".md"));
        }
    }

    List<Pair> candidates(String owner, String answerId, List<TurnEntry> turns) {
        int end = -1;
        for (int i = 0; i < turns.size(); i++)
            if (Objects.equals(answerId, turns.get(i).id())
                    && "ASSISTANT".equalsIgnoreCase(turns.get(i).role())
                    && turns.get(i).content() != null
                    && !turns.get(i).content().isBlank()) end = i;
        if (end < 0) throw new DatasetException("未找到可反馈的最终答案，请刷新会话后重试", 409);
        if (turns.get(end).content().contains("[错误]")
                || turns.get(end).content().contains("[error]"))
            throw new DatasetException("失败的回答不能保存为正确样例", 400);
        for (int i = end + 1;
                i < turns.size() && !"USER".equalsIgnoreCase(turns.get(i).role());
                i++) {
            if ("ASSISTANT".equalsIgnoreCase(turns.get(i).role())
                    || "TOOL".equalsIgnoreCase(turns.get(i).role()))
                throw new DatasetException("只能对最终答案反馈，不能对中间过程反馈", 400);
        }
        int start = end;
        while (start > 0 && !"USER".equalsIgnoreCase(turns.get(start).role())) start--;
        Map<String, Pair> successful = new LinkedHashMap<>();
        List<JsonNode> calls = new ArrayList<>();
        for (int i = start; i < end; i++) {
            TurnEntry turn = turns.get(i);
            if ("wren_run_sql".equals(turn.toolName()) && turn.toolInput() != null)
                calls.add(json(turn.toolInput()));
            if ("wren_run_sql".equals(turn.toolName())
                    && turn.toolResult() != null
                    && turn.toolResult().contains("## wren 语义查询结果")
                    && !turn.toolResult().startsWith("error:")) {
                for (JsonNode call : calls) {
                    String question = call.path("question").asText("").trim();
                    String sql = call.path("sql").asText("");
                    String queryType = call.path("query_type").asText("");
                    if (!"BUSINESS".equals(queryType) || !hasChinese(question) || sql.isBlank())
                        continue;
                    for (var group : groups.listGroups(owner)) {
                        int limit =
                                call.path("limit").isNumber() ? call.path("limit").asInt() : 1000;
                        if (limit < 1 || limit > 10000) continue;
                        String id = receiptId(group.getId(), question, sql, limit, queryType);
                        if (turn.toolResult().contains("答案查询编号：`" + id + "`")) {
                            successful.put(id, new Pair(group.getId(), question, sql, limit));
                        }
                    }
                }
            }
        }
        return successful.values().stream().distinct().toList();
    }

    public static boolean hasChinese(String question) {
        return question != null
                && question.codePoints()
                        .anyMatch(
                                c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }

    public static String receiptId(
            String group, String question, String sql, int limit, String queryType) {
        return queryId(group, question, sql + "\nquery_type=" + queryType, limit);
    }

    private Path project(String owner, String group) {
        groups.getGroup(owner, group);
        return properties
                .groupRoot(group)
                .resolve("runtime/business-memory-v1")
                .resolve(queryId(owner, "owner", "memory"))
                .toAbsolutePath()
                .normalize();
    }

    private void initialize(Path project) {
        Path marker = project.resolve(".initialized");
        if (Files.isRegularFile(marker)) return;
        try {
            Files.createDirectories(project);
            require(
                    cliRun(
                            project,
                            "context-init",
                            List.of("context", "init", "--empty", "--force")));
            Files.writeString(marker, "initialized", StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new DatasetException("创建官方样例工程失败", e);
        }
    }

    private List<String> memoryArgs(Path project, String command, String... args) {
        List<String> values =
                new ArrayList<>(
                        List.of(
                                "memory",
                                command,
                                "--path",
                                project.resolve(".wren/memory").toString()));
        values.addAll(List.of(args));
        return values;
    }

    private void store(Path project, String question, String sql) {
        require(
                cliRun(
                        project,
                        "store",
                        memoryArgs(project, "store", "--nl", question, "--sql", sql)));
    }

    private WrenCli.Result cliRun(Path project, String operation, List<String> args) {
        String question = valueAfter(args, "--nl");
        String sql = valueAfter(args, "--sql");
        log.info(
                "[answer-memory] wren cli start operation={} project={} question={} sqlSha256={}"
                        + " args={}",
                operation,
                project,
                question == null ? "" : question,
                sql == null ? "" : sha256(sql),
                redactArgs(args));
        WrenCli.Result result = cli.run(project, properties.timeout(), args);
        String output = result.output() == null ? "" : result.output().replaceAll("\\s+", " ");
        log.info(
                "[answer-memory] wren cli finish operation={} project={} exitCode={} output={}",
                operation,
                project,
                result.exitCode(),
                output.length() > 300 ? output.substring(0, 300) : output);
        return result;
    }

    private static String valueAfter(List<String> args, String option) {
        int index = args.indexOf(option);
        return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : null;
    }

    private static List<String> redactArgs(List<String> args) {
        List<String> copy = new ArrayList<>(args);
        int sql = copy.indexOf("--sql");
        if (sql >= 0 && sql + 1 < copy.size()) copy.set(sql + 1, "<sql>");
        return copy;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static Result result(Feedback f) {
        if ("DOWN".equals(f.vote()))
            return new Result(
                    f.vote(),
                    "SAVED".equals(f.memoryStatus()) ? f.pairs().size() : 0,
                    "已记录结果不正确。",
                    f.memoryStatus());
        if ("FAILED".equals(f.memoryStatus()))
            return new Result(f.vote(), 0, "点赞已记录；业务样例保存未完成，请再次点击“正确”重试。", "FAILED");
        return new Result(
                f.vote(),
                f.pairs().size(),
                f.pairs().isEmpty()
                        ? "已记录结果正确；本轮没有可保存的成功业务查询。"
                        : "已确认结果正确，保存了 " + f.pairs().size() + " 个业务问题—SQL 样例。",
                f.memoryStatus());
    }

    private void require(WrenCli.Result result) {
        if (!result.ok()) throw new DatasetException("官方 Wren 样例存储失败，请重试", 502);
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private Object lock(String owner) {
        return locks[Math.floorMod(owner.hashCode(), locks.length)];
    }

    private Path stateFile(String owner) {
        return properties
                .mdlRoot()
                .resolve(".business-answer-feedback-v1")
                .resolve(queryId(owner, "owner", "feedback") + ".json");
    }

    private State read(String owner) {
        Path file = stateFile(owner);
        try {
            return Files.exists(file)
                    ? mapper.readValue(Files.readString(file), State.class)
                    : new State(Map.of(), Map.of());
        } catch (Exception e) {
            throw new DatasetException("读取答案反馈失败", e);
        }
    }

    private void write(String owner, State state) {
        Path file = stateFile(owner);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, mapper.writeValueAsString(state));
            Files.move(
                    tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new DatasetException("保存答案反馈失败", e);
        }
    }
}
