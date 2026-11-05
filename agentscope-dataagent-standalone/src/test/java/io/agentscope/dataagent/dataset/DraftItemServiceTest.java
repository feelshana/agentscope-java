package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftItemServiceTest {
    @TempDir Path home;
    private Path root;
    private Path published;
    private ModelingActivity activity;
    private DraftItemService service;

    @BeforeEach
    void setup() throws Exception {
        var workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""));
        root = workspace.workspaceRoot("g1");
        published = root.getParent().resolve("published");
        activity = new ModelingActivity();
        service = new DraftItemService(mock(DatasetGroupService.class), workspace, activity);
        write(published, "models/orders/metadata.yml", model("订单", "金额"));
        write(published, "knowledge/rules/general.md", "# 规则\n- 已有规则\n");
        write(
                published,
                "relationships.yml",
                "relationships:\n- name: orders_users\n  models: [orders, users]\n"
                        + "  join_type: MANY_TO_ONE\n  condition: orders.user_id = users.id\n");
        write(root, "models/orders/metadata.yml", model("订单表，一行一笔订单", "实付金额，元"));
        write(root, "knowledge/rules/general.md", "# 规则\n- 已有规则\n- 营收排除内部账号\n  内部账号见 dept 字段\n");
        write(root, "knowledge/caveats/pending.md", "- 时区可能是 UTC\n");
        write(
                root,
                "relationships.yml",
                "relationships:\n- name: orders_users\n  models: [orders, users]\n"
                        + "  join_type: MANY_TO_ONE\n  condition: orders.user_id = users.id\n"
                        + "- name: orders_shops\n  models: [orders, shops]\n"
                        + "  join_type: MANY_TO_ONE\n  condition: orders.shop_id = shops.id\n");
        write(root, "knowledge/sql/q-1.md", "example\n");
    }

    private static String model(String table, String amount) {
        return "name: orders\ncolumns:\n- name: amount\n  properties:\n    description: "
                + amount
                + "\n- name: status\n  properties:\n    description: 状态\nproperties:\n"
                + "  description: "
                + table
                + "\n";
    }

    private static void write(Path base, String path, String content) throws IOException {
        Path file = base.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private DraftItemService.Item item(List<DraftItemService.Item> items, String id) {
        return items.stream().filter(i -> i.id().startsWith(id)).findFirst().orElseThrow();
    }

    @Test
    void draftIsShownAsBusinessItemsNotFiles() {
        var items = service.items("u1", "g1", null);
        var orders = item(items, "MODEL:orders");
        assertEquals("MODIFIED", orders.kind());
        assertEquals(
                List.of("__table__", "amount"),
                orders.fields().stream().map(DraftItemService.Field::name).sorted().toList());
        assertTrue(orders.editable());
        assertFalse(orders.deletable());

        var relation = item(items, "REL:orders_shops");
        assertEquals("orders.shop_id = shops.id", relation.title());
        assertEquals("多对一", relation.summary());
        assertTrue(relation.deletable());
        assertEquals(1, items.stream().filter(i -> i.category().equals("RELATIONSHIP")).count());

        var rule = item(items, "SEMANTIC:");
        assertEquals("营收排除内部账号\n内部账号见 dept 字段", rule.text());
        assertEquals("营收排除内部账号", rule.title());
        assertEquals("NOTE", item(items, "NOTE:").category());
        // Examples and other files are not modeling items.
        assertTrue(items.stream().noneMatch(i -> i.path().startsWith("knowledge/sql/")));
    }

    @Test
    void sinceLimitsItemsToFilesWrittenInThatTurnAndCarriesTheBasis() throws Exception {
        String before = Instant.now().minusSeconds(1).toString();
        DraftChangeLog.record(root, "knowledge/caveats/pending.md", ModelingTurnPurpose.AUTO, "u1");
        var items = service.items("u1", "g1", before);
        assertEquals(1, items.size());
        assertEquals(List.of("auto"), items.get(0).basis());
        assertTrue(service.items("u1", "g1", Instant.now().plusSeconds(60).toString()).isEmpty());
    }

    @Test
    void itemsAreEditedAndDeletedOneByOne() throws Exception {
        service.updateModel("u1", "g1", "orders", "订单明细", Map.of("status", "订单状态"));
        String yaml = Files.readString(root.resolve("models/orders/metadata.yml"));
        assertTrue(yaml.contains("订单明细") && yaml.contains("订单状态") && yaml.contains("实付金额"));

        service.updateBullet(
                "u1", "g1", "knowledge/rules/general.md", "营收排除内部账号\n内部账号见 dept 字段", "营收排除测试账号");
        assertEquals(
                "# 规则\n- 已有规则\n- 营收排除测试账号\n",
                Files.readString(root.resolve("knowledge/rules/general.md")));
        service.updateBullet("u1", "g1", "knowledge/caveats/pending.md", "时区可能是 UTC", null);
        assertEquals("", Files.readString(root.resolve("knowledge/caveats/pending.md")).trim());

        var items = service.updateRelationship("u1", "g1", "orders_shops", null, null, true);
        assertTrue(items.stream().noneMatch(i -> i.id().equals("REL:orders_shops")));
        assertTrue(Files.readString(root.resolve("relationships.yml")).contains("orders_users"));
        assertTrue(
                DraftChangeLog.read(root).stream()
                        .allMatch(e -> e.source().equals(ModelingTurnPurpose.EDIT)));
    }

    @Test
    void staleOrForeignEditsAreRefused() {
        assertEquals(
                409,
                assertThrows(
                                DatasetException.class,
                                () ->
                                        service.updateBullet(
                                                "u1",
                                                "g1",
                                                "knowledge/rules/general.md",
                                                "不存在",
                                                "x"))
                        .status());
        assertEquals(
                400,
                assertThrows(
                                DatasetException.class,
                                () ->
                                        service.updateBullet(
                                                "u1", "g1", "knowledge/sql/q-1.md", "example", "x"))
                        .status());
        assertEquals(
                400,
                assertThrows(
                                DatasetException.class,
                                () -> service.updateModel("u1", "g1", "../x", "t", Map.of()))
                        .status());
        activity.begin("g1");
        assertThrows(
                DatasetException.class,
                () -> service.updateModel("u1", "g1", "orders", "t", Map.of()));
    }

    private String read(Path base, String path) throws IOException {
        return Files.readString(base.resolve(path), StandardCharsets.UTF_8);
    }

    @Test
    void columnPropertiesAreWrittenAndMarkedAsUserDescribed() throws Exception {
        service.updateColumn(
                "u1",
                "g1",
                "orders",
                new DraftItemService.ColumnChange(
                        "amount",
                        null,
                        "实付金额，单位元",
                        null,
                        "MEASURE",
                        List.of("实付", " 付款金额 ", "实付"),
                        null,
                        false,
                        false),
                null);
        String yaml = read(root, "models/orders/metadata.yml");
        assertTrue(yaml.contains("description: 实付金额，单位元"), yaml);
        assertTrue(yaml.contains("description_source: user"), yaml);
        assertTrue(yaml.contains("semanticType: MEASURE"), yaml);
        assertTrue(yaml.contains("- 实付") && yaml.contains("- 付款金额"), yaml);
        // An empty value removes the property again.
        service.updateColumn(
                "u1",
                "g1",
                "orders",
                new DraftItemService.ColumnChange(
                        "amount", null, null, null, "", List.of(), null, false, false),
                null);
        yaml = read(root, "models/orders/metadata.yml");
        assertFalse(yaml.contains("semanticType") || yaml.contains("synonyms"), yaml);
        var orders = item(service.items("u1", "g1", null), "MODEL:orders");
        assertTrue(orders.fields().stream().anyMatch(f -> f.name().equals("amount")));
    }

    @Test
    void onlyCalculatedColumnsAreCreatedOrDeletedAndTheGateCanRefuse() throws Exception {
        var create =
                new DraftItemService.ColumnChange(
                        "net", "DECIMAL", "净额", null, "MEASURE", null, "amount - 1", true, false);
        assertEquals(
                400,
                assertThrows(
                                DatasetException.class,
                                () ->
                                        service.updateColumn(
                                                "u1",
                                                "g1",
                                                "orders",
                                                create,
                                                (path, content) -> "expression error"))
                        .status());
        assertFalse(
                read(root, "models/orders/metadata.yml").contains("net"),
                "a refused write leaves the file unchanged");
        service.updateColumn("u1", "g1", "orders", create, (path, content) -> null);
        String yaml = read(root, "models/orders/metadata.yml");
        assertTrue(
                yaml.contains("is_calculated: true") && yaml.contains("expression: amount - 1"),
                yaml);
        assertEquals(
                409,
                assertThrows(
                                DatasetException.class,
                                () -> service.updateColumn("u1", "g1", "orders", create, null))
                        .status());
        var deletePlain =
                new DraftItemService.ColumnChange(
                        "status", null, null, null, null, null, null, false, true);
        assertThrows(
                DatasetException.class,
                () -> service.updateColumn("u1", "g1", "orders", deletePlain, null));
        var expressionOnPlain =
                new DraftItemService.ColumnChange(
                        "status", null, null, null, null, null, "1", false, false);
        assertThrows(
                DatasetException.class,
                () -> service.updateColumn("u1", "g1", "orders", expressionOnPlain, null));
        service.updateColumn(
                "u1",
                "g1",
                "orders",
                new DraftItemService.ColumnChange(
                        "net", null, null, null, null, null, null, false, true),
                null);
        assertFalse(read(root, "models/orders/metadata.yml").contains("net"));
        var badRole =
                new DraftItemService.ColumnChange(
                        "amount", null, null, null, "SECRET", null, null, false, false);
        assertThrows(
                DatasetException.class,
                () -> service.updateColumn("u1", "g1", "orders", badRole, null));
    }

    @Test
    void relationshipsAreAddedBetweenExistingColumns() throws Exception {
        write(
                root,
                "models/users/metadata.yml",
                "name: users\ncolumns:\n- name: id\n  type: VARCHAR\n");
        write(
                root,
                "models/orders/metadata.yml",
                "name: orders\ncolumns:\n- name: user_id\n  type: VARCHAR\n");
        service.addRelationship(
                "u1", "g1", "orders", "user_id", "users", "id", "MANY_TO_ONE", "每单一个用户", null);
        String yaml = read(root, "relationships.yml");
        assertTrue(yaml.contains("name: orders_users_2"), yaml);
        assertTrue(yaml.contains("condition: orders.user_id = users.id"), yaml);
        assertTrue(yaml.contains("description: 每单一个用户"), yaml);
        assertThrows(
                DatasetException.class,
                () ->
                        service.addRelationship(
                                "u1", "g1", "orders", "nope", "users", "id", null, null, null));
        assertThrows(
                DatasetException.class,
                () ->
                        service.addRelationship(
                                "u1", "g1", "orders", "user_id", "users", "id", "CROSS", null,
                                null));
        service.updateRelationship("u1", "g1", "orders_users_2", "ONE_TO_ONE", null, "一对一", false);
        yaml = read(root, "relationships.yml");
        assertTrue(
                yaml.contains("join_type: ONE_TO_ONE") && yaml.contains("description: 一对一"), yaml);
    }

    @Test
    void aBulletMovesToAnotherSectionOfTheSameFile() throws Exception {
        write(
                root,
                "knowledge/rules/general.md",
                "# 规则\n\n## Default filters\n\n- 排除测试订单\n\n## 区域划分\n\n- 华东含上海\n  和江苏\n");
        service.moveBullet(
                "u1", "g1", "knowledge/rules/general.md", "华东含上海\n和江苏", "Naming conventions");
        String md = read(root, "knowledge/rules/general.md");
        assertFalse(
                md.substring(md.indexOf("## 区域划分"), md.indexOf("## Naming")).contains("华东"), md);
        assertTrue(md.contains("## Naming conventions\n\n- 华东含上海\n  和江苏"), md);
        service.appendBullet(
                "u1", "g1", "knowledge/rules/general.md", "Default filters", "排除已取消订单");
        md = read(root, "knowledge/rules/general.md");
        assertTrue(md.contains("- 排除测试订单\n- 排除已取消订单"), md);
        assertThrows(
                DatasetException.class,
                () ->
                        service.moveBullet(
                                "u1",
                                "g1",
                                "knowledge/rules/general.md",
                                "不存在",
                                "Naming conventions"));
    }

    @Test
    void pageEditsNeedManageAndAreRecheckedInsideTheLock() throws Exception {
        var workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""));
        DatasetGroupService groups = mock(DatasetGroupService.class);
        var guarded = new DraftItemService(groups, workspace, activity);
        var change =
                new DraftItemService.ColumnChange(
                        "amount", null, "别人改的", null, null, null, null, false, false);
        String before = read(root, "models/orders/metadata.yml");
        org.mockito.Mockito.when(groups.getGroup("u2", "g1"))
                .thenThrow(new DatasetException("无权管理该知识库", 403));
        assertEquals(
                403,
                assertThrows(
                                DatasetException.class,
                                () -> guarded.updateColumn("u2", "g1", "orders", change, null))
                        .status());
        // Allowed when queued, revoked by the time the lock is held.
        org.mockito.Mockito.when(groups.getGroup("u3", "g1"))
                .thenReturn(null)
                .thenThrow(new DatasetException("无权管理该知识库", 403));
        assertThrows(
                DatasetException.class,
                () -> guarded.updateColumn("u3", "g1", "orders", change, null));
        assertEquals(before, read(root, "models/orders/metadata.yml"));
    }
}
