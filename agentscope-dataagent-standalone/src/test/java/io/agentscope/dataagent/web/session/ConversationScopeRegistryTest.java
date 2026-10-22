package io.agentscope.dataagent.web.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.web.persistence.jpa.ConversationDatasetScopeEntity;
import io.agentscope.dataagent.web.persistence.jpa.ConversationDatasetScopeRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConversationScopeRegistryTest {
    @Test
    void perMessageSnapshotRetainsNamesAfterConversationSelectionChanges() {
        var repository = mock(ConversationDatasetScopeRepository.class);
        var row = new ConversationDatasetScopeEntity("alice", "turn:m1", "[\"g1\"]");
        row.setSelectionJson(
                "{\"groupIds\":[\"g1\"],\"allGroups\":true,\"groupNames\":{\"g1\":\"销售库\"}}");
        when(repository.findAllById(any())).thenReturn(List.of(row));
        var registry = new ConversationScopeRegistry();
        ReflectionTestUtils.setField(registry, "repository", repository);
        registry.put("alice", "conversation", List.of("g2"));
        var user = new SessionTurnParser.TurnEntry("m1", null, "USER", "问题", 0, null, null, null);
        var old =
                new SessionTurnParser.TurnEntry("legacy", null, "USER", "旧问题", 0, null, null, null);
        var turns = registry.enrichTurns("alice", List.of(user, old));
        assertThat(turns.get(0).knowledgeScope().groupIds()).containsExactly("g1");
        assertThat(turns.get(0).knowledgeScope().groupNames()).containsEntry("g1", "销售库");
        assertThat(turns.get(0).knowledgeScope().allGroups()).isTrue();
        assertThat(turns.get(1).knowledgeScope()).isNull();
        assertThat(registry.get("alice", "conversation")).containsExactly("g2");
        verify(repository, times(1)).findAllById(any());
    }

    @Test
    void modelingPurposeIsPerUserConversationAndClearedByTheNextPlainRequest() {
        var registry = new ConversationScopeRegistry();
        registry.putModelingPurpose("alice", "c1", "question");
        assertThat(registry.modelingPurpose("alice", "c1")).isEqualTo("question");
        assertThat(registry.modelingPurpose("bob", "c1")).isNull();
        assertThat(registry.modelingPurpose("alice", "c2")).isNull();
        registry.putModelingPurpose("alice", "c1", null);
        assertThat(registry.modelingPurpose("alice", "c1")).isNull();
    }

    @Test
    void sameSessionNameIsIsolatedByUserAndTracksLatestAcceptedTurn() {
        var registry = new ConversationScopeRegistry();
        registry.put("alice", "same", List.of("g1"));
        registry.put("bob", "same", List.of("g2"));
        assertThat(registry.get("alice", "same")).containsExactly("g1");
        assertThat(registry.get("bob", "same")).containsExactly("g2");
        registry.put("alice", "same", List.of("g1"));
        registry.put("alice", "same", List.of("g2"));
        assertThat(registry.get("alice", "same")).containsExactly("g2");
        assertThat(registry.get("bob", "same")).containsExactly("g2");
        registry.put("alice", "same", List.of());
        assertThat(registry.get("alice", "same")).isEmpty();
    }

    @Test
    void persistedSelectionAndAllModeSurviveRestartAndCanChangeNextTurn() {
        var repository = mock(ConversationDatasetScopeRepository.class);
        var rows = new ConcurrentHashMap<String, ConversationDatasetScopeEntity>();
        when(repository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        when(repository.save(any()))
                .thenAnswer(
                        inv -> {
                            ConversationDatasetScopeEntity row = inv.getArgument(0);
                            rows.put(ConversationDatasetScopeEntity.key("alice", "s1"), row);
                            return row;
                        });
        var first = new ConversationScopeRegistry();
        ReflectionTestUtils.setField(first, "repository", repository);
        first.putSelection(
                "alice",
                "s1",
                new ConversationScopeRegistry.Selection(
                        List.of("g1"), true, java.util.Map.of("g1", "销售库")));
        var restarted = new ConversationScopeRegistry();
        ReflectionTestUtils.setField(restarted, "repository", repository);
        assertThat(restarted.get("alice", "s1")).containsExactly("g1");
        assertThat(restarted.getSelection("alice", "s1").allGroups()).isTrue();
        assertThat(restarted.getSelection("alice", "s1").groupNames()).containsEntry("g1", "销售库");
        restarted.put("alice", "s1", List.of("g2"));
        assertThat(restarted.get("alice", "s1")).containsExactly("g2");
        verify(repository, times(2)).save(any());
    }

    @Test
    void concurrentSnapshotsAreWrittenAtomically() throws Exception {
        var registry = new ConversationScopeRegistry();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures =
                    List.of("g1", "g2").stream()
                            .map(
                                    id ->
                                            executor.submit(
                                                    () -> {
                                                        start.await();
                                                        try {
                                                            registry.put(
                                                                    "alice", "s1", List.of(id));
                                                            return true;
                                                        } catch (DatasetException expected) {
                                                            return false;
                                                        }
                                                    }))
                            .toList();
            start.countDown();
            int success = 0;
            for (var f : futures) if (f.get(5, TimeUnit.SECONDS)) success++;
            assertThat(success).isEqualTo(2);
            assertThat(registry.get("alice", "s1")).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
