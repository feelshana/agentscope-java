package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.web.session.DashboardGenerationService;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Resumable product status around the existing authorized native chat endpoint. */
@RestController
@RequestMapping("/api/dashboards/{id}/generation")
public class DashboardGenerationController {
    private final DashboardGenerationService generations;
    private final ChatController chat;
    private final AgentAccessGuard guard;

    public DashboardGenerationController(
            DashboardGenerationService generations, ChatController chat, AgentAccessGuard guard) {
        this.generations = generations;
        this.chat = chat;
        this.guard = guard;
    }

    public record Stop(String requestId) {}

    private <T> Mono<T> authorized(Authentication auth, Callable<T> action) {
        return Mono.fromCallable(
                        () -> {
                            guard.require(
                                    (String) auth.getPrincipal(),
                                    DashboardService.AGENT_ID,
                                    Tier.RUN);
                            return action.call();
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.valueOf(e.status()),
                                        e.getMessage(),
                                        e));
    }

    @GetMapping
    public Mono<DashboardGenerationService.Snapshot> current(
            Authentication auth, @PathVariable String id) {
        return authorized(auth, () -> generations.snapshot((String) auth.getPrincipal(), id));
    }

    @PostMapping
    public Mono<DashboardGenerationService.View> start(
            Authentication auth,
            @PathVariable String id,
            @RequestBody DashboardGenerationService.Request body) {
        return authorized(
                auth,
                () ->
                        generations.start(
                                (String) auth.getPrincipal(),
                                id,
                                body,
                                (group, message) ->
                                        chat.stream(
                                                        DashboardService.AGENT_ID,
                                                        new ChatController.ChatRequest(
                                                                message,
                                                                id,
                                                                List.of(group),
                                                                false,
                                                                null,
                                                                null,
                                                                null,
                                                                false),
                                                        auth)
                                                .mapNotNull(
                                                        org.springframework.http.codec
                                                                        .ServerSentEvent
                                                                ::data)));
    }

    @PostMapping("/stop")
    public Mono<DashboardGenerationService.View> stop(
            Authentication auth, @PathVariable String id, @RequestBody Stop body) {
        return authorized(
                auth, () -> generations.stop((String) auth.getPrincipal(), id, body.requestId()));
    }
}
