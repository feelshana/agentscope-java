package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.FactTableWindowService;
import io.agentscope.dataagent.dataset.KnowledgeBaseAccessService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Table-level time-window configuration for external fact tables. */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/fact-query-windows")
public class FactQueryWindowController {
    private final KnowledgeBaseAccessService access;
    private final FactTableWindowService service;

    public FactQueryWindowController(
            KnowledgeBaseAccessService access, FactTableWindowService service) {
        this.access = access;
        this.service = service;
    }

    @GetMapping
    public Mono<List<FactTableWindowService.Candidate>> candidates(
            @PathVariable String groupId, Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            access.requireManage((String) auth.getPrincipal(), groupId);
                            return service.candidates(groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }

    @PutMapping
    public Mono<FactTableWindowService.Candidate> save(
            @PathVariable String groupId, Authentication auth, @RequestBody WindowRequest request) {
        String actor = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            access.requireManage(actor, groupId);
                            return service.save(
                                    actor,
                                    groupId,
                                    request.datasetId(),
                                    request.mode(),
                                    request.days(),
                                    request.timeColumn());
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }

    @PutMapping("/table-role")
    public Mono<Void> confirmRole(
            @PathVariable String groupId, Authentication auth, @RequestBody RoleRequest request) {
        String actor = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                        () -> {
                            access.requireManage(actor, groupId);
                            service.confirmRole(
                                    actor, groupId, request.datasetId(), request.role());
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then()
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }

    public record RoleRequest(String datasetId, String role) {}

    public record WindowRequest(String datasetId, String mode, Integer days, String timeColumn) {}
}
