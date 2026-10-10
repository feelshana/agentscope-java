package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import java.util.List;
import org.junit.jupiter.api.Test;

class MdlQuestionServiceTest {
    private final DatasetGroupService groups = mock(DatasetGroupService.class);
    private final MdlQuestionStore store = mock(MdlQuestionStore.class);
    private final MdlPublishService publisher = mock(MdlPublishService.class);
    private final MdlQuestionService service =
            new MdlQuestionService(groups, store, publisher, mock(WrenQueryGateway.class));

    @Test
    void narrowedScopeCannotReadValidateOrDecideAnotherGroup() {
        DatasetScope scope = new DatasetScope("alice", List.of("a"));
        assertThrows(DatasetException.class, () -> service.list(scope, "b"));
        assertThrows(DatasetException.class, () -> service.validate(scope, "b", "sales"));
        assertThrows(DatasetException.class, () -> service.decide(scope, "b", "sales", "id", true));
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
                () -> service.decide(new DatasetScope("alice"), "b", "sales", "id", true));
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
