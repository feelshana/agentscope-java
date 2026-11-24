package io.agentscope.dataagent.web.chat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.ModelingTurnPurpose;
import io.agentscope.dataagent.runtime.session.ConversationScopeRegistry;
import io.agentscope.dataagent.tools.data.ModelingToolkitRegistrar;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.toolbus.ToolEventBus;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.List;

/** Prepares a turn and commits its scope only after the existing gateway admits it. */
public final class ChatTurnPreparation {
    private final ConversationScopeRegistry conversationScopes;
    private final DatasetGroupService datasetGroupService;
    private final AgentCatalogService catalogService;
    private io.agentscope.dataagent.dataset.DashboardService dashboards;

    public void setDashboards(io.agentscope.dataagent.dataset.DashboardService dashboards) {
        this.dashboards = dashboards;
    }

    public ChatTurnPreparation(
            ConversationScopeRegistry scopes,
            DatasetGroupService groups,
            AgentCatalogService catalog) {
        this.conversationScopes = scopes;
        this.datasetGroupService = groups;
        this.catalogService = catalog;
    }

    public InboundMessage buildInbound(
            String userId,
            String agentId,
            List<Msg> msgs,
            String conversationId,
            List<String> groupIds,
            boolean allGroups,
            String requestId,
            java.util.function.Consumer<
                            io.agentscope.dataagent.runtime.session.ConversationScopeRegistry
                                    .Selection>
                    onAccepted) {
        return buildInbound(
                userId,
                agentId,
                msgs,
                conversationId,
                groupIds,
                allGroups,
                requestId,
                onAccepted,
                null);
    }

    public InboundMessage buildInbound(
            String userId,
            String agentId,
            List<Msg> msgs,
            String conversationId,
            List<String> groupIds,
            boolean allGroups,
            String requestId,
            java.util.function.Consumer<ConversationScopeRegistry.Selection> onAccepted,
            ModelingTurnPurpose newModelingTurn) {
        List<String> authorized =
                resolveGroupScope(userId, agentId, conversationId, groupIds, allGroups);
        var groupNames = new java.util.LinkedHashMap<String, String>();
        for (var group : datasetGroupService.queryGroups(userId, authorized))
            groupNames.put(group.getId(), group.getName());
        var selection =
                new io.agentscope.dataagent.runtime.session.ConversationScopeRegistry.Selection(
                        authorized, allGroups, groupNames);
        RuntimeContext.Builder runtimeContextBuilder =
                RuntimeContext.builder()
                        .userId(userId)
                        .put(DatasetScope.class, new DatasetScope(userId, authorized))
                        .put(
                                io.agentscope.dataagent.dataset.ChatTurnScope.class,
                                new io.agentscope.dataagent.dataset.ChatTurnScope(
                                        () -> {
                                            // Runs inside the gateway gate: queued turns cannot
                                            // overwrite active tool scope.
                                            resolveGroupScope(
                                                    userId,
                                                    agentId,
                                                    conversationId,
                                                    authorized,
                                                    false);
                                            conversationScopes.putSelection(
                                                    userId, conversationId, selection);
                                            if (newModelingTurn != null)
                                                conversationScopes.putModelingTurn(
                                                        userId, conversationId, newModelingTurn);
                                            for (Msg msg : msgs)
                                                if (msg.getRole() == MsgRole.USER)
                                                    conversationScopes.putSelection(
                                                            userId,
                                                            "turn:" + msg.getId(),
                                                            selection);
                                            onAccepted.accept(selection);
                                        }));
        if (requestId != null) {
            runtimeContextBuilder.put(ToolEventBus.REQUEST_ID_CONTEXT_KEY, requestId);
        }
        var purpose =
                newModelingTurn == null
                        ? conversationScopes.modelingTurn(userId, conversationId)
                        : newModelingTurn;
        if (purpose != null
                && purpose.value() != null
                && ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)) {
            runtimeContextBuilder.put(ModelingTurnPurpose.class, purpose);
        }
        var analysis = conversationScopes.analysis(userId, conversationId);
        if (analysis != null && !ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)) {
            runtimeContextBuilder.put(
                    io.agentscope.dataagent.tools.data.AnalysisTurn.class, analysis);
        }
        RuntimeContext runtimeContext = runtimeContextBuilder.build();
        if (io.agentscope.dataagent.dataset.DashboardService.AGENT_ID.equals(agentId))
            runtimeContext.put(
                    io.agentscope.dataagent.dataset.DashboardTurn.class,
                    new io.agentscope.dataagent.dataset.DashboardTurn(conversationId));
        if (agentId == null || agentId.isBlank()) {
            return InboundMessage.dm(ChatUiChannel.CHANNEL_ID, userId, List.copyOf(msgs))
                    .withRuntimeContext(runtimeContext);
        }
        String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
        return InboundMessage.builder(
                        ChatUiChannel.CHANNEL_ID, Peer.direct(userId), List.copyOf(msgs))
                .preferredAgentId(gatewayAgentId)
                .accountId(conversationId)
                .runtimeContext(runtimeContext)
                .build();
    }

    public List<String> resolveGroupScope(
            String userId,
            String agentId,
            String conversationId,
            List<String> groupIds,
            boolean allGroups) {
        boolean modeling = ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId);
        boolean dashboard =
                io.agentscope.dataagent.dataset.DashboardService.AGENT_ID.equals(agentId);
        if (dashboard) {
            if (dashboards == null) throw new DatasetException("仪表盘服务未就绪", 503);
            var asset = dashboards.requireDraft(userId, conversationId);
            if (allGroups || (groupIds != null && !List.of(asset.groupId()).equals(groupIds)))
                throw new DatasetException("此看板已绑定知识库，请新建看板切换", 400);
            return List.of(asset.groupId());
        }
        if (allGroups || (groupIds != null && groupIds.stream().distinct().count() > 1))
            throw new DatasetException("本次只支持单知识库问数，请重新选择一个知识库", 400);
        if (allGroups && modeling) throw new DatasetException("建模会话必须选择一个知识库", 400);
        if (allGroups && groupIds != null && !groupIds.isEmpty())
            throw new DatasetException("全选不能同时指定知识库列表", 400);
        List<String> previous = conversationScopes.get(userId, conversationId);
        List<String> requested =
                allGroups
                        ? datasetGroupService.listGroups(userId).stream()
                                .map(
                                        io.agentscope.dataagent.web.persistence.jpa
                                                        .DatasetGroupEntity
                                                ::getId)
                                .toList()
                        : groupIds != null ? groupIds : previous == null ? List.of() : previous;
        if (requested.stream().anyMatch(id -> id == null || id.isBlank()))
            throw new DatasetException("知识库 ID 不能为空", 400);
        List<String> normalized = requested.stream().distinct().sorted().toList();
        if (normalized.size() > 1) throw new DatasetException("此会话原先选择了多个知识库，请重新选择一个知识库", 400);
        if (modeling
                && previous != null
                && !new java.util.HashSet<>(previous).equals(new java.util.HashSet<>(normalized)))
            throw new DatasetException("当前会话已绑定知识库范围，请新建对话切换", 400);
        if (modeling && normalized.size() != 1) throw new DatasetException("建模会话必须选择一个知识库", 400);
        List<String> authorized =
                datasetGroupService.queryGroups(userId, normalized).stream()
                        .map(io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity::getId)
                        .distinct()
                        .sorted()
                        .toList();
        if (modeling) {
            if (authorized.size() != 1) throw new DatasetException("建模会话必须选择一个知识库", 400);
            datasetGroupService.getGroup(userId, authorized.get(0));
        }
        return authorized;
    }
}
