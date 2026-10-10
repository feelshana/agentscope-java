package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.AnswerQueryMemory;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.web.session.SessionHistoryService;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Feedback is authorized by both agent ACL and persistent session ownership. */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{session}/answers/{answerId}/feedback")
public class AnswerFeedbackController {
    private final AgentAccessGuard guard;
    private final SessionController sessions;
    private final SessionHistoryService history;
    private final AnswerQueryMemory memory;

    public AnswerFeedbackController(
            AgentAccessGuard guard,
            SessionController sessions,
            SessionHistoryService history,
            AnswerQueryMemory memory) {
        this.guard = guard;
        this.sessions = sessions;
        this.history = history;
        this.memory = memory;
    }

    public record Request(String vote) {}

    @PostMapping
    public Mono<AnswerQueryMemory.Result> feedback(
            @PathVariable String agentId,
            @PathVariable String session,
            @PathVariable String answerId,
            @RequestBody Request request,
            Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            require(owner, agentId);
                            var entry = sessions.requireOwnedSession(agentId, session, owner);
                            history.refresh(entry.sessionKey());
                            return memory.feedback(
                                    owner,
                                    entry.sessionKey(),
                                    answerId,
                                    request.vote(),
                                    history.all(entry.sessionKey()));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<AnswerQueryMemory.Result> status(
            @PathVariable String agentId,
            @PathVariable String session,
            @PathVariable String answerId,
            Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            require(owner, agentId);
                            var entry = sessions.requireOwnedSession(agentId, session, owner);
                            return memory.status(owner, entry.sessionKey(), answerId);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void require(String owner, String agentId) {
        guard.require(owner, agentId, Tier.RUN);
        if (!"data-agent".equals(agentId)) throw new DatasetException("仅问数助手支持答案反馈", 400);
    }
}
