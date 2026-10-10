package io.agentscope.dataagent.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.MdlSuggestionService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class SemanticModelingCubeRetirementTest {
    @Test
    void retiredRoutesReturnGoneOnlyAfterCheckingKnowledgeBaseOwnership() {
        var groups = mock(DatasetGroupService.class);
        var modeling = mock(MdlSuggestionService.class);
        var controller =
                new SemanticModelingController(
                        groups,
                        null,
                        modeling,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new ObjectMapper());
        var alice = mock(Authentication.class);
        when(alice.getPrincipal()).thenReturn("alice");
        when(groups.getGroup("alice", "g"))
                .thenReturn(new DatasetGroupEntity("g", "alice", "知识库", ""));
        assertEquals(
                410,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.suggestCubes("g", alice).block())
                        .getStatusCode()
                        .value());
        assertEquals(
                410,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.createCube("g", null, alice).block())
                        .getStatusCode()
                        .value());
        assertEquals(
                410,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.updateCube("g", "c", null, alice).block())
                        .getStatusCode()
                        .value());
        assertEquals(
                410,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.deleteCube("g", "c", alice).block())
                        .getStatusCode()
                        .value());
        var bob = mock(Authentication.class);
        when(bob.getPrincipal()).thenReturn("bob");
        when(groups.getGroup("bob", "g")).thenThrow(new DatasetException("知识库不存在", 404));
        assertEquals(
                404,
                assertThrows(
                                ResponseStatusException.class,
                                () -> controller.createCube("g", null, bob).block())
                        .getStatusCode()
                        .value());
        verifyNoInteractions(modeling);
    }
}
