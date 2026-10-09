/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.gateway.HarnessGateway;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.tools.data.ModelingToolkitRegistrar;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.identity.IdentityLinkStore;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.toolbus.ToolEventBus;
import io.agentscope.dataagent.web.usage.UsageStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class ChatControllerHitlTest {

    private static final String MODELING_AGENT_ID = ModelingToolkitRegistrar.MODELING_AGENT_ID;

    private final Authentication authentication = mock(Authentication.class);
    private final AgentAccessGuard guard = mock(AgentAccessGuard.class);
    private final ObjectProvider<Model> modelProvider = mock(ObjectProvider.class);
    private final ConversationScopeRegistry conversationScopes =
            mock(ConversationScopeRegistry.class);
    private final DatasetGroupService groupService = mock(DatasetGroupService.class);
    private final ChatUiChannel channel = mock(ChatUiChannel.class);
    private final HarnessGateway gateway = mock(HarnessGateway.class);
    private final SessionAgentManager sessions = mock(SessionAgentManager.class);
    private final AgentCatalogService catalogService = mock(AgentCatalogService.class);
    private ChatController controller;

    @BeforeEach
    void setUp() {
        DataAgentBootstrap bootstrap = mock(DataAgentBootstrap.class);
        when(bootstrap.gateway()).thenReturn(gateway);
        when(gateway.sessionAgentManager()).thenReturn(sessions);
        when(authentication.getPrincipal()).thenReturn("alice");
        when(modelProvider.getIfAvailable()).thenReturn(mock(Model.class));
        when(groupService.getGroup(anyString(), anyString()))
                .thenReturn(new DatasetGroupEntity("g1", "alice", "KB", null));

        controller =
                new ChatController(
                        channel,
                        bootstrap,
                        catalogService,
                        mock(IdentityLinkStore.class),
                        mock(UsageStore.class),
                        mock(ToolEventBus.class),
                        guard,
                        mock(AgentActivityStore.class),
                        modelProvider,
                        conversationScopes,
                        groupService);
    }

    @Test
    void confirmationEventIsExposedAsHitlRequestFrame() throws Exception {
        ToolUseBlock tool =
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("decide_relation")
                        .input(Map.of("relation_id", "r1", "action", "CONFIRM"))
                        .state(ToolCallState.ASKING)
                        .build();

        ServerSentEvent<String> frame =
                controller
                        .toAgentFrame(new RequireUserConfirmEvent("reply-1", List.of(tool)))
                        .block();
        JsonNode payload = new ObjectMapper().readTree(frame.data());

        assertThat(frame.event()).isEqualTo("hitl_request");
        assertThat(payload.path("type").asText()).isEqualTo("hitl_request");
        assertThat(payload.path("replyId").asText()).isEqualTo("reply-1");
        assertThat(payload.path("toolCalls").get(0).path("id").asText()).isEqualTo("call-1");
        assertThat(payload.path("toolCalls").get(0).path("name").asText())
                .isEqualTo("decide_relation");
        assertThat(payload.path("toolCalls").get(0).path("input").path("relation_id").asText())
                .isEqualTo("r1");
    }

    @Test
    void currentSessionRestoresPersistedAskingTool() {
        RouteResult route = mock(RouteResult.class);
        MsgContext context = mock(MsgContext.class);
        when(channel.previewRoute(any())).thenReturn(route);
        when(route.context()).thenReturn(context);
        when(context.canonicalKey()).thenReturn("gate-1");
        when(sessions.allSessions())
                .thenReturn(
                        List.of(
                                new SessionEntry(
                                        "storage-key",
                                        "modeling-gateway",
                                        "runtime-session",
                                        null,
                                        SessionKind.MAIN,
                                        null,
                                        0,
                                        1L,
                                        1L,
                                        null,
                                        null,
                                        "gate-1",
                                        "alice")));
        when(sessions.getSession("storage-key"))
                .thenReturn(
                        java.util.Optional.of(
                                new SessionEntry(
                                        "storage-key",
                                        "modeling-gateway",
                                        "runtime-session",
                                        null,
                                        SessionKind.MAIN,
                                        null,
                                        0,
                                        1L,
                                        1L,
                                        null,
                                        null,
                                        "gate-1",
                                        "alice")));
        when(catalogService.resolveGatewayAgentId("alice", MODELING_AGENT_ID))
                .thenReturn("modeling-gateway");

        ToolUseBlock tool =
                ToolUseBlock.builder()
                        .id("call-pending")
                        .name("decide_relation")
                        .input(Map.of("relation_id", "r1", "action", "CONFIRM"))
                        .state(ToolCallState.ASKING)
                        .build();
        AgentState state =
                AgentState.builder()
                        .sessionId("runtime-session")
                        .replyId("reply-pending")
                        .addMessage(Msg.builder().role(MsgRole.ASSISTANT).content(tool).build())
                        .build();
        HarnessAgent agent = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        when(gateway.findAgent("modeling-gateway")).thenReturn(agent);
        when(agent.getDelegate()).thenReturn(delegate);
        when(delegate.getAgentState("alice", "runtime-session")).thenReturn(state);

        ChatController.CurrentSessionResponse response =
                controller.currentSession(MODELING_AGENT_ID, "modeling-g1", authentication).block();

        assertThat(response).isNotNull();
        assertThat(response.sessionKey()).isEqualTo("modeling-g1");
        assertThat(response.exists()).isTrue();
        assertThat(response.pendingReplyId()).isEqualTo("reply-pending");
        assertThat(response.pendingToolCalls())
                .singleElement()
                .satisfies(
                        call -> {
                            assertThat(call.get("id")).isEqualTo("call-pending");
                            assertThat(call.get("name")).isEqualTo("decide_relation");
                            assertThat(call.get("input")).isEqualTo(tool.getInput());
                        });
        state.contextMutable()
                .add(
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        io.agentscope.core.message.ToolResultBlock.text(
                                                        "Permission denied by user")
                                                .withIdAndName(tool.getId(), tool.getName())
                                                .withState(
                                                        io.agentscope.core.message.ToolResultState
                                                                .DENIED))
                                .build());
        ChatController.CurrentSessionResponse resolved =
                controller.currentSession(MODELING_AGENT_ID, "modeling-g1", authentication).block();
        assertThat(resolved.pendingReplyId()).isNull();
        assertThat(resolved.pendingToolCalls()).isEmpty();
    }

    @Test
    void confirmationRejectsNullGroupIdAsBadRequest() {
        ChatController.ModelingConfirmRequest request =
                new ChatController.ModelingConfirmRequest(
                        "modeling-g1",
                        Arrays.asList("g1", null),
                        "reply-1",
                        "call-1",
                        "decide_relation",
                        true,
                        null);

        assertThatThrownBy(
                        () ->
                                controller.confirmModeling(
                                        MODELING_AGENT_ID, request, authentication))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void confirmationRejectsOversizedFeedbackAndFeedbackOnApproval() {
        for (ChatController.ModelingConfirmRequest request :
                List.of(
                        new ChatController.ModelingConfirmRequest(
                                "modeling-g1",
                                List.of("g1"),
                                "reply-1",
                                "call-1",
                                "write_file",
                                false,
                                null,
                                "x".repeat(4001)),
                        new ChatController.ModelingConfirmRequest(
                                "modeling-g1",
                                List.of("g1"),
                                "reply-1",
                                "call-1",
                                "write_file",
                                true,
                                null,
                                "请修正"))) {
            assertThatThrownBy(
                            () ->
                                    controller.confirmModeling(
                                            MODELING_AGENT_ID, request, authentication))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void confirmationRejectsChangedKnowledgeBaseScope() {
        when(conversationScopes.get("modeling-g1")).thenReturn(List.of("g1"));
        ChatController.ModelingConfirmRequest request =
                new ChatController.ModelingConfirmRequest(
                        "modeling-g1",
                        List.of("g2"),
                        "reply-1",
                        "call-1",
                        "decide_relation",
                        true,
                        null);

        assertThatThrownBy(
                        () ->
                                controller.confirmModeling(
                                        MODELING_AGENT_ID, request, authentication))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
