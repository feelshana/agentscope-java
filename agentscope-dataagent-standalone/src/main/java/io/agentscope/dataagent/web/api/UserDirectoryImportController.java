package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.UserDirectoryImportService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/admin/user-directory/import")
public class UserDirectoryImportController {
    private final UserDirectoryImportService service;

    public UserDirectoryImportController(UserDirectoryImportService service) {
        this.service = service;
    }

    public record Request(List<UserDirectoryImportService.Row> users, Boolean dryRun) {}

    @PostMapping
    public Mono<UserDirectoryImportService.Result> apply(
            Authentication auth, @RequestBody Request request) {
        return Mono.fromCallable(
                        () ->
                                service.apply(
                                        (String) auth.getPrincipal(),
                                        request.users(),
                                        !Boolean.FALSE.equals(request.dryRun())))
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
