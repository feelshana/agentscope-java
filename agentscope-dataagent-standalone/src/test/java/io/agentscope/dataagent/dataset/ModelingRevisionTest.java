package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantEntity;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import java.util.List;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

class ModelingRevisionTest {
    @Test
    void oldPublicationCannotCoverNewMutationAndStaleMergeCannotResetRevision() {
        try (var factory =
                        new Configuration()
                                .addAnnotatedClass(DatasetGroupEntity.class)
                                .addAnnotatedClass(KnowledgeBaseGrantEntity.class)
                                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                                .setProperty(
                                        "hibernate.connection.url",
                                        "jdbc:h2:mem:modeling-revision;DB_CLOSE_DELAY=-1")
                                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                                .buildSessionFactory();
                var em = factory.createEntityManager()) {
            var repo = new JpaRepositoryFactory(em).getRepository(DatasetGroupRepository.class);
            em.getTransaction().begin();
            var group = new DatasetGroupEntity("g", "alice", "example", "");
            group.setMdlVersion(1);
            em.persist(group);
            em.flush();
            repo.requireModeling("g");
            var stale = em.find(DatasetGroupEntity.class, "g");
            assertThat(stale.getModelingRevision()).isEqualTo(1);
            repo.requireModeling("g");
            stale.setMdlState("PUBLISHED");
            em.merge(stale);
            em.flush();
            repo.coverModelingRevision("g", 1);
            var pending = em.find(DatasetGroupEntity.class, "g");
            assertThat(pending.getModelingRevision()).isEqualTo(2);
            assertThat(pending.getPublishedModelingRevision()).isEqualTo(1);
            assertThat(pending.isModelingPending()).isTrue();
            repo.coverModelingRevision("g", 2);
            repo.coverModelingRevision("g", 1);
            var ready = em.find(DatasetGroupEntity.class, "g");
            assertThat(ready.isModelingPending()).isFalse();
            assertThat(ready.getPublishedModelingRevision()).isEqualTo(2);
            em.getTransaction().commit();
        }
    }

    @Test
    void publicationGateIsRecheckedAndDoesNotExpandAuthorization() {
        var groups = mock(DatasetGroupService.class);
        var access = new WrenQueryAccess(groups, new ConversationScopeRegistry());
        var ctx =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("s")
                        .put(DatasetScope.class, new DatasetScope("alice", List.of("g")))
                        .build();
        var group = new DatasetGroupEntity("g", "alice", "example", "");
        group.setMdlVersion(1);
        when(groups.queryGroups("alice", List.of("g"))).thenReturn(List.of(group));
        assertThat(access.resolve(ctx, "g")).isSameAs(group);
        org.springframework.test.util.ReflectionTestUtils.setField(group, "modelingRevision", 1L);
        assertThatThrownBy(() -> access.resolve(ctx, "g"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("发布");
        assertThatThrownBy(() -> access.resolve(ctx, "foreign"))
                .isInstanceOf(DatasetException.class);
        when(groups.queryGroups("alice", List.of("g"))).thenReturn(List.of());
        assertThatThrownBy(() -> access.resolve(ctx, "g")).isInstanceOf(DatasetException.class);
    }
}
