package io.agentscope.dataagent.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.ProcessWrenCli;
import io.agentscope.dataagent.dataset.WrenProfileHome;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenInstanceRegistry;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.tools.data.WrenMcpToolkit;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;

/** Opt-in paid LLM evaluation using native ReAct, official skills and the production MCP adapter.
 * It uses synthetic data only and does not test the running HTTP service, publication UI or Python.
 */
@EnabledIfSystemProperty(named = "dataagent.test.liveWren", matches = "true")
class WrenLiveQueryEvaluationTest {
    @TempDir Path home;
    private static final ObjectMapper JSON = new ObjectMapper();

    record ModelSetup(io.agentscope.core.model.Model model, String name) {}

    static ModelSetup configuredModel() throws Exception {
        var config =
                new ObjectMapper(new YAMLFactory())
                        .readTree(Path.of("src/main/resources/application.yml").toFile())
                        .path("dataagent")
                        .path("openai");
        var environment = new StandardEnvironment();
        String key = environment.resolvePlaceholders(config.path("api-key").asText());
        assumeTrue(
                !key.isBlank() && !key.contains("${"), "Configured model credentials are required");
        String modelName = environment.resolvePlaceholders(config.path("model-name").asText());
        var model =
                OpenAIChatModel.builder()
                        .apiKey(key)
                        .baseUrl(environment.resolvePlaceholders(config.path("base-url").asText()))
                        .modelName(modelName)
                        .stream(
                                Boolean.parseBoolean(
                                        environment.resolvePlaceholders(
                                                config.path("stream").asText("true"))))
                        .generateOptions(
                                GenerateOptions.builder()
                                        .additionalBodyParam("thinking", Map.of("type", "disabled"))
                                        .build())
                        .build();
        return new ModelSetup(model, modelName);
    }

