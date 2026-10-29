package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.KnowledgeBaseAccessService;
import io.agentscope.dataagent.dataset.RecommendedQuestionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** spec098: the question the query page prefills for a knowledge base; requires QUERY. */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/recommended-question")
public class RecommendedQuestionController {
    private final KnowledgeBaseAccessService access;
    private final RecommendedQuestionService questions;

    public RecommendedQuestionController(
            KnowledgeBaseAccessService access, RecommendedQuestionService questions) {
        this.access = access;
        this.questions = questions;
    }

    /** {@code question} is null until one has been generated. */
    public record Recommended(String question) {}

    @GetMapping
    public Mono<Recommended> get(@PathVariable String groupId, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            access.requireQuery(user, groupId);
                            return new Recommended(questions.get(groupId).orElse(null));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }
}
