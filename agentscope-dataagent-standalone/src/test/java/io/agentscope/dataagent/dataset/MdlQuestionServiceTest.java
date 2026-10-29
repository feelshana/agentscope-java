package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MdlQuestionServiceTest {
    private final DatasetGroupService groups = mock(DatasetGroupService.class);
    private final MdlQuestionStore store = mock(MdlQuestionStore.class);
    private final MdlPublishService publisher = mock(MdlPublishService.class);
    private final MdlQuestionService service =
            new MdlQuestionService(groups, store, publisher, mock(WrenQueryGateway.class));

    /** Tracks whether the workspace lock is held while the store's locked section runs. */
    private boolean insideLock;

    @BeforeEach
    void runLockedSectionsInline() {
        when(store.locked(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call -> {
                            insideLock = true;
                            try {
                                return ((java.util.function.Supplier<?>) call.getArgument(1)).get();
                            } finally {
                                insideLock = false;
                            }
                        });
    }

    /**
     * 2026-10-25: a question confirmed while a publish waited for the workspace lock bumped the
     * modeling revision after the lock was released, so the publish covered the old revision and
     * querying stayed blocked with nothing left to publish. The bump now happens inside the lock.
     */
    @Test
    void questionChangesBumpTheModelingRevisionInsideTheWorkspaceLock() {
        var scope = new DatasetScope("alice");
        java.util.List<Boolean> locked = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> locked.add(insideLock))
                .when(groups)
                .markMdlDirty("alice", "a");
        service.decide(scope, "a", "q-1", true);
        service.correctSql(scope, "a", "q-1", "r1", "SELECT 1");
        service.archive(scope, "a", "q-1", "r1");
        org.assertj.core.api.Assertions.assertThat(locked).containsExactly(true, true, true);
    }

    /**
     * 2026-10-28: "department is not like '%x%'" was saved and confirmed, then dropped by the
     * publish dry-plan without notice. Saving and confirming now refuse it up front.
     */
    @Test
    void sqlThatDoesNotPlanIsRefusedOnSaveAndConfirm() {
        String bad = "SELECT 1 FROM t WHERE d is not like '%x%'";
        String error =
                "Error: [INVALID_SQL] Invalid expression / Unexpected token. Line 1, Col: 27.";
        when(publisher.planDraftSql("a", bad)).thenReturn(error);
        when(store.requireQuestion("a", "q-1"))
                .thenReturn(new MdlQuestionStore.Question("q-1", "访问量", "口径", bad));
        var scope = new DatasetScope("alice");

        var save =
                assertThrows(
                        DatasetException.class,
                        () -> service.correctSql(scope, "a", "q-1", "r1", bad));
        org.assertj.core.api.Assertions.assertThat(save.getMessage())
                .startsWith("SQL 检查未通过，未保存：")
                .contains("Col: 27");
        var confirm =
                assertThrows(DatasetException.class, () -> service.decide(scope, "a", "q-1", true));
        org.assertj.core.api.Assertions.assertThat(confirm.getMessage())
                .startsWith("SQL 检查未通过，不能确认：");
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .correctSql(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .decide("a", "q-1", true, "alice");

        // Returning a question needs no plan.
        service.decide(scope, "a", "q-1", false);
        org.mockito.Mockito.verify(store).decide("a", "q-1", false, "alice");
    }

    @Test
    void planErrorsSkipCliNotesBeforeTheOfficialError() {
        org.assertj.core.api.Assertions.assertThat(
                        MdlPublishService.planError(
                                "Note: no profile bound to this project.\n"
                                        + "Error: [INVALID_SQL] Unexpected token. Line 1, Col: 9.\n"
                                        + "  SELECT ... phase=SQL_PLANNING"))
                .isEqualTo("Error: [INVALID_SQL] Unexpected token. Line 1, Col: 9.");
        org.assertj.core.api.Assertions.assertThat(MdlPublishService.planError("boom\nmore"))
                .isEqualTo("boom");
    }

    @Test
    void questionValidationPassesTheSameTimeWindowGateBeforeExecution() {
        var windows = mock(FactQueryWindowPolicy.class);
        var gated =
                new MdlQuestionService(
                        groups, store, publisher, mock(WrenQueryGateway.class), windows);
        String sql = "SELECT SUM(amount) FROM orders";
        when(store.requireQuestion("a", "q-1"))
                .thenReturn(new MdlQuestionStore.Question("q-1", "营收", "全部订单", sql));
        org.mockito.Mockito.doThrow(new DatasetException("SQL_TIME_WINDOW_REJECTED", 422))
                .when(windows)
                .requireAllowed(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("a"),
                        org.mockito.ArgumentMatchers.eq(sql),
                        org.mockito.ArgumentMatchers.eq("question validate"));
        var refused =
                assertThrows(
                        DatasetException.class,
                        () -> gated.validate(new DatasetScope("alice", List.of("a")), "a", "q-1"));
        org.junit.jupiter.api.Assertions.assertTrue(
                refused.getMessage().contains("SQL_TIME_WINDOW_REJECTED"));
        verifyNoInteractions(publisher);
    }

    @Test
    void confirmationPassesTheTimeWindowGateBeforeWritingButReturnDoesNot() {
        var windows = mock(FactQueryWindowPolicy.class);
        var gated =
                new MdlQuestionService(
                        groups, store, publisher, mock(WrenQueryGateway.class), windows);
        String sql = "SELECT SUM(amount) FROM orders";
        when(store.requireQuestion("a", "q-1"))
                .thenReturn(new MdlQuestionStore.Question("q-1", "营收", "全部订单", sql));
        org.mockito.Mockito.doThrow(new DatasetException("SQL_TIME_WINDOW_REJECTED", 422))
                .when(windows)
                .requireAllowed(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq("a"),
                        org.mockito.ArgumentMatchers.eq(sql),
                        org.mockito.ArgumentMatchers.eq("question confirm"));
        DatasetScope scope = new DatasetScope("alice", List.of("a"));
        assertThrows(DatasetException.class, () -> gated.decide(scope, "a", "q-1", true));
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .decide("a", "q-1", true, "alice");
        gated.decide(scope, "a", "q-1", false);
        org.mockito.Mockito.verify(store).decide("a", "q-1", false, "alice");
    }

    @Test
    void suggestionsAreScopedAndSkipExistingQuestions() {
        var suggester = mock(QuestionSuggestionService.class);
        var suggesting =
                new MdlQuestionService(
                        groups, store, publisher, mock(WrenQueryGateway.class), null, suggester);
        when(store.list("a"))
                .thenReturn(
                        List.of(
                                new MdlQuestionStore.Review(
                                        new MdlQuestionStore.Question("q-1", "营收", "", ""),
                                        "DRAFT",
                                        null)));
        when(suggester.suggest("a", List.of("营收"))).thenReturn(List.of("10月1日访问量"));
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("10月1日访问量"),
                suggesting.suggestions(new DatasetScope("alice", List.of("a")), "a"));
        assertThrows(
                DatasetException.class,
                () -> suggesting.suggestions(new DatasetScope("alice", List.of("a")), "b"));
        when(groups.getGroup("alice", "c")).thenThrow(new DatasetException("not found", 404));
        assertThrows(
                DatasetException.class,
                () -> suggesting.suggestions(new DatasetScope("alice"), "c"));
        org.mockito.Mockito.verify(suggester, org.mockito.Mockito.never())
                .suggest(org.mockito.ArgumentMatchers.eq("b"), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(suggester, org.mockito.Mockito.never())
                .suggest(org.mockito.ArgumentMatchers.eq("c"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void narrowedScopeCannotReadValidateOrDecideAnotherGroup() {
        DatasetScope scope = new DatasetScope("alice", List.of("a"));
        assertThrows(DatasetException.class, () -> service.list(scope, "b"));
        assertThrows(DatasetException.class, () -> service.validate(scope, "b", "sales"));
        assertThrows(DatasetException.class, () -> service.decide(scope, "b", "sales", true));
        assertThrows(
                DatasetException.class,
                () -> service.correctSql(scope, "b", "sales", "rev", "SELECT 1"));
        assertThrows(DatasetException.class, () -> service.archive(scope, "b", "sales", "rev"));
        verifyNoInteractions(store, publisher);
    }

    @Test
    void foreignOwnerIsCheckedBeforeReadingAnyFiles() {
        when(groups.getGroup("alice", "b")).thenThrow(new DatasetException("not found", 404));
        assertThrows(DatasetException.class, () -> service.list(new DatasetScope("alice"), "b"));
        assertThrows(
                DatasetException.class,
                () -> service.validate(new DatasetScope("alice"), "b", "sales"));
        assertThrows(
                DatasetException.class,
                () -> service.decide(new DatasetScope("alice"), "b", "sales", true));
        assertThrows(
                DatasetException.class,
                () ->
                        service.correctSql(
                                new DatasetScope("alice"), "b", "sales", "rev", "SELECT 1"));
        assertThrows(
                DatasetException.class,
                () -> service.archive(new DatasetScope("alice"), "b", "sales", "rev"));
        verifyNoInteractions(store, publisher);
    }

    @Test
    void missingTenantCannotUseQuestionTools() {
        assertThrows(DatasetException.class, () -> service.list(null, "a"));
        assertThrows(
                DatasetException.class,
                () -> service.correctSql(null, "a", "sales", "rev", "SELECT 1"));
        assertThrows(DatasetException.class, () -> service.archive(null, "a", "sales", "rev"));
        verifyNoInteractions(groups, store, publisher);
    }
}
