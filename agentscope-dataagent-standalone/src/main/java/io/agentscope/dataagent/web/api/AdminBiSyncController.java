package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.BiSyncStatusService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Global-admin observation only. No task execution, file access or shell entry point. */
@RestController
@RequestMapping("/api/admin/bi-sync")
public class AdminBiSyncController {
    private final BiSyncStatusService service;

    public AdminBiSyncController(BiSyncStatusService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<ResponseEntity<BiSyncStatusService.View>> status(Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(service::view)
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        value ->
                                ResponseEntity.ok()
                                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                                        .body(value));
    }

    @GetMapping("/extra")
    public Mono<ResponseEntity<io.agentscope.dataagent.dataset.BiExtraSyncStatus.View>> extra(
            Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(service::extraView)
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        value ->
                                ResponseEntity.ok()
                                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                                        .body(value));
    }

    private void requireAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅全局管理员可查看BI同步状态。");
    }
}
