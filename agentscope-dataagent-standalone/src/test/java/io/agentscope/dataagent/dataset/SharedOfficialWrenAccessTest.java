package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedOfficialWrenAccessTest {
    @TempDir Path home;

    @Test
    void sharedQueryUsesPublishedProjectButCannotReadDraftAndRevocationTakesEffect()
            throws Exception {
        var repository = mock(DatasetGroupRepository.class);
        var grants = mock(KnowledgeBaseGrantRepository.class);
        var users = mock(UserStore.class);
        var operations =
                new KnowledgeBaseOperations(
                        new WrenProperties("wren", home.toString(), "test", "duckdb", 30));
        var acl = new KnowledgeBaseAccessService(repository, grants, users, operations);
        var group = new DatasetGroupEntity("g", "owner", "shared", "");
        group.setMdlVersion(1);
        when(repository.findById("g")).thenReturn(Optional.of(group));
        when(users.findById("reader"))
                .thenReturn(
                        Optional.of(
                                new UserStore.UserRecord("reader", "reader", "", List.of("user"))));
        var grant = new KnowledgeBaseGrantEntity("g", "reader");
        grant.grant("QUERY", "owner");
        when(grants.findByGroupIdAndUserId("g", "reader")).thenReturn(Optional.of(grant));
        var groups =
                new DatasetGroupService(
                        repository, null, null, null, null, null, null, acl, null, operations);
        var scopes = new ConversationScopeRegistry();
        var access = new WrenQueryAccess(groups, scopes);
        var context = mock(RuntimeContext.class);
        when(context.getUserId()).thenReturn("reader");
        when(context.getSessionId()).thenReturn("session");
        scopes.put("reader", "session", List.of("g"));
        assertThat(access.resolve(context, "g").getOwnerId()).isEqualTo("owner");
        var props = new WrenProperties("wren", home.toString(), "test", "duckdb", 30);
        Path published = props.groupRoot("g").resolve("published/target");
        Files.createDirectories(published);
        Files.writeString(published.resolve("mdl.json"), "{\"models\":[]}");
        var projects = new WrenPublishedProject(props, groups);
        assertThat(Files.readString(projects.prepare("reader", "g").resolve("target/mdl.json")))
                .isEqualTo("{\"models\":[]}");
        var workspace = mock(MdlWorkspaceService.class);
        var modeling =
                new WrenProjectAccess(
                        groups, workspace, mock(DatasetKnowledgeRepository.class), scopes);
        assertThatThrownBy(() -> modeling.resolve("reader", "g"))
                .isInstanceOf(DatasetException.class);
        verifyNoInteractions(workspace);
        when(grants.findByGroupIdAndUserId("g", "reader")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.resolve(context, "g")).isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> projects.prepare("reader", "g"))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> projects.likedProject("reader", "g"))
                .isInstanceOf(DatasetException.class);
        scopes.put("reader", "session", List.of());
        assertThat(access.visible(context)).isEmpty();
        when(context.getUserId()).thenReturn("other");
        assertThat(access.visible(context)).isEmpty();
    }
}
