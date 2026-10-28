package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.KnowledgeBaseMembershipService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/dataset-groups/{groupId}/members")
public class KnowledgeBaseMembershipController {
    public record GrantRequest(String role) {}

    private final KnowledgeBaseMembershipService service;

    public KnowledgeBaseMembershipController(KnowledgeBaseMembershipService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<KnowledgeBaseMembershipService.Members> list(
            @PathVariable String groupId,
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return call(() -> service.members((String) auth.getPrincipal(), groupId, page, size));
    }

    @GetMapping("/users")
    public Mono<KnowledgeBaseMembershipService.Directory> users(
            @PathVariable String groupId,
            Authentication auth,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String company,
            @RequestParam(defaultValue = "") String department,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return call(
                () ->
                        service.filteredDirectory(
                                (String) auth.getPrincipal(),
                                groupId,
                                search,
                                company,
                                department,
                                page,
                                size));
    }

    public record BatchRequest(java.util.List<String> userIds, String role) {}

    @PutMapping("/batch")
    public Mono<Void> batch(
            @PathVariable String groupId, @RequestBody BatchRequest request, Authentication auth) {
        return call(() -> {
                    service.batchGrant(
                            (String) auth.getPrincipal(),
                            groupId,
                            request.userIds(),
                            request.role());
                    return true;
                })
                .then();
    }

    @GetMapping("/filters")
    public Mono<KnowledgeBaseMembershipService.Filters> filters(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "") String company,
            Authentication auth) {
        return call(() -> service.filters((String) auth.getPrincipal(), groupId, company));
    }

    @PutMapping("/{userId}")
    public Mono<Void> grant(
            @PathVariable String groupId,
            @PathVariable String userId,
            @RequestBody GrantRequest request,
            Authentication auth) {
        return call(() -> {
                    service.grant((String) auth.getPrincipal(), groupId, userId, request.role());
                    return true;
                })
                .then();
    }

    @DeleteMapping("/{userId}")
    public Mono<Void> revoke(
            @PathVariable String groupId, @PathVariable String userId, Authentication auth) {
        return call(() -> {
                    service.revoke((String) auth.getPrincipal(), groupId, userId);
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
                                        ? new ResponseStatusException(
                                                org.springframework.http.HttpStatus.valueOf(
                                                        e.status()),
                                                e.getMessage(),
                                                e)
                                        : t);
    }
}
