package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MdlQuestionStoreTest {
    @TempDir Path home;
    private MdlWorkspaceService workspace;
    private MdlQuestionStore store;
    private Path root;

    @BeforeEach
    void setup() throws Exception {
        workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""));
        root = workspace.workspaceRoot("g1");
        Files.createDirectories(root.resolve("knowledge/questions"));
        Files.createDirectories(root.resolve("models/order"));
        Files.writeString(root.resolve("models/order/metadata.yml"), "name: order\n");
        question("sales", "SELECT SUM(amount) AS total FROM order");
        store = new MdlQuestionStore(workspace);
    }

    private void question(String id, String sql) throws Exception {
        Files.writeString(
                root.resolve("knowledge/questions/" + id + ".yml"),
                "question: 总销售额是多少\ndefinition: 有效订单，不扣退款，单位元\nsql: '"
                        + sql
                        + "'\nrequired: true\n");
    }

    @Test
    void questionPlansRejectRetiredCubeStrategiesAndAssetReferences() throws Exception {
        Path source = root.resolve("knowledge/questions/sales.yml");
        for (String strategy : List.of("CUBE", "EXAMPLE")) {
            Files.writeString(
                    source,
                    "question: 营收\ndefinition: 元\nsql: SELECT SUM(amount) FROM order\n"
                            + "modeling:\n  strategy: "
                            + strategy
                            + "\n"
                            + "  reason: 文档定义\n"
                            + "  assets:\n"
                            + "    - kind: CUBE\n"
                            + "      name: revenue\n");
            assertThrows(DatasetException.class, () -> store.requireQuestion("g1", "sales"));
        }
    }

    private MdlQuestionStore.Receipt execute(boolean truncated, String error) throws Exception {
        var result =
                new ObjectMapper()
                        .readTree("{\"columns\":[\"total\"],\"rows\":[{\"total\":1050}]}");
        return store.saveExecution(
                "g1",
                store.requireQuestion("g1", "sales"),
                store.modelHash(root),
                result,
                error,
                truncated);
    }

    @Test
    void archivedQuestionsLeaveReviewAndPublishGateButKeepSourceAndReceipt() throws Exception {
        var receipt = execute(false, null);
        store.decide("g1", "sales", receipt.validationId(), true, "alice");
        Path source = root.resolve("knowledge/questions/sales.yml");
        Files.writeString(source, Files.readString(source) + "archived: true\n");
        assertTrue(store.list("g1").isEmpty());
        assertTrue(store.publishIssues("g1").isEmpty());
        assertTrue(Files.exists(source));
        assertThrows(DatasetException.class, () -> store.requireQuestion("g1", "sales"));
    }

    @Test
    void humanCorrectionInvalidatesOnlyItsExampleAndKeepsSemanticAssets() throws Exception {
        question("other", "SELECT COUNT(*) FROM order");
        var other = store.requireQuestion("g1", "other");
        var otherReceipt =
                store.saveExecution(
                        "g1",
                        other,
                        store.modelHash(root),
                        new ObjectMapper().readTree("{\"columns\":[],\"rows\":[]}"),
                        null,
                        false);
        store.decide("g1", "other", otherReceipt.validationId(), true, "alice");
        var receipt = execute(false, null);
        store.decide("g1", "sales", receipt.validationId(), true, "alice");
        String modelHash = store.modelHash(root);
        String metadata = Files.readString(root.resolve("models/order/metadata.yml"));
        var updated =
                store.correctSql(
                        "g1",
                        "sales",
                        store.revision("g1", "sales"),
                        "SELECT COUNT(*) AS total FROM order");
        assertEquals("STALE", updated.status());
        assertEquals("EXAMPLE", updated.question().modeling().strategy());
        assertEquals(modelHash, store.modelHash(root));
        assertEquals(metadata, Files.readString(root.resolve("models/order/metadata.yml")));
        assertFalse(Files.exists(root.resolve("knowledge/sql/sales.md")));
        assertEquals(
                "CONFIRMED",
                store.list("g1").stream()
                        .filter(r -> r.question().id().equals("other"))
                        .findFirst()
                        .orElseThrow()
                        .status());
        assertThrows(
                DatasetException.class,
                () -> store.decide("g1", "sales", receipt.validationId(), true, "alice"));
        var newReceipt = execute(false, null);
        assertEquals(
                "EXECUTED",
                store.list("g1").stream()
                        .filter(r -> r.question().id().equals("sales"))
                        .findFirst()
                        .orElseThrow()
                        .status());
        store.decide("g1", "sales", newReceipt.validationId(), true, "alice");
        assertTrue(Files.readString(root.resolve("knowledge/sql/sales.md")).contains("COUNT(*)"));
    }

    @Test
    void correctionsRejectUnsafeSqlAndConcurrentChangesWithoutOverwriting() throws Exception {
        String revision = store.revision("g1", "sales");
        Path source = root.resolve("knowledge/questions/sales.yml");
        String before = Files.readString(source);
        assertThrows(
                DatasetException.class,
                () -> store.correctSql("g1", "sales", revision, "DELETE FROM order"));
        assertEquals(before, Files.readString(source));
        question("sales", "SELECT COUNT(*) FROM order");
        String concurrent = Files.readString(source);
        assertThrows(
                DatasetException.class,
                () -> store.correctSql("g1", "sales", revision, "SELECT SUM(amount) FROM order"));
        assertThrows(DatasetException.class, () -> store.archive("g1", "sales", revision));
        assertEquals(concurrent, Files.readString(source));
    }

    @Test
    void humanArchiveRemovesDraftExampleButKeepsHistoryAndPublishedVersion() throws Exception {
        var receipt = execute(false, null);
        store.decide("g1", "sales", receipt.validationId(), true, "alice");
        Path published = root.getParent().resolve("published/knowledge/sql/sales.md");
        Files.createDirectories(published.getParent());
        Files.writeString(published, "published example");
        store.archive("g1", "sales", store.revision("g1", "sales"));
        assertTrue(store.list("g1").isEmpty());
        assertTrue(store.publishIssues("g1").isEmpty());
        assertTrue(Files.exists(root.resolve("knowledge/questions/sales.yml")));
        assertTrue(Files.exists(root.resolve(".platform/questions/sales.json")));
        assertFalse(Files.exists(root.resolve("knowledge/sql/sales.md")));
        assertEquals("published example", Files.readString(published));
    }

    @Test
    void receiptKeepsRowsAcrossLegacyStorageAndSpringHttpSerialization() throws Exception {
        var receipt = execute(false, null);
        String http =
                tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .writeValueAsString(receipt);
        var serialized = new ObjectMapper().readTree(http);
        assertEquals("total", serialized.path("result").path("columns").get(0).asText());
        assertEquals(1050, serialized.path("result").path("rows").get(0).path("total").asInt());
        String reloaded =
                tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .writeValueAsString(store.list("g1").get(0));
        assertEquals(
                1050,
                new ObjectMapper()
                        .readTree(reloaded)
                        .path("validation")
                        .path("result")
                        .path("rows")
                        .get(0)
                        .path("total")
                        .asInt());
    }

    @Test
    void successfulExecutionStillRequiresHumanConfirmationAndWritesOfficialPair() throws Exception {
        assertEquals("DRAFT", store.list("g1").get(0).status());
        assertFalse(store.publishIssues("g1").isEmpty());
        var receipt = execute(false, null);
        assertEquals("EXECUTED", store.list("g1").get(0).status());
        assertFalse(store.publishIssues("g1").isEmpty());
        store.decide("g1", "sales", receipt.validationId(), true, "alice");
        assertEquals("CONFIRMED", store.list("g1").get(0).status());
        assertTrue(store.publishIssues("g1").isEmpty());
        String pair = Files.readString(root.resolve("knowledge/sql/sales.md"));
        assertTrue(pair.contains("\nnl: "));
        assertTrue(pair.contains("\nsql: "));
        assertTrue(pair.contains("verified: true"));
        assertFalse(pair.contains("1050"));
        assertEquals("alice", store.list("g1").get(0).validation().confirmedBy());
    }

    @Test
    void evidenceSurvivesReloadAndDecisionAndNeverChangesWithTheWorkspace() throws Exception {
        Path view = root.resolve("views/monthly/sql.yml");
        Files.createDirectories(view.getParent());
        Files.writeString(view, "statement: SELECT SUM(amount) FROM order\n");
        var evidence =
                new MdlQuestionStore.Evidence(
                        "SELECT * FROM monthly",
                        List.of(
                                new MdlWorkspaceReader.WorkspaceView(
                                        "monthly",
                                        "SELECT SUM(amount) FROM order",
                                        "有效营收",
                                        "views/monthly/sql.yml")));
        var receipt =
                store.saveExecution(
                        "g1",
                        store.requireQuestion("g1", "sales"),
                        store.modelHash(root),
                        new ObjectMapper().readTree("{\"columns\":[\"total\"],\"rows\":[]}"),
                        null,
                        false,
                        evidence);
        assertEquals(evidence, store.list("g1").get(0).validation().evidence());
        var decided = store.decide("g1", "sales", receipt.validationId(), true, "alice");
        assertEquals(evidence, decided.validation().evidence());
        Files.writeString(view, "statement: SELECT COUNT(*) FROM order\n");
        var stale = store.list("g1").get(0);
        assertEquals("STALE", stale.status());
        assertEquals(evidence, stale.validation().evidence());
        assertThrows(
                DatasetException.class,
                () -> store.decide("g1", "sales", receipt.validationId(), true, "alice"));
    }

    @Test
    void legacyReceiptWithoutEvidenceStillLoadsAndCanBeRevalidated() throws Exception {
        execute(false, null);
        Path file = root.resolve(".platform/questions/sales.json");
        var json = new ObjectMapper();
        var legacy =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(Files.readString(file));
        legacy.remove("evidence");
        Files.writeString(file, json.writeValueAsString(legacy));
        assertEquals("EXECUTED", store.list("g1").get(0).status());
        assertEquals(null, store.list("g1").get(0).validation().evidence());
    }

    @Test
    void legacyClassificationIsIgnoredButOldConfirmationHashesRemainValid() throws Exception {
        for (boolean oldRequired : new boolean[] {true, false}) {
            Files.deleteIfExists(root.resolve(".platform/questions/sales.json"));
            Path source = root.resolve("knowledge/questions/sales.yml");
            Files.writeString(
                    source,
                    Files.readString(source).replace("required: true", "required: " + oldRequired));
            assertFalse(store.publishIssues("g1").isEmpty());
            var receipt = execute(false, null);
            var mapper = new ObjectMapper();
            var oldQuestion =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            mapper.valueToTree(store.requireQuestion("g1", "sales"));
            oldQuestion.put("required", oldRequired);
            String oldHash =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    mapper.writeValueAsString(oldQuestion)
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8)));
            Path saved = root.resolve(".platform/questions/sales.json");
            var oldReceipt =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            mapper.readTree(Files.readString(saved));
            oldReceipt.put("questionHash", oldHash);
            Files.writeString(saved, mapper.writeValueAsString(oldReceipt));
            store.decide("g1", "sales", receipt.validationId(), true, "alice");
            assertEquals("CONFIRMED", store.list("g1").get(0).status());
            assertTrue(store.publishIssues("g1").isEmpty());
            assertFalse(mapper.valueToTree(store.requireQuestion("g1", "sales")).has("required"));
        }
    }

    @Test
    void semanticChangesInvalidateConfirmationButReceiptAndExamplesDoNot() throws Exception {
        var receipt = execute(false, null);
        store.decide("g1", "sales", receipt.validationId(), true, "alice");
        assertEquals(receipt.modelHash(), store.modelHash(root));
        Files.writeString(
                root.resolve("models/order/metadata.yml"),
                "name: order\nproperties:\n  description: 新口径\n");
        assertEquals("STALE", store.list("g1").get(0).status());
        assertThrows(
                DatasetException.class,
                () -> store.decide("g1", "sales", receipt.validationId(), true, "alice"));
        assertFalse(store.publishIssues("g1").isEmpty());
    }

    @Test
    void questionChangeAndWrongExecutionIdCannotBeConfirmed() throws Exception {
        var receipt = execute(false, null);
        assertThrows(
                DatasetException.class, () -> store.decide("g1", "sales", "forged", true, "alice"));
        question("sales", "SELECT COUNT(*) AS total FROM order");
        assertEquals("STALE", store.list("g1").get(0).status());
        assertThrows(
                DatasetException.class,
                () -> store.decide("g1", "sales", receipt.validationId(), true, "alice"));
    }

    @Test
    void failuresAndTruncationCannotBeConfirmed() throws Exception {
        var failed = execute(false, "SQL rejected");
        assertThrows(
                DatasetException.class,
                () -> store.decide("g1", "sales", failed.validationId(), true, "alice"));
        var truncated = execute(true, null);
        assertThrows(
                DatasetException.class,
                () -> store.decide("g1", "sales", truncated.validationId(), true, "alice"));
    }

    @Test
    void rejectionRemovesExampleAndRequiresNewValidation() throws Exception {
        var receipt = execute(false, null);
        store.decide("g1", "sales", receipt.validationId(), true, "alice");
        store.decide("g1", "sales", receipt.validationId(), false, "alice");
        assertFalse(Files.exists(root.resolve("knowledge/sql/sales.md")));
        assertEquals("REJECTED", store.list("g1").get(0).status());
        assertThrows(
                DatasetException.class,
                () -> store.decide("g1", "sales", receipt.validationId(), true, "alice"));
    }

    @Test
    void publishedFilteringDropsStaleOptionalAndUnverifiedExamples() throws Exception {
        var receipt = execute(false, null);
        store.decide("g1", "sales", receipt.validationId(), true, "alice");
        Path prepared = home.resolve("prepared/knowledge/sql");
        Files.createDirectories(prepared);
        Files.writeString(prepared.resolve("sales.md"), "trusted");
        Files.writeString(prepared.resolve("unverified.md"), "untrusted");
        store.filterPublishedExamples("g1", home.resolve("prepared"));
        assertTrue(Files.exists(prepared.resolve("sales.md")));
        assertFalse(Files.exists(prepared.resolve("unverified.md")));
    }

    @Test
    void platformPathsAndConfirmedExamplesCannotBeAgentWritten() {
        assertThrows(
                DatasetException.class,
                () -> workspace.resolveWritable("g1", "knowledge/sql/forged.md"));
        assertThrows(
                DatasetException.class,
                () -> workspace.resolveWritable("g1", ".platform/questions/sales.json"));
        assertThrows(
                DatasetException.class, () -> workspace.resolveWritable("g1", "../escape.yml"));
    }

    @Test
    void sqlWriteAndMultiStatementAreRejected() {
        for (String sql :
                List.of(
                        "DELETE FROM order",
                        "SELECT 1; DROP TABLE order",
                        "SELECT * FROM order LIMIT 1",
                        "SELECT * INTO OUTFILE 'a' FROM order",
                        "WITH x AS (DELETE FROM order) SELECT * FROM x")) {
            assertThrows(DatasetException.class, () -> MdlQuestionStore.requireReadSql(sql));
        }
        assertDoesNotThrow(
                () ->
                        MdlQuestionStore.requireReadSql(
                                "WITH x AS (SELECT * FROM order) SELECT COUNT(*) FROM x"));
    }

    @Test
    void canonicalPathsCannotBypassProtectedDirectories() {
        assertThrows(
                DatasetException.class,
                () -> workspace.resolveWritable("g1", "./wren_project.yml"));
        assertThrows(
                DatasetException.class,
                () -> workspace.resolveWritable("g1", "knowledge/questions/../sql/forged.md"));
    }

    @Test
    void topQuestionUsesRankingWithoutWeakeningSqlGate() {
        assertDoesNotThrow(
                () ->
                        MdlQuestionStore.requireReadSql(
                                "WITH counts AS (SELECT project_name, COUNT(DISTINCT user_account)"
                                    + " AS visitors FROM clicks GROUP BY project_name), ranked AS"
                                    + " (SELECT project_name, visitors, DENSE_RANK() OVER (ORDER BY"
                                    + " visitors DESC) AS ranking FROM counts) SELECT project_name,"
                                    + " visitors FROM ranked WHERE ranking = 1"));
        assertThrows(
                DatasetException.class,
                () ->
                        MdlQuestionStore.requireReadSql(
                                "SELECT project_name FROM clicks ORDER BY project_name OFFSET 1"));
    }

    @Test
    void assetPlansMustExistAndBeReferencedBeforeConfirmation() throws Exception {
        Path file = root.resolve("knowledge/questions/sales.yml");
        Files.writeString(
                file,
                Files.readString(file)
                        + "modeling:\n"
                        + "  strategy: VIEW\n"
                        + "  reason: 统一有效订单口径\n"
                        + "  assets:\n"
                        + "    - kind: VIEW\n"
                        + "      name: valid_order\n");
        var question = store.requireQuestion("g1", "sales");
        assertFalse(store.coverage(root, question).ready());
        Files.createDirectories(root.resolve("views/valid_order"));
        Files.writeString(root.resolve("views/valid_order/metadata.yml"), "name: valid_order\n");
        assertFalse(store.coverage(root, question).ready());
        Files.writeString(file, Files.readString(file).replace("FROM order", "FROM valid_order"));
        assertTrue(store.coverage(root, store.requireQuestion("g1", "sales")).ready());
        Files.writeString(
                file,
                Files.readString(file)
                        .replace(
                                "FROM valid_order",
                                "FROM order WHERE ''FROM valid_order'' = ''FROM valid_order''"));
        assertFalse(store.coverage(root, store.requireQuestion("g1", "sales")).ready());
        var receipt = execute(false, null);
        assertThrows(
                DatasetException.class,
                () -> store.decide("g1", "sales", receipt.validationId(), true, "alice"));
    }

    @Test
    void legacyExamplesRemainValidButNewWritesNeedAnExplicitPlan() throws Exception {
        assertTrue(store.list("g1").get(0).coverage().ready());
        assertThrows(DatasetException.class, () -> store.requireModelingPlan(root, "sales"));
        Path file = root.resolve("knowledge/questions/sales.yml");
        Files.writeString(
                file,
                Files.readString(file)
                        + "modeling:\n  strategy: EXAMPLE\n  reason: 一次性核对查询，无需新建资产\n");
        assertDoesNotThrow(() -> store.requireModelingPlan(root, "sales"));
        assertTrue(store.list("g1").get(0).coverage().ready());
    }

    @Test
    void modelCoverageAndPlanChangesExpireReceipts() throws Exception {
        Path file = root.resolve("knowledge/questions/sales.yml");
        Files.writeString(
                file,
                Files.readString(file)
                        + "modeling:\n"
                        + "  strategy: MODEL\n"
                        + "  reason: 稳定销售指标\n"
                        + "  assets:\n"
                        + "    - kind: MODEL\n"
                        + "      name: order\n");
        assertTrue(store.list("g1").get(0).coverage().ready());
        var receipt = execute(false, null);
        store.decide("g1", "sales", receipt.validationId(), true, "alice");
        Files.writeString(file, Files.readString(file).replace("稳定销售指标", "净销售口径"));
        assertEquals("STALE", store.list("g1").get(0).status());
        Files.delete(root.resolve("models/order/metadata.yml"));
        assertFalse(store.list("g1").get(0).coverage().ready());
    }

    @Test
    void publishedStateUsesSnapshotAndIsIsolatedByGroup() throws Exception {
        var receipt = execute(false, null);
        store.decide("g1", "sales", receipt.validationId(), true, "alice");
        assertFalse(store.list("g1").get(0).published().available());
        Path published = root.getParent().resolve("published");
        Files.createDirectories(published.resolve("knowledge/questions"));
        Files.createDirectories(published.resolve("knowledge/sql"));
        Files.copy(
                root.resolve("knowledge/questions/sales.yml"),
                published.resolve("knowledge/questions/sales.yml"));
        Files.copy(
                root.resolve("knowledge/sql/sales.md"),
                published.resolve("knowledge/sql/sales.md"));
        assertTrue(store.list("g1").get(0).published().current());
        assertTrue(store.list("g2").isEmpty());
        question("sales", "SELECT COUNT(*) FROM order");
        assertTrue(store.list("g1").get(0).published().available());
        assertFalse(store.list("g1").get(0).published().current());
    }
}
