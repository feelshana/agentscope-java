package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.KnowledgeBaseBrowseService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/knowledge-bases/{groupId}/browse")
public class KnowledgeBaseBrowseController {
    private final KnowledgeBaseBrowseService browse;
    private final DatasetService datasets;

    public KnowledgeBaseBrowseController(
            KnowledgeBaseBrowseService browse, DatasetService datasets) {
        this.browse = browse;
        this.datasets = datasets;
    }

    @GetMapping
    public Mono<KnowledgeBaseBrowseService.Browse> get(
            @PathVariable String groupId, Authentication auth) {
        return call(() -> browse.browse((String) auth.getPrincipal(), groupId));
    }

    @GetMapping("/{datasetId}/preview")
    public Mono<DatasetService.Preview> preview(
            @PathVariable String groupId, @PathVariable String datasetId, Authentication auth) {
        return call(
                () -> datasets.previewReadable((String) auth.getPrincipal(), groupId, datasetId));
    }

    private static <T> Mono<T> call(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        t ->
                                t instanceof DatasetException e
                                        ? new ResponseStatusException(
                                                org.springframework.http.HttpStatus.valueOf(
                                                        e.status()),
                                                e.getMessage(),
                                                e)
                                        : t);
    }
}
