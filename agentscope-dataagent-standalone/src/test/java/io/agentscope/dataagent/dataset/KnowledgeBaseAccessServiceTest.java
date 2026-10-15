package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeBaseAccessServiceTest {
    private DatasetGroupRepository groups;
    private KnowledgeBaseGrantRepository grants;
    private UserStore users;
    private KnowledgeBaseOperations operations;
    private KnowledgeBaseAccessService access;
    private DatasetGroupEntity group;

    @BeforeEach
    void setUp() {
        groups = mock(DatasetGroupRepository.class);
        grants = mock(KnowledgeBaseGrantRepository.class);
        users = mock(UserStore.class);
        operations = mock(KnowledgeBaseOperations.class);
        access = new KnowledgeBaseAccessService(groups, grants, users, operations);
        group = new DatasetGroupEntity("g1", "owner", "共享库", null);
        when(groups.findById("g1")).thenReturn(Optional.of(group));
        for (String id : List.of("owner", "reader", "manager", "admin", "other")) {
            when(users.findById(id))
                    .thenReturn(
                            Optional.of(
                                    new UserStore.UserRecord(
                                            id,
                                            id,
                                            "",
                                            List.of(id.equals("admin") ? "admin" : "user"))));
        }
    }

    private void grant(String user, String role) {
        var grant = new KnowledgeBaseGrantEntity("g1", user);
        grant.grant(role, "owner");
        when(grants.findByGroupIdAndUserId("g1", user)).thenReturn(Optional.of(grant));
    }

    @Test
    void creatorAndCurrentGlobalAdminManageWithoutExplicitGrant() {
        assertThat(access.requireManage("owner", "g1")).isSameAs(group);
        assertThat(access.requireManage("admin", "g1")).isSameAs(group);
        verifyNoInteractions(grants);
    }

    @Test
    void queryMemberCanQueryButCannotManage() {
        grant("reader", "QUERY");
        assertThat(access.requireQuery("reader", "g1")).isSameAs(group);
        assertThatThrownBy(() -> access.requireManage("reader", "g1"))
                .isInstanceOfSatisfying(
                        DatasetException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void managerCanQueryAndManageWithoutChangingActor() {
        grant("manager", "MANAGE");
        assertThat(access.requireManage("manager", "g1").getOwnerId()).isEqualTo("owner");
        assertThat(access.requireQuery("manager", "g1")).isSameAs(group);
        verify(grants, times(2)).findByGroupIdAndUserId("g1", "manager");
    }

    @Test
    void revokeAndAdminDemotionAreEffectiveWithExistingIdentity() {
        grant("reader", "QUERY");
        access.requireQuery("reader", "g1");
        when(grants.findByGroupIdAndUserId("g1", "reader")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.requireQuery("reader", "g1"))
                .isInstanceOfSatisfying(
                        DatasetException.class, e -> assertThat(e.status()).isEqualTo(404));
        access.requireManage("admin", "g1");
        when(users.findById("admin"))
                .thenReturn(
                        Optional.of(
                                new UserStore.UserRecord("admin", "admin", "", List.of("user"))));
        assertThatThrownBy(() -> access.requireManage("admin", "g1"))
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void removedUserCannotUseRetainedGrant() {
        grant("reader", "MANAGE");
        when(users.findById("reader")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.requireQuery("reader", "g1"))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> access.page("reader", 0, 20, "", "date", false))
                .isInstanceOfSatisfying(
                        DatasetException.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    @Test
    void unknownAndUnassignedGroupAreIndistinguishable() {
        for (String id : List.of("g1", "missing")) {
            assertThatThrownBy(() -> access.requireQuery("other", id))
                    .isInstanceOfSatisfying(
                            DatasetException.class, e -> assertThat(e.status()).isEqualTo(404))
                    .hasMessage("知识库不存在或未授权");
        }
    }

    @Test
    void pendingDeletionBlocksDataButAllowsAuthorizedRetry() {
        doThrow(new DatasetException("正在删除", 409)).when(operations).requireActive("g1");
        assertThatThrownBy(() -> access.requireManage("owner", "g1"))
                .isInstanceOf(DatasetException.class);
        assertThat(access.requireManageForDeletion("owner", "g1")).isSameAs(group);
        assertThatThrownBy(() -> access.requireManageForDeletion("other", "g1"))
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void emptySelectionCannotExpandToAllAndPaginationCarriesAcl() {
        assertThat(access.visible("reader", List.of())).isEmpty();
        access.page("reader", 1, 20, " SaleS ", "name", true);
        verify(groups)
                .findAccessible(
                        eq("reader"),
                        eq(false),
                        eq(true),
                        eq("sales"),
                        argThat(p -> p.getPageNumber() == 1 && p.getPageSize() == 20));
        assertThatThrownBy(() -> access.page("reader", 0, 101, "", "date", false))
                .isInstanceOf(DatasetException.class);
    }
}
