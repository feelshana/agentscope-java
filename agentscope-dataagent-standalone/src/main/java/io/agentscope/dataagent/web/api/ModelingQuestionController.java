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
    private final io.agentscope.dataagent.dataset.RecommendedQuestionService recommended;

    public ModelingQuestionController(MdlQuestionService service, AgentAccessGuard guard) {
        this(service, guard, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ModelingQuestionController(
            MdlQuestionService service,
            AgentAccessGuard guard,
            io.agentscope.dataagent.dataset.RecommendedQuestionService recommended) {
        this.service = service;
        this.guard = guard;
        this.recommended = recommended;
    }

    /** {@code validationId} is accepted for old clients and ignored (spec091). */
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

    /** spec103: the draft's recommended question for the trial panel; {@code question} may be null. */
    @GetMapping("/recommended")
    public Mono<io.agentscope.dataagent.dataset.RecommendedQuestionService.Recommendation>
            recommended(@PathVariable String groupId, Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    guard.require(owner, "modeling-agent", Tier.RUN);
                    return service.recommended(
                            new DatasetScope(owner, List.of(groupId)), groupId, recommended);
                });
    }

    public record RecommendedEdit(String question) {}

    /** spec103: the modeler's own recommended question; blank text returns it to generation. */
    @PostMapping("/recommended")
    public Mono<io.agentscope.dataagent.dataset.RecommendedQuestionService.Recommendation>
            editRecommended(
                    @PathVariable String groupId,
                    @RequestBody RecommendedEdit body,
                    Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    guard.require(owner, "modeling-agent", Tier.RUN);
                    return service.changeRecommended(
                            new DatasetScope(owner, List.of(groupId)),
                            groupId,
                            recommended,
                            body == null ? null : body.question(),
                            false);
                });
    }

    @PostMapping("/recommended/regenerate")
    public Mono<io.agentscope.dataagent.dataset.RecommendedQuestionService.Recommendation>
            regenerateRecommended(@PathVariable String groupId, Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    guard.require(owner, "modeling-agent", Tier.RUN);
                    return service.changeRecommended(
                            new DatasetScope(owner, List.of(groupId)),
                            groupId,
                            recommended,
                            null,
                            true);
                });
    }

    @GetMapping("/suggestions")
    public Mono<java.util.Map<String, List<String>>> suggestions(
            @PathVariable String groupId, Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    guard.require(owner, "modeling-agent", Tier.RUN);
                    return java.util.Map.of(
                            "questions",
                            service.suggestions(
                                    new DatasetScope(owner, List.of(groupId)), groupId));
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
                    if (decision.accepted() == null) {
                        throw new DatasetException("请提供确认/退回决定", 400);
                    }
                    return service.decide(
                            new DatasetScope(owner, List.of(groupId)),
                            groupId,
                            questionId,
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