    @Test
    void evaluateGeneratedQueriesAgainstIndependentReference() throws Exception {
        String executable = System.getProperty("dataagent.test.wren", "");
        Path source = Path.of("target/wren-native-fixture").toAbsolutePath();
        Path skills =
                Path.of(
                        System.getProperty(
                                "dataagent.test.wrenSkills",
                                "target/wren-native-venv/Lib/site-packages/wren/skills_content"));
        assumeTrue(!executable.isBlank() && Files.isRegularFile(source.resolve("summary.json")));
        var setup = configuredModel();
        var model = setup.model();
        String modelName = setup.name();
        var props = new WrenProperties(executable, home.toString(), "fixture", "duckdb", 120);
        Files.createDirectories(props.wrenHome());
        Files.copy(source.resolve("home/profiles.yml"), props.wrenHome().resolve("profiles.yml"));
        Path published = props.groupRoot("fixture").resolve("published");
        for (String name :
                List.of("models", "views", "cubes", "knowledge", "target", "wren_project.yml")) {
            Path origin = source.resolve(name);
            if (!Files.exists(origin)) continue;
            try (var paths = Files.walk(origin)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    Path destination = published.resolve(source.relativize(file));
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination);
                }
            }
        }
        var group = new DatasetGroupEntity("fixture", "fixture-user", "隔离测试知识库", "");
        group.setMdlVersion(1);
        var groups = mock(DatasetGroupService.class);
        when(groups.getGroup("fixture-user", "fixture")).thenReturn(group);
        when(groups.listGroups("fixture-user")).thenReturn(List.of(group));
        var repository = mock(DatasetGroupRepository.class);
        when(repository.findById("fixture")).thenReturn(Optional.of(group));
        var profiles = mock(WrenProfileHome.class);
        when(profiles.ensure()).thenReturn(props.wrenHome());
        var projects = new WrenPublishedProject(props, groups, new ProcessWrenCli(props));
        var registry = new WrenInstanceRegistry(props, profiles, projects, repository);
        var traces = new CopyOnWriteArrayList<Map<String, Object>>();
        WrenQueryGateway tracked =
                new WrenQueryGateway() {
                    public WrenCallResult call(
                            String groupId, String tool, Map<String, Object> arguments) {
                        var result = registry.call(groupId, tool, arguments);
                        traces.add(
                                Map.of(
                                        "tool",
                                        tool,
                                        "arguments",
                                        arguments,
                                        "ok",
                                        result.ok(),
                                        "payload",
                                        result.payload()));
                        return result;
                    }

                    public List<McpSchema.Tool> listTools(String groupId) {
                        return registry.listTools(groupId);
                    }

                    public void invalidate(String groupId) {
                        registry.invalidate(groupId);
                    }
                };
        var toolkit = new Toolkit();
        toolkit.registerTool(
                new WrenMcpToolkit(
                        tracked,
                        new WrenQueryAccess(groups, new ConversationScopeRegistry()),
                        projects,
                        null));
        var questions = new ArrayList<JsonNode>();
        JSON.readTree(source.resolve("questions.json").toFile()).forEach(questions::add);
        var critical = questions.remove(6);
        questions.add(0, critical);
        String selectedQuestions = System.getProperty("dataagent.test.liveQuestionPattern", "");
        if (!selectedQuestions.isBlank())
            questions.removeIf(q -> !q.path("question").asText().matches(selectedQuestions));
        int count = Math.min(Integer.getInteger("dataagent.test.liveCount", 30), questions.size());
        var reports = new ArrayList<Map<String, Object>>();
        Path output =
                Path.of(
                        System.getProperty(
                                "dataagent.test.liveReport",
                                "target/wren-native-live/report.json"));
        Files.createDirectories(output.getParent());
        try {
            for (int i = 0; i < count; i++) {
                traces.clear();
                var agent =
                        ReActAgent.builder()
                                .name("wren-evaluation")
                                .model(model)
                                .toolkit(toolkit)
                                .sysPrompt(
                                        DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT
                                                + "\n当前唯一已发布知识库：fixture（隔离测试知识库）。当前日期为 2026-10-11。")
                                .skillRepository(new FileSystemSkillRepository(skills))
                                .skillFilter(
                                        SkillFilter.only(
                                                "usage",
                                                "onboarding",
                                                "enrich-context",
                                                "generate-mdl"))
                                .maxIters(18)
                                .build();
                var context =
                        RuntimeContext.builder()
                                .userId("fixture-user")
                                .sessionId("case-" + i)
                                .put(
                                        DatasetScope.class,
                                        new DatasetScope("fixture-user", List.of("fixture")))
                                .build();
                var question = questions.get(i);
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("question", question.path("question").asText());
                report.put("expected", question.path("expected"));
                long started = System.nanoTime();
                try {
                    var answer =
                            agent.call(question.path("question").asText(), context)
                                    .block(Duration.ofMinutes(3));
                    report.put("answer", answer == null ? "" : answer.getTextContent());
                } catch (Exception error) {
                    // Exception classes are sufficient here; do not persist credential-bearing
                    // request data.
                    report.put("errorType", error.getClass().getName());
                }
                report.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
                report.put("calls", List.copyOf(traces));
                JsonNode lastRows = null;
                for (var call : traces) {
                    if (!Boolean.TRUE.equals(call.get("ok"))
                            || !List.of("run_sql", "query_cube").contains(call.get("tool")))
                        continue;
                    try {
                        var payload = JSON.readTree(String.valueOf(call.get("payload")));
                        if (payload.has("rows")) lastRows = payload.path("rows");
                    } catch (Exception ignored) {
                    }
                }
                report.put(
                        "queryResultMatches",
                        lastRows != null
                                && values(lastRows).equals(values(question.path("expected"))));
                reports.add(report);
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValue(
                                output.toFile(),
                                Map.of(
                                        "model",
                                        modelName,
                                        "scope",
                                        "native ReAct + production MCP adapter; synthetic data;"
                                                + " final-answer review required",
                                        "cases",
                                        reports));
                // Stop on transport failure; do not spend on the same broken upstream 30 times.
                if (report.containsKey("errorType")) break;
            }
        } finally {
            registry.invalidate("fixture");
        }
        assertThat(reports).hasSize(count);
        assertThat(reports).allSatisfy(report -> assertThat(report).doesNotContainKey("errorType"));
        if (Boolean.getBoolean("dataagent.test.liveCollectOnly")) return;

        assertThat(
                        reports.stream()
                                .filter(r -> Boolean.TRUE.equals(r.get("queryResultMatches")))
                                .count())
                .as("Generated query results; final narrative correctness still needs review")
                .isGreaterThanOrEqualTo((long) Math.ceil(count * 0.9));
    }

    private static List<List<String>> values(JsonNode rows) {
        List<List<String>> result = new ArrayList<>();
        rows.forEach(
                row -> {
                    List<String> cells = new ArrayList<>();
                    row.fields()
                            .forEachRemaining(
                                    entry -> {
                                        // Official Cube results include their selected temporal
                                        // dimension.
                                        // Keep row count and all measures; exclude only named
                                        // time-grain columns.
                                        if (entry.getKey()
                                                .matches(".+__(year|quarter|month|week|day|hour)"))
                                            return;
                                        JsonNode value = entry.getValue();
                                        String text = value.isNull() ? "null" : value.asText();
                                        try {
                                            text =
                                                    new BigDecimal(text)
                                                            .stripTrailingZeros()
                                                            .toPlainString();
                                        } catch (NumberFormatException ignored) {
                                        }
                                        cells.add(text);
                                    });
                    result.add(cells);
                });
        return result;
    }
}
