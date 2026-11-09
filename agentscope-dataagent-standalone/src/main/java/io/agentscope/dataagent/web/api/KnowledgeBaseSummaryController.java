package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.KnowledgeBaseBrowseService;
import io.agentscope.dataagent.dataset.ModelingHubService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** spec106: published model counts for the semantic modeling list. */
@RestController
@RequestMapping("/api/knowledge-bases/summaries")
public class KnowledgeBaseSummaryController {
    private final KnowledgeBaseBrowseService browse;
    private final ModelingHubService modeling;

    public KnowledgeBaseSummaryController(
            KnowledgeBaseBrowseService browse, ModelingHubService modeling) {
        this.browse = browse;
        this.modeling = modeling;
    }

    @GetMapping
    public Mono<List<KnowledgeBaseBrowseService.Summary>> list(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> browse.summaries(userId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/modeling")
    public Mono<List<ModelingHubService.Summary>> modeling(
            Authentication auth, @RequestParam List<String> ids) {
        String actor = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> modeling.summaries(actor, ids))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        org.springframework.http.HttpStatusCode.valueOf(e.status()),
                                        e.getMessage(),
                                        e));
    }
}
