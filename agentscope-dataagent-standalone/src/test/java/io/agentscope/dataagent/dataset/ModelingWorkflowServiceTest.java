package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelingWorkflowServiceTest {
    @TempDir Path home;
    private final DatasetGroupService groups = mock(DatasetGroupService.class);
    private final MdlPublishService publisher = mock(MdlPublishService.class);
    private final MdlCatalog catalog = mock(MdlCatalog.class);
    private final DatasetScope scope = new DatasetScope("alice", List.of("g"));
    private MdlWorkspaceService workspace;
    private MdlQuestionStore questions;
    private ModelingWorkflowStore checks;
    private ModelingWorkflowService service;
    private DatasetGroupEntity group;
    private Path root;

    @BeforeEach
    void setup() throws Exception {
        workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""));
        root = workspace.workspaceRoot("g");
        Files.createDirectories(root.resolve("models/order"));
        Files.writeString(root.resolve("models/order/metadata.yml"), "name: order\n");
        questions = new MdlQuestionStore(workspace);
        checks = new ModelingWorkflowStore(workspace, questions);
        service =
                new ModelingWorkflowService(
                        groups, publisher, catalog, questions, checks, workspace);
        group = new DatasetGroupEntity("g", "alice", "orders", "");
        group.setMdlVersion(1);
        group.setMdlState("PUBLISHED");
        when(groups.getGroup("alice", "g")).thenReturn(group);
        when(groups.listDatasets("alice", "g")).thenReturn(List.of(mock(DatasetEntity.class)));
        when(catalog.load("g"))
                .thenReturn(
                        Optional.of(
                                new MdlCatalog.GroupMdl(
                                        "g", "orders", 1, List.of(), List.of(), List.of())));
        preview(false);
    }

    private void preview(boolean changed) {
        var current = new MdlPublishService.MdlFile("models/order/metadata.yml", "name: order");
        var old =
                new MdlPublishService.MdlFile(
                        "models/order/metadata.yml", changed ? "name: previous" : "name: order");
        when(publisher.preview("g"))
                .thenReturn(
                        new MdlPublishService.MdlPreview(
                                List.of(current),
                                List.of(old),
                                List.of(),
                                group.getMdlState(),
                                group.getMdlVersion(),
                                null,
                                changed));
    }

    private void question(String definition, String sql, boolean required) throws Exception {
        Files.createDirectories(root.resolve("knowledge/questions"));
        Files.writeString(
                root.resolve("knowledge/questions/sales.yml"),
                "question: 销售额是多少\ndefinition: '"
                        + definition
                        + "'\nsql: '"
                        + sql
                        + "'\nrequired: "
                        + required
                        + "\n");
    }

    private void engineering(boolean ok) {
        checks.record(
                "g",
                new MdlPublishService.MdlValidation(
                        ok,
                        ok
                                ? List.of()
                                : List.of(
                                        new MdlPublishService.MdlIssue(
                                                "error", "invalid model", "wren")),
                        "",
                        List.of()));
    }

    private MdlQuestionStore.Receipt execute(boolean truncated) throws Exception {
        return questions.saveExecution(
                "g",
                questions.requireQuestion("g", "sales"),
                questions.modelHash(root),
                new ObjectMapper().readTree("{\"columns\":[\"total\"],\"rows\":[{\"total\":100}]}"),
                null,
                truncated);
    }

    @Test
    void basePublishedModelIsQueryableWithoutRequiringQuestions() {
        var state = service.snapshot(scope, "g");
        assertTrue(state.queryAvailable());
        assertEquals("COMPLETE", state.stage());
        assertEquals("ADD_QUESTIONS", state.nextAction().type());
        assertEquals(0, state.questionSummary().total());
    }

    @Test
    void noDataAndFailedInitializationNeverClaimQueryAvailability() {
        when(groups.listDatasets("alice", "g")).thenReturn(List.of());
        assertEquals("PREPARE_DATA", service.snapshot(scope, "g").nextAction().type());
        assertFalse(service.snapshot(scope, "g").queryAvailable());
        assertFalse(service.snapshot(scope, "g").canPublish());
        when(groups.listDatasets("alice", "g")).thenReturn(List.of(mock(DatasetEntity.class)));
        when(catalog.load("g")).thenReturn(Optional.empty());
        group.setMdlState("FAILED");
        assertEquals("INITIALIZE", service.snapshot(scope, "g").nextAction().type());
        assertFalse(service.snapshot(scope, "g").queryAvailable());
    }

    @Test
    void sameQuestionMovesFromRequirementThroughValidationConfirmationAndPublication()
            throws Exception {
        preview(true);
        question("", "", true);
        assertEquals("MODELING", service.snapshot(scope, "g").stage());
        question("有效订单，元", "SELECT SUM(amount) FROM order", true);
        assertEquals("VALIDATION", service.snapshot(scope, "g").stage());
        engineering(true);
        assertEquals("VALIDATION", service.snapshot(scope, "g").stage());
        var receipt = execute(false);
        assertEquals("CONFIRMATION", service.snapshot(scope, "g").stage());
        assertFalse(service.snapshot(scope, "g").canPublish());
        questions.decide("g", "sales", receipt.validationId(), true, "alice");
        assertEquals("PUBLICATION", service.snapshot(scope, "g").stage());
        assertTrue(service.snapshot(scope, "g").canPublish());
        preview(false);
        assertEquals("COMPLETE", service.snapshot(scope, "g").stage());
        assertEquals("QUERY", service.snapshot(scope, "g").nextAction().type());
        assertEquals("sales", questions.list("g").get(0).question().id());
    }

    @Test
    void engineeringPassDoesNotSubstituteForBusinessConfirmation() throws Exception {
        preview(true);
        question("有效订单", "SELECT SUM(amount) FROM order", true);
        engineering(true);
        assertFalse(service.snapshot(scope, "g").canPublish());
        assertEquals("VALIDATION", service.snapshot(scope, "g").stage());
        execute(true);
        assertEquals("VALIDATION", service.snapshot(scope, "g").stage());
        assertEquals(0, service.snapshot(scope, "g").questionSummary().awaitingConfirmation());
    }

    @Test
    void modelEditExpiresEngineeringAndBusinessReceiptsButKeepsPublishedQueries() throws Exception {
        preview(true);
        question("有效订单", "SELECT SUM(amount) FROM order", true);
        engineering(true);
        var receipt = execute(false);
        questions.decide("g", "sales", receipt.validationId(), true, "alice");
        Files.writeString(root.resolve("models/order/metadata.yml"), "name: changed\n");
        var state = service.snapshot(scope, "g");
        assertEquals("NOT_CHECKED", state.engineeringStatus());
        assertEquals("VALIDATION", state.stage());
        assertEquals(0, state.questionSummary().confirmed());
        assertTrue(state.queryAvailable());
        assertEquals(1, state.publishedVersion());
        assertFalse(state.canPublish());
    }

    @Test
    void legacyFalseQuestionsRequireAcceptanceAndDeletedAssetsAppearInSummary() throws Exception {
        question("", "", false);
        engineering(true);
        when(publisher.preview("g"))
                .thenReturn(
                        new MdlPublishService.MdlPreview(
                                List.of(
                                        new MdlPublishService.MdlFile(
                                                "models/order/metadata.yml", "name: order")),
                                List.of(
                                        new MdlPublishService.MdlFile(
                                                "models/order/metadata.yml", "name: order"),
                                        new MdlPublishService.MdlFile(
                                                "views/removed/metadata.yml", "name: removed")),
                                List.of(),
                                "PUBLISHED",
                                1,
                                null,
                                true));
        var state = service.snapshot(scope, "g");
        assertEquals("MODELING", state.stage());
        assertEquals(1, state.questionSummary().incomplete());
        assertFalse(state.canPublish());
        assertEquals("REMOVED", state.changedAssets().get(0).kind());
        assertEquals("业务视图：removed", state.changedAssets().get(0).label());
    }

    @Test
    void legacyFalseQuestionsBlockUntilSqlExecutionAndConfirmation() throws Exception {
        question("有效订单，元", "SELECT SUM(amount) FROM order", false);
        engineering(true);
        var initial = service.snapshot(scope, "g");
        assertEquals("VALIDATION", initial.stage());
        assertEquals(1, initial.questionSummary().needsValidation());
        assertEquals(1, initial.questionSummary().total());
        assertFalse(initial.canPublish());
        assertEquals("QUESTION_VALIDATION", initial.blockers().get(0).code());
        var receipt = execute(false);
        var executed = service.snapshot(scope, "g");
        assertEquals("CONFIRMATION", executed.stage());
        assertEquals(1, executed.questionSummary().awaitingConfirmation());
        assertFalse(executed.canPublish());
        questions.decide("g", "sales", receipt.validationId(), true, "alice");
        assertEquals("COMPLETE", service.snapshot(scope, "g").stage());
    }

    @Test
    void allQuestionsBlockRegardlessOfLegacyClassification() throws Exception {
        question("有效订单，元", "SELECT SUM(amount) FROM order", true);
        Files.writeString(
                root.resolve("knowledge/questions/customers.yml"),
                "question: 客户数\n"
                        + "definition: 正式客户\n"
                        + "sql: SELECT COUNT(*) FROM order\n"
                        + "required: false\n");
        engineering(true);
        var initial = service.snapshot(scope, "g");
        assertEquals(2, initial.questionSummary().total());
        assertEquals(2, initial.questionSummary().needsValidation());
        assertEquals(2, initial.blockers().size());
        var receipt = execute(false);
        questions.decide("g", "sales", receipt.validationId(), true, "alice");
        var state = service.snapshot(scope, "g");
        assertEquals("VALIDATION", state.stage());
        assertEquals(1, state.questionSummary().confirmed());
        assertEquals(1, state.questionSummary().needsValidation());
        assertFalse(state.canPublish());
        assertEquals(1, state.blockers().size());
    }

    @Test
    void failedEngineeringIsDistinctFromPendingBusinessConfirmation() {
        preview(true);
        engineering(false);
        var state = service.snapshot(scope, "g");
        assertEquals("FAILED", state.engineeringStatus());
        assertEquals("VALIDATE", state.nextAction().type());
        assertTrue(state.blockers().stream().anyMatch(b -> b.code().equals("ENGINEERING_FAILED")));
        assertFalse(state.canPublish());
    }

    @Test
    void missingOrNarrowedTenantCannotAccessFiles() {
        assertThrows(DatasetException.class, () -> service.snapshot(null, "g"));
        assertThrows(
                DatasetException.class,
                () -> service.snapshot(new DatasetScope("alice", List.of("other")), "g"));
        verifyNoInteractions(publisher, catalog);
    }

    @Test
    void foreignOwnerIsRejectedBeforeWorkspaceReads() {
        when(groups.getGroup("bob", "g")).thenThrow(new DatasetException("not found", 404));
        assertThrows(DatasetException.class, () -> service.snapshot(new DatasetScope("bob"), "g"));
        verifyNoInteractions(publisher, catalog);
    }
}
