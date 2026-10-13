package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class WrenQueryAccessTest {
    @Test
    void selectionCannotExposeAnotherOwnerOrUnselectedGroup() {
        var groups = mock(DatasetGroupService.class);
        var a = new DatasetGroupEntity("a", "alice", "销售", "");
        a.setMdlVersion(1);
        var b = new DatasetGroupEntity("b", "alice", "其他", "");
        b.setMdlVersion(1);
        var foreign = new DatasetGroupEntity("c", "bob", "销售", "");
        foreign.setMdlVersion(1);
        when(groups.listGroups("alice")).thenReturn(List.of(a, b, foreign));
        var ctx = mock(RuntimeContext.class);
        when(ctx.getUserId()).thenReturn("alice");
        when(ctx.get(DatasetScope.class)).thenReturn(new DatasetScope("alice", List.of("a")));
        var access = new WrenQueryAccess(groups, new ConversationScopeRegistry());
        assertThat(access.visible(ctx)).containsExactly(a);
        assertThat(access.resolve(ctx, null)).isSameAs(a);
        assertThatThrownBy(() -> access.resolve(ctx, "b")).isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> access.resolve(ctx, "c")).isInstanceOf(DatasetException.class);
        when(ctx.get(DatasetScope.class)).thenReturn(new DatasetScope("bob", List.of("a")));
        assertThatThrownBy(() -> access.visible(ctx)).isInstanceOf(DatasetException.class);
    }

    @Test
    void unpublishedModelCannotBeQueried() {
        var groups = mock(DatasetGroupService.class);
        when(groups.listGroups("alice"))
                .thenReturn(List.of(new DatasetGroupEntity("a", "alice", "销售", "")));
        var ctx = mock(RuntimeContext.class);
        when(ctx.getUserId()).thenReturn("alice");
        var access = new WrenQueryAccess(groups, new ConversationScopeRegistry());
        assertThatThrownBy(() -> access.resolve(ctx, "a"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("尚未发布");
    }
}
