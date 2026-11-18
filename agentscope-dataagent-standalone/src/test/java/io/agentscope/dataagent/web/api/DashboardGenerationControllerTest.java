package io.agentscope.dataagent.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.web.session.DashboardGenerationService;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class DashboardGenerationControllerTest {
    @Test
    void emptyStatusHasAStableEnvelopeAndNativeExecutionUsesTheServerSelectedGroup() {
        var generations = mock(DashboardGenerationService.class);
        var chat = mock(ChatController.class);
        var guard = mock(AgentAccessGuard.class);
        var auth = new TestingAuthenticationToken("alice", "unused");
        var api = new DashboardGenerationController(generations, chat, guard);
        when(generations.snapshot("alice", "d"))
                .thenReturn(new DashboardGenerationService.Snapshot(null, false, false));
        var client =
                WebTestClient.bindToController(api)
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(
                                                exchange.mutate()
                                                        .principal(Mono.just(auth))
                                                        .build()))
                        .build();
        client.get()
                .uri("/api/dashboards/d/generation")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.task")
                .isEmpty();
        when(chat.stream(eq(DashboardService.AGENT_ID), any(), eq(auth)))
                .thenReturn(Flux.just(ServerSentEvent.builder("{\"type\":\"done\"}").build()));
        when(generations.start(eq("alice"), eq("d"), any(), any()))
                .thenAnswer(
                        call -> {
                            BiFunction<String, String, Flux<String>> engine = call.getArgument(3);
                            engine.apply("server-group", "原需求").collectList().block();
                            return null;
                        });
        client.post()
                .uri("/api/dashboards/d/generation")
                .bodyValue(
                        java.util.Map.of(
                                "requestId",
                                java.util.UUID.randomUUID().toString(),
                                "revision",
                                "r",
                                "message",
                                "原需求"))
                .exchange()
                .expectStatus()
                .isOk();
        verify(chat).stream(
                DashboardService.AGENT_ID,
                new ChatController.ChatRequest(
                        "原需求", "d", List.of("server-group"), false, null, null, null, false),
                auth);
        clearInvocations(generations, chat);
        doThrow(new DatasetException("未授权", 403))
                .when(guard)
                .require("alice", DashboardService.AGENT_ID, Tier.RUN);
        client.get().uri("/api/dashboards/d/generation").exchange().expectStatus().isForbidden();
        client.post()
                .uri("/api/dashboards/d/generation")
                .bodyValue(java.util.Map.of("message", "生成"))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri("/api/dashboards/d/generation/stop")
                .bodyValue(java.util.Map.of("requestId", "r"))
                .exchange()
                .expectStatus()
                .isForbidden();
        verifyNoInteractions(generations, chat);
    }
}
