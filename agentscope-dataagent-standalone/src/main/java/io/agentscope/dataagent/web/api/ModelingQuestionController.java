package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlQuestionService;
import io.agentscope.dataagent.dataset.MdlQuestionStore;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Human review API; confirmation is deliberately not an agent tool. */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/modeling/questions")
public class ModelingQuestionController {
    private final MdlQuestionService service;
    private final AgentAccessGuard guard;

    public ModelingQuestionController(MdlQuestionService service, AgentAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    public record Decision(String validationId, Boolean accepted) {}

    public record Correction(String revision, String sql) {}

    public record Archive(String revision) {}

    @GetMapping("/{questionId}/revision")
    public Mono<java.util.Map<String, String>> revision(
            @PathVariable String groupId, @PathVariable String questionId, Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    guard.require(owner, "modeling-agent", Tier.RUN);
                    return java.util.Map.of(
                            "revision",
                            service.revision(
                                    new DatasetScope(owner, List.of(groupId)),
                                    groupId,
                                    questionId));
                });
    }

    @PostMapping("/{questionId}/sql")
    public Mono<MdlQuestionStore.Review> correctSql(
            @PathVariable String groupId,
            @PathVariable String questionId,
            @RequestBody Correction correction,
            Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    guard.require(owner, "modeling-agent", Tier.RUN);
                    return service.correctSql(
                            new DatasetScope(owner, List.of(groupId)),
                            groupId,
                            questionId,
                            correction.revision(),
                            correction.sql());
                });
    }

    @PostMapping("/{questionId}/archive")
    public Mono<java.util.Map<String, Boolean>> archive(
            @PathVariable String groupId,
            @PathVariable String questionId,
            @RequestBody Archive archive,
            Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    guard.require(owner, "modeling-agent", Tier.RUN);
                    service.archive(
                            new DatasetScope(owner, List.of(groupId)),
                            groupId,
                            questionId,
                            archive.revision());
                    return java.util.Map.of("archived", true);
                });
    }

    @GetMapping
    public Mono<List<MdlQuestionStore.Review>> list(
            @PathVariable String groupId, Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    guard.require(owner, "modeling-agent", Tier.RUN);
                    return service.list(new DatasetScope(owner, List.of(groupId)), groupId);
                });
    }

    @PostMapping("/{questionId}/validate")
    public Mono<MdlQuestionStore.Review> validate(
            @PathVariable String groupId, @PathVariable String questionId, Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    guard.require(owner, "modeling-agent", Tier.RUN);
                    return service.validate(
                            new DatasetScope(owner, List.of(groupId)), groupId, questionId);
                });
    }

    @PostMapping("/{questionId}/decision")
    public Mono<MdlQuestionStore.Review> decide(
            @PathVariable String groupId,
            @PathVariable String questionId,
            @RequestBody Decision decision,
            Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    guard.require(owner, "modeling-agent", Tier.RUN);
                    if (decision.validationId() == null || decision.accepted() == null) {
                        throw new DatasetException("请提供验证 ID 和确认/退回决定", 400);
                    }
                    return service.decide(
                            new DatasetScope(owner, List.of(groupId)),
                            groupId,
                            questionId,
                            decision.validationId(),
                            decision.accepted());
                });
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }
}
