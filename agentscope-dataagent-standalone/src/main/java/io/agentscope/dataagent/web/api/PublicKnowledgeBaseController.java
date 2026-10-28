package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.PublicKnowledgeBaseService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/admin/public-knowledge-bases")
public class PublicKnowledgeBaseController {
    private final PublicKnowledgeBaseService service;

    public PublicKnowledgeBaseController(PublicKnowledgeBaseService service) {
        this.service = service;
    }

    public record Update(boolean publicQuery) {}

    @GetMapping
    public Mono<PublicKnowledgeBaseService.Page> list(
            Authentication auth,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page) {
        return call(() -> service.list((String) auth.getPrincipal(), search, page));
    }

    @PutMapping("/{id}")
    public Mono<Void> set(
            Authentication auth, @PathVariable String id, @RequestBody Update request) {
        return call(() -> {
                    service.set((String) auth.getPrincipal(), id, request.publicQuery());
                    return true;
                })
                .then();
    }

    private static <T> Mono<T> call(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        t ->
                                t instanceof DatasetException e
                                        ? new org.springframework.web.server
                                                .ResponseStatusException(
                                                org.springframework.http.HttpStatus.valueOf(
                                                        e.status()),
                                                e.getMessage(),
                                                e)
                                        : t);
    }
}
