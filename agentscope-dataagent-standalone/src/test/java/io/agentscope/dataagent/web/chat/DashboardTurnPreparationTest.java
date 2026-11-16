package io.agentscope.dataagent.web.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.dataagent.dataset.ChatTurnScope;
import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.dataset.DashboardTurn;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.runtime.session.ConversationScopeRegistry;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardTurnPreparationTest {
    @Test
    void workspaceIdentityIsServerBoundAndReauthorizedAtAdmission() {
        var groups = mock(DatasetGroupService.class);
        var catalog = mock(AgentCatalogService.class);
        var dashboards = mock(DashboardService.class);
        var scopes = new ConversationScopeRegistry();
        var preparation = new ChatTurnPreparation(scopes, groups, catalog);
        preparation.setDashboards(dashboards);
        var asset = new DashboardService.Asset("dashboard", "alice", "g", "看板", null, "now", 1);
        when(dashboards.requireDraft("alice", "dashboard")).thenReturn(asset);
        when(catalog.resolveGatewayAgentId("alice", DashboardService.AGENT_ID))
                .thenReturn(DashboardService.AGENT_ID);
        when(groups.queryGroups("alice", List.of("g")))
                .thenReturn(List.of(new DatasetGroupEntity("g", "alice", "数据", "")));
        assertThatThrownBy(
                        () ->
                                preparation.resolveGroupScope(
                                        "alice",
                                        DashboardService.AGENT_ID,
                                        "dashboard",
                                        List.of("other"),
                                        false))
                .hasMessageContaining("绑定知识库");
        var inbound =
                preparation.buildInbound(
                        "alice",
                        DashboardService.AGENT_ID,
                        List.of(Msg.builder().role(MsgRole.USER).textContent("看板").build()),
                        "dashboard",
                        List.of("g"),
                        false,
                        null,
                        selection -> {});
        assertThat(inbound.runtimeContext().get(DashboardTurn.class).dashboardId())
                .isEqualTo("dashboard");
        assertThat(scopes.get("alice", "dashboard")).isNull();
        when(dashboards.requireDraft("alice", "dashboard"))
                .thenThrow(new DatasetException("权限已撤销", 403));
        assertThatThrownBy(() -> inbound.runtimeContext().get(ChatTurnScope.class).accept())
                .hasMessageContaining("撤销");
        assertThat(scopes.get("alice", "dashboard")).isNull();
    }
}
