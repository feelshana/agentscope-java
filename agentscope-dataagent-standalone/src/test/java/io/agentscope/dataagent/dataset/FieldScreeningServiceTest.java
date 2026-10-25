package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class FieldScreeningServiceTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, DatasetEntity> byId = new LinkedHashMap<>();
    private DatasetGroupService groups;
    private MdlSeeder seeder;
    private Path workspace;
    private FieldScreeningService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        DatasetRepository repository = mock(DatasetRepository.class);
        when(repository.findByGroupId(anyString()))
                .thenAnswer(
                        i ->
                                byId.values().stream()
                                        .filter(d -> d.getGroupId().equals(i.getArgument(0)))
                                        .toList());
        when(repository.findById(anyString()))
                .thenAnswer(i -> Optional.ofNullable(byId.get(i.getArgument(0))));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        groups = mock(DatasetGroupService.class);
        when(groups.getGroup("bob", "g1")).thenThrow(new DatasetException("知识库不存在或无权限", 404));
        WrenProperties props =
                new WrenProperties(
                        "wren", temp.resolve("mdl").toString(), "dataagent", "mysql", 10);
        var cli = new MdlSeederTest.FakeCli();
        var workspaceService = new MdlWorkspaceService(props, cli);
        seeder =
                new MdlSeeder(
                        repository, workspaceService, new WrenTypeNormalizer(props, cli, mapper));
        workspace = temp.resolve("mdl/g1/workspace");
        FactQueryWindowPolicy windows = mock(FactQueryWindowPolicy.class);
        when(windows.rules("g1"))
                .thenReturn(List.of(new SqlWindowGate.Rule("orders", List.of("order_time"), 31)));
        ObjectProvider<Model> models = mock(ObjectProvider.class);
        service =
                new FieldScreeningService(
                        groups, repository, workspaceService, seeder, windows, models);
        dataset(
                "o1",
                "orders",
                false,
                new String[][] {
                    {"order_time", "datetime"},
                    {"account", "varchar"},
                    {"etl_batch_id", "bigint"},
                    {"channel", "varchar"}
                });
        dataset("u1", "users", true, new String[][] {{"account", "varchar"}, {"dept", "varchar"}});
    }

    private void dataset(String id, String name, boolean confirmed, String[][] columns)
            throws Exception {
        DatasetEntity d = new DatasetEntity();
        d.setId(id);
        d.setGroupId("g1");
        d.setOwnerId("alice");
        d.setName(name);
        d.setTableName("ds_" + id);
        d.setSchemaName("data_agent");
        d.setCreatedAt(Instant.parse("2026-10-01T00:00:00Z"));
        d.setFieldScreeningConfirmed(confirmed);
        List<ColumnSchema> cols = new ArrayList<>();
        for (String[] c : columns) cols.add(new ColumnSchema(c[0], c[0], c[1], true, c[0] + " 说明"));
        d.setColumnSchemaJson(mapper.writeValueAsString(cols));
        byId.put(id, d);
    }

    private static FieldScreeningService.Field field(
            FieldScreeningService.TableFields t, String name) {
        return t.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void withoutAModelEveryColumnIsUnknownAndIncludedAndTheTimeColumnIsLocked() {
        var table = service.fields("alice", "g1", "o1");
        assertThat(table.fields()).allMatch(f -> f.role().equals("UNKNOWN") && f.included());
        assertThat(field(table, "order_time").locked()).isTrue();
        assertThat(field(table, "account").joinKey()).isTrue();
        assertThat(field(table, "channel").joinKey()).isFalse();
    }

    @Test
    void theTimeColumnCannotBeExcludedAndATableKeepsOneColumn() {
        assertEquals(
                400,
                assertThrows(
                                DatasetException.class,
                                () ->
                                        service.save(
                                                "alice",
                                                "g1",
                                                "o1",
                                                List.of(
                                                        new FieldScreeningService.Update(
                                                                "order_time", false, null)),
                                                true,
                                                false))
                        .status());
        var everything = new ArrayList<FieldScreeningService.Update>();
        for (String n : List.of("account", "etl_batch_id", "channel", "dept"))
            everything.add(new FieldScreeningService.Update(n, false, null));
        // order_time stays included, so excluding the rest is allowed.
        service.save("alice", "g1", "o1", everything, false, false);
        assertThrows(
                DatasetException.class,
                () ->
                        service.save(
                                "alice",
                                "g1",
                                "u1",
                                List.of(
                                        new FieldScreeningService.Update("account", false, null),
                                        new FieldScreeningService.Update("dept", false, null)),
                                true,
                                false));
    }

    @Test
    void autoModelingWaitsForEveryTableToBeConfirmed() {
        assertThrows(DatasetException.class, () -> service.requireConfirmed("g1"));
        service.save("alice", "g1", "o1", List.of(), true, false);
        service.requireConfirmed("g1");
        assertThat(service.status("alice", "g1"))
                .allMatch(FieldScreeningService.TableStatus::confirmed);
    }

    @Test
    void confirmedChoicesPruneTheDraftKeepUserDescriptionsAndAreNeverAddedBack() throws Exception {
        seeder.reconcile("g1");
        Path model = workspace.resolve("models/orders/metadata.yml");
        assertThat(Files.readString(model)).contains("etl_batch_id");
        var first =
                service.save(
                        "alice",
                        "g1",
                        "o1",
                        List.of(
                                new FieldScreeningService.Update("etl_batch_id", false, null),
                                new FieldScreeningService.Update("account", null, "访问账号，wx 结尾为外协")),
                        true,
                        false);
        assertThat(first.remodel()).isFalse(); // first screening: automatic modeling follows
        String yml = Files.readString(model, StandardCharsets.UTF_8);
        assertThat(yml)
                .doesNotContain("etl_batch_id")
                .contains("访问账号，wx 结尾为外协", "description_source: user");
        seeder.reconcile("g1");
        assertThat(Files.readString(model)).doesNotContain("etl_batch_id");
    }

    @Test
    void adjustingAfterModelingListsReferencesBeforeRemovingAndAsksToRemodel() throws Exception {
        seeder.reconcile("g1");
        service.save("alice", "g1", "o1", List.of(), true, false);
        Path rules = workspace.resolve("knowledge/rules/general.md");
        Files.createDirectories(rules.getParent());
        Files.writeString(rules, "# 规则\n- 渠道统计用 orders.channel 字段\n- 其他\n");
        var blocked =
                service.save(
                        "alice",
                        "g1",
                        "o1",
                        List.of(new FieldScreeningService.Update("channel", false, null)),
                        true,
                        false);
        assertThat(blocked.references())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.path()).isEqualTo("knowledge/rules/general.md");
                            assertThat(r.line()).contains("orders.channel");
                        });
        assertThat(Files.readString(workspace.resolve("models/orders/metadata.yml")))
                .contains("channel");
        var forced =
                service.save(
                        "alice",
                        "g1",
                        "o1",
                        List.of(new FieldScreeningService.Update("channel", false, null)),
                        true,
                        true);
        assertThat(forced.remodel()).isTrue();
        assertThat(Files.readString(workspace.resolve("models/orders/metadata.yml")))
                .doesNotContain("channel");
        assertThat(DraftChangeLog.read(workspace))
                .anyMatch(
                        e ->
                                e.path().equals("models/orders/metadata.yml")
                                        && e.source().equals(ModelingTurnPurpose.EDIT));
        // Including it again appends it back through the seeder.
        service.save(
                "alice",
                "g1",
                "o1",
                List.of(new FieldScreeningService.Update("channel", true, null)),
                true,
                false);
        assertThat(Files.readString(workspace.resolve("models/orders/metadata.yml")))
                .contains("channel");
    }

    @Test
    void anotherUserCannotSeeOrChangeTheFields() {
        assertThrows(DatasetException.class, () -> service.fields("bob", "g1", "o1"));
        assertThrows(DatasetException.class, () -> service.status("bob", "g1"));
        assertThrows(
                DatasetException.class,
                () -> service.save("bob", "g1", "o1", List.of(), true, false));
        assertThrows(DatasetException.class, () -> service.fields("alice", "g1", "missing"));
    }

    @Test
    void modelRolesAreParsedDefensively() {
        var roles =
                FieldScreeningService.parse(
                        "说明 {\"columns\":[{\"name\":\"etl_batch_id\",\"role\":\"technical\",\"reason\":\"ETL批次\"},"
                            + "{\"name\":\"x\",\"role\":\"weird\"},{\"name\":\"\"}]}");
        assertThat(roles.get("etl_batch_id")).containsExactly("TECHNICAL", "ETL批次");
        assertThat(roles.get("x")[0]).isEqualTo("UNKNOWN");
        assertThat(roles).hasSize(2);
        assertThat(FieldScreeningService.parse("无法判断")).isEmpty();
    }
}
