package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.KnowledgeBaseBrowseService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** spec106: published model counts for the semantic modeling list. */
@RestController
@RequestMapping("/api/knowledge-bases/summaries")
public class KnowledgeBaseSummaryController {
    private final KnowledgeBaseBrowseService browse;

    public KnowledgeBaseSummaryController(KnowledgeBaseBrowseService browse) {
        this.browse = browse;
    }

    @GetMapping
    public Mono<List<KnowledgeBaseBrowseService.Summary>> list(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> browse.summaries(userId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
