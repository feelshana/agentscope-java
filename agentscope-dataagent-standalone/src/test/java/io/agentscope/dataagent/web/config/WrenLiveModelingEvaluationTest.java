package io.agentscope.dataagent.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.MdlQuestionStore;
import io.agentscope.dataagent.dataset.MdlWorkspaceService;
import io.agentscope.dataagent.dataset.ModelingWorkflowStore;
import io.agentscope.dataagent.dataset.ProcessWrenCli;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.tools.data.OfficialWrenToolkit;
import io.agentscope.dataagent.tools.data.WrenProjectFilesystem;
import io.agentscope.dataagent.web.middleware.ModelingHitlMiddleware;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.agentscope.harness.agent.tool.FilesystemTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Real model + native HITL against a synthetic, unpublished project only. */
@EnabledIfSystemProperty(named = "dataagent.test.liveModeling", matches = "true")
class WrenLiveModelingEvaluationTest {
    @TempDir Path home;

    @Test
    void enrichDocumentAndValidateOfficialAssetsWithNativeConfirmation() throws Exception {
        String executable = System.getProperty("dataagent.test.wren", "");
        assumeTrue(!executable.isBlank());
        var json = new ObjectMapper();
        var yaml = new ObjectMapper(new YAMLFactory());
        var source = Path.of("target/wren-native-fixture").toAbsolutePath();
        var props = new WrenProperties(executable, home.toString(), "fixture", "duckdb", 120);
        var cli = new ProcessWrenCli(props);
        var groups = mock(DatasetGroupService.class);
        when(groups.getGroup("fixture-user", "fixture"))
                .thenReturn(new DatasetGroupEntity("fixture", "fixture-user", "隔离建模工程", ""));
        var knowledge = mock(DatasetKnowledgeRepository.class);
        when(knowledge.findById("fixture"))
                .thenReturn(
                        Optional.of(
                                new DatasetKnowledgeEntity(
                                        "fixture",
                                        """
                                        # 已确认业务定义
                                        有效订单必须同时满足 status='paid'、is_internal=0、amount>0。
                                        营收为有效订单 amount 合计，付费客户数为有效订单 customer_id 去重数。
                                        退款整单排除、内部测试客户排除、零额订单排除；年度客户数必须在年度范围重算，不能累加月度去重。
                                        order_date 是业务日期，年度时间范围采用当年1月1日到下一年1月1日，不含后者。
                                        金额单位未提供，请勿猜测；无有效订单时 SUM 返回 NULL，报告无数据，不补零。
                                        希望沉淀统一的有效订单明细视图，供钻取与指标共用；两项指标按官方 enrich-context 指引定义，可按年/月查询。
                                        这里无需预设问题或问题验收清单，技术验证由助手执行。当前文档业务含义已确认。
                                        """)));
        var workspace = new MdlWorkspaceService(props, cli);
        var access =
                new WrenProjectAccess(
                        groups, workspace, knowledge, new ConversationScopeRegistry());
        var ctx =
                RuntimeContext.builder()
                        .userId("fixture-user")
                        .sessionId("modeling-fixture")
                        .put(
                                DatasetScope.class,
                                new DatasetScope("fixture-user", List.of("fixture")))
                        .build();
        var project = access.resolve(ctx);
        Files.createDirectories(props.wrenHome());
        Files.copy(source.resolve("home/profiles.yml"), props.wrenHome().resolve("profiles.yml"));
        try (var paths = Files.walk(source.resolve("models"))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                var destination = project.root().resolve(source.relativize(file));
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination);
            }
        }
        var publication = mock(MdlPublishService.class);
        when(publication.draftCliProfile("fixture-user", "fixture")).thenReturn("fixture");
        var checks = new ModelingWorkflowStore(workspace, new MdlQuestionStore(workspace));
        var commands = new OfficialWrenToolkit(access, cli, props, publication, checks);
        var files = new WrenProjectFilesystem(access);
        var toolkit = new Toolkit();
        toolkit.registerTool(new FilesystemTool(files));
        toolkit.registerTool(files);
        toolkit.registerTool(commands);
        var gate =
                new ModelingHitlMiddleware(
                        (context, call) ->
                                "wren_cli".equals(call.getName())
                                        ? commands.preflight(context, call)
                                        : files.preflight(context, call));
        var setup = WrenLiveQueryEvaluationTest.configuredModel();
        var agent =
                ReActAgent.builder()
                        .name("modeling-evaluation")
                        .model(setup.model())
                        .sysPrompt(
                                DataAgentConfig.MODELING_SCRIPT
                                        + "\n"
                                        + "当前唯一知识库 fixture，工作区路径为工程根目录；使用相对路径，资料在"
                                        + " raw/business-document.md。")
                        .toolkit(toolkit)
                        .middleware(gate)
                        .enablePendingToolRecovery(true)
                        .skillRepository(
                                new FileSystemSkillRepository(
                                        Path.of(
                                                "target/wren-native-venv/Lib/site-packages/wren/skills_content")))
                        .skillFilter(
                                SkillFilter.only(
                                        "usage", "onboarding", "enrich-context", "generate-mdl"))
                        .maxIters(24)
                        .build();
        List<Msg> inputs =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent(
                                        "请按官方 enrich-context 的 Auto-pilot"
                                            + " 模式处理已确认的业务文档，完善现有基本模型、明细视图和可复用指标，并自行校验、编译、查询检查。无需预设问题，不发布。需要文件权限时发出工具调用，我会确认本隔离草稿的写入权限。请开始并完成工作。")
                                .build());
        var turns = new ArrayList<Map<String, Object>>();
        Path output = Path.of("target/wren-native-modeling");
        Files.createDirectories(output);
        int approvals = 0;
        int businessConfirmations = 0;
        for (int round = 0; round < 12; round++) {
            var events = agent.streamEvents(inputs, ctx).collectList().block(Duration.ofMinutes(4));
            var asking =
                    events.stream()
                            .filter(RequireUserConfirmEvent.class::isInstance)
                            .map(RequireUserConfirmEvent.class::cast)
                            .findFirst()
                            .orElse(null);
            var state = agent.getAgentState("fixture-user", "modeling-fixture");
            turns.add(
                    Map.of(
                            "round",
                            round,
                            "asking",
                            asking == null
                                    ? List.of()
                                    : asking.getToolCalls().stream()
                                            .map(
                                                    t ->
                                                            Map.of(
                                                                    "id",
                                                                    t.getId(),
                                                                    "tool",
                                                                    t.getName(),
                                                                    "arguments",
                                                                    t.getInput()))
                                            .toList(),
                            "messages",
                            state.getContext().stream()
                                    .map(
                                            m ->
                                                    Map.of(
                                                            "role",
                                                            m.getRole().name(),
                                                            "text",
                                                            java.util.Objects.toString(
                                                                    m.getTextContent(), "")))
                                    .toList()));
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(output.resolve("turns.json").toFile(), turns);
            if (asking == null) {
                boolean assetsReady;
                try (var paths = Files.walk(project.root())) {
                    assetsReady =
                            paths.anyMatch(
                                    p ->
                                            p.startsWith(project.root().resolve("cubes"))
                                                    && p.getFileName()
                                                            .toString()
                                                            .equals("metadata.yml"));
                }
                if (assetsReady || businessConfirmations >= 2) break;
                // A synthetic user accepts the official skill's high-impact asset proposal.
                businessConfirmations++;
                inputs =
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent(
                                                "接受本隔离测试工程中你提出的有效订单明细视图与营收、付费客户数指标方案。请按已确认的文档口径完成写入和技术校验；需要工具写入权限时继续申请。不发布。")
                                        .build());
                continue;
            }
            approvals++;
            inputs =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .textContent("[confirm]")
                                    .metadata(
                                            Map.of(
                                                    Msg.METADATA_CONFIRM_RESULTS,
                                                    asking.getToolCalls().stream()
                                                            .map(t -> new ConfirmResult(true, t))
                                                            .toList()))
                                    .build());
        }
        assertThat(commands.execute(ctx, List.of("context", "validate", "--strict")))
                .contains("退出码：0");
        assertThat(commands.execute(ctx, List.of("context", "build"))).contains("退出码：0");
        assertThat(Files.isDirectory(project.root().resolve("views"))).isTrue();
        assertThat(Files.isDirectory(project.root().resolve("cubes"))).isTrue();
        List<Path> cubes;
        try (var paths = Files.walk(project.root().resolve("cubes"))) {
            cubes = paths.filter(p -> p.getFileName().toString().equals("metadata.yml")).toList();
        }
        assertThat(cubes).isNotEmpty();
        int checkedMeasures = 0;
        for (Path cubeFile : cubes) {
            var cube = yaml.readTree(cubeFile.toFile());
            String time = cube.path("time_dimensions").get(0).path("name").asText();
            String customerMeasure = null, revenueMeasure = null;
            for (var measure : cube.path("measures")) {
                String expression = measure.path("expression").asText().toUpperCase();
                if (expression.contains("COUNT") && expression.contains("DISTINCT"))
                    customerMeasure = measure.path("name").asText();
                if (expression.contains("SUM")) revenueMeasure = measure.path("name").asText();
            }
            for (String measure :
                    List.of(
                            customerMeasure == null ? "" : customerMeasure,
                            revenueMeasure == null ? "" : revenueMeasure)) {
                if (measure.isEmpty()) continue;
                var result =
                        commands.execute(
                                ctx,
                                List.of(
                                        "cube",
                                        "query",
                                        "--cube",
                                        cube.path("name").asText(),
                                        "--measures",
                                        measure,
                                        "--filter",
                                        time + ":gte:2025-01-01",
                                        "--filter",
                                        time + ":lt:2026-01-01",
                                        "--output",
                                        "json"));
                assertThat(result).contains("退出码：0");
                var row =
                        result.lines()
                                .filter(line -> line.strip().startsWith("{"))
                                .findFirst()
                                .orElseThrow();
                var values = json.readTree(row);
                assertThat(values.path(measure).asInt())
                        .isEqualTo(measure.equals(customerMeasure) ? 2 : 3099);
                checkedMeasures++;
            }
        }
        assertThat(checkedMeasures).isGreaterThanOrEqualTo(2);
        try (var paths = Files.walk(project.root())) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path relative = project.root().relativize(file);
                if (relative.toString().startsWith(".")) continue;
                Path destination = output.resolve("project").resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        json.writerWithDefaultPrettyPrinter()
                .writeValue(
                        output.resolve("summary.json").toFile(),
                        Map.of(
                                "model",
                                setup.name(),
                                "nativeConfirmationBatches",
                                approvals,
                                "businessConfirmations",
                                businessConfirmations,
                                "yearCustomers",
                                2,
                                "yearRevenue",
                                3099,
                                "published",
                                false));
        assertThat(Files.exists(props.groupRoot("fixture").resolve("published"))).isFalse();
    }
}
