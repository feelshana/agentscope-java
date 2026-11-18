package io.agentscope.dataagent.web.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.gateway.HarnessGateway;
import io.agentscope.dataagent.runtime.session.ConversationScopeRegistry;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionHistoryEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionHistoryRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionMessageRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ConversationHistoryTitlesTest {
    @TempDir Path home;
    final DatasetGroupRepository groups = mock(DatasetGroupRepository.class);
    final ConversationScopeRegistry scopes = new ConversationScopeRegistry();

    ConversationHistoryTitles titles() {
        return new ConversationHistoryTitles(
                groups, scopes, new WrenProperties("wren", home.toString(), "test", "mysql", 10));
    }

    SessionEntry entry(String route, String conversation) {
        return new SessionEntry(
                "key",
                "opaque-framework-id",
                "session",
                null,
                SessionKind.MAIN,
                null,
                0,
                1,
                2,
                null,
                null,
                "chatui|u:user|g:" + conversation + "|x:agentId=" + route,
                "user");
    }

    @Test
    void classifiesOnlyExactServerRoutingWithExplicitLegacyFallback() {
        assertThat(HistoryAssistant.resolve("chatui|x:agentId=dashboard-agent", "data-agent"))
                .isEqualTo(HistoryAssistant.DASHBOARD);
        assertThat(HistoryAssistant.resolve("chatui|x:agentId=data-agent-more", "data-agent"))
                .isEqualTo(HistoryAssistant.OTHER);
        assertThat(HistoryAssistant.resolve("chatui|x:agentId=uca-user-data-agent", "data-agent"))
                .isEqualTo(HistoryAssistant.OTHER);
        assertThat(HistoryAssistant.resolve(null, "data-agent")).isEqualTo(HistoryAssistant.QUERY);
        assertThat(HistoryAssistant.resolve(null, "opaque-framework-id"))
                .isEqualTo(HistoryAssistant.OTHER);
    }

    @Test
    void projectsKnowledgeBaseNamesAndFallsBackWithoutGuessingFromPrompt() {
        when(groups.findById("group"))
                .thenReturn(Optional.of(new DatasetGroupEntity("group", "user", "经营分析", null)));
        assertThat(titles().title(entry("modeling-agent", "modeling-group")))
                .isEqualTo("经营分析 · 建模对话");
        scopes.put("user", "legacy-conversation", List.of("group"));
        assertThat(titles().title(entry("modeling-agent", "legacy-conversation")))
                .isEqualTo("经营分析 · 建模对话");
        assertThat(titles().title(entry("modeling-agent", "unbound"))).isEqualTo("未关联知识库 · 建模对话");
        assertThat(titles().title(entry("modeling-agent", "modeling-deleted")))
                .isEqualTo("知识库已删除 · 建模对话");
        assertThat(titles().title(entry("data-agent", "modeling-group"))).isNull();
    }

    @Test
    void sharedModelingHistoryPrefersCapturedNameAndDoesNotRevealLaterForeignRenames() {
        when(groups.findById("shared"))
                .thenReturn(
                        Optional.of(
                                new DatasetGroupEntity(
                                        "shared", "another-owner", "后来改的私有名称", null)));
        scopes.putSelection(
                "user",
                "modeling-shared",
                new ConversationScopeRegistry.Selection(
                        List.of("shared"), false, java.util.Map.of("shared", "当时的共享名称")));
        assertThat(titles().title(entry("modeling-agent", "modeling-shared")))
                .isEqualTo("当时的共享名称 · 建模对话");
        scopes.put("user", "modeling-shared", List.of("shared"));
        assertThat(titles().title(entry("modeling-agent", "modeling-shared")))
                .isEqualTo("知识库名称未记录 · 建模对话");
    }

    @Test
    void readsOnlySmallMatchingDashboardMetadataAndHandlesMissingCorruptOrUnsafeReferences()
            throws Exception {
        String id = "27986791-b152-4ff0-b137-35e4686efbb8";
        Path file = home.resolve("dashboards").resolve(id).resolve("asset.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"id\":\"" + id + "\",\"title\":\"零售驾驶舱\"}");
        assertThat(titles().title(entry("dashboard-agent", id))).isEqualTo("零售驾驶舱 · 看板对话");
        Files.writeString(file, "{\"id\":\"another\",\"title\":\"不得混入\"}");
        assertThat(titles().title(entry("dashboard-agent", id))).isEqualTo("看板记录不可用 · 看板对话");
        Files.writeString(file, "bad json");
        assertThat(titles().title(entry("dashboard-agent", id))).isEqualTo("看板记录不可用 · 看板对话");
        Files.delete(file);
        assertThat(titles().title(entry("dashboard-agent", id))).isEqualTo("看板记录不可用 · 看板对话");
        assertThat(titles().title(entry("dashboard-agent", "../../outside")))
                .isEqualTo("看板记录不可用 · 看板对话");
        verifyNoInteractions(groups);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void refreshesTitleWithMissingOrUnchangedTranscript(boolean unchangedTranscript)
            throws Exception {
        var source = new SessionRegistryEntity();
        source.setSessionKey("key");
        source.setAgentId("opaque-framework-id");
        source.setSessionId("session");
        source.setUserId("user");
        source.setKind("main");
        source.setGateKey(entry("modeling-agent", "modeling-group").gateKey());
        var registry = mock(SessionRegistryRepository.class);
        when(registry.findById("key")).thenReturn(Optional.of(source));
        when(registry.existsById("key")).thenReturn(true);
        var history = mock(SessionHistoryRepository.class);
        var previous = new SessionHistoryEntity();
        previous.sessionKey = "key";
        previous.title = "请加载官方技能";
        previous.activityMs = 123;
        when(history.findById("key")).thenReturn(Optional.of(previous));
        var messages = mock(SessionMessageRepository.class);
        var bootstrap = mock(DataAgentBootstrap.class);
        when(bootstrap.gateway()).thenReturn(mock(HarnessGateway.class));
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(groups.findById("group"))
                .thenReturn(Optional.of(new DatasetGroupEntity("group", "user", "经营分析", null)));
        if (unchangedTranscript) {
            Path transcript = home.resolve("unchanged-session.json");
            Files.writeString(transcript, "[]");
            source.setSessionFilePath(transcript.toString());
            previous.sessionId = "session";
            previous.sourcePath = transcript.toString();
            previous.fileStamp = Files.getLastModifiedTime(transcript).toMillis();
            previous.fileSize = Files.size(transcript);
        }
        var worker =
                new SessionHistoryService(
                        bootstrap,
                        registry,
                        history,
                        messages,
                        manager,
                        scopes,
                        Optional.of(titles()));
        try {
            worker.refresh("key");
            assertThat(previous.title).isEqualTo("经营分析 · 建模对话");
            assertThat(previous.activityMs).isEqualTo(123);
            // Unchanged files are reindexed once per process to remove old internal projections.
            verify(history, org.mockito.Mockito.times(unchangedTranscript ? 2 : 1)).save(previous);
            worker.refresh("key");
            verify(history, org.mockito.Mockito.times(unchangedTranscript ? 2 : 1)).save(previous);
            if (!unchangedTranscript) verifyNoInteractions(messages);
        } finally {
            worker.stop();
        }
    }
}
