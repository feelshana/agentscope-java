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
}
