package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.KnowledgeBaseAccessService;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.tools.data.AnalysisSkills;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * spec097: which analysis methods a knowledge base supports, so the query page greys out the ones
 * that cannot run. Requires knowledge-base QUERY; reads only the published model.
 */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/analysis-capabilities")
public class AnalysisController {
    private final KnowledgeBaseAccessService access;
    private final MdlCatalog catalog;

    public AnalysisController(KnowledgeBaseAccessService access, MdlCatalog catalog) {
        this.access = access;
        this.catalog = catalog;
    }

    /** {@code reason} is shown on the greyed-out methods; {@code null} when all are usable. */
    public record Capabilities(boolean hasTimeField, List<String> methods, String reason) {}

    @GetMapping
    public Mono<Capabilities> get(@PathVariable String groupId, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            access.requireQuery(user, groupId);
                            boolean time = catalog.hasTimeColumn(groupId);
                            return new Capabilities(
                                    time,
                                    time ? AnalysisSkills.IDS : List.of(),
                                    time ? null : "该知识库没有时间字段，暂不支持此分析");
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }
}
