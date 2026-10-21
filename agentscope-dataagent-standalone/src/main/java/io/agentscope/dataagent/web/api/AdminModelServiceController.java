package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.runtime.model.ModelServiceRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Global administrators alone may manage or probe model services. */
@RestController
@RequestMapping("/api/admin/model-services")
public class AdminModelServiceController {
    private final ModelServiceRegistry registry;

    public record RevisionRequest(long revision) {}

    public AdminModelServiceController(ModelServiceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Mono<ResponseEntity<ModelServiceRegistry.View>> list(Authentication auth) {
        requireAdmin(auth);
        return response(Mono.fromCallable(registry::view));
    }

    @PostMapping
    public Mono<ResponseEntity<ModelServiceRegistry.View>> create(
            Authentication auth, @RequestBody ModelServiceRegistry.Request request) {
        requireAdmin(auth);
        return response(Mono.fromCallable(() -> registry.save(null, request)));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<ModelServiceRegistry.View>> update(
            Authentication auth,
            @PathVariable String id,
            @RequestBody ModelServiceRegistry.Request request) {
        requireAdmin(auth);
        return response(Mono.fromCallable(() -> registry.save(id, request)));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<ModelServiceRegistry.View>> delete(
            Authentication auth, @PathVariable String id, @RequestParam long revision) {
        requireAdmin(auth);
        return response(Mono.fromCallable(() -> registry.delete(id, revision)));
    }

    @PostMapping("/{id}/test")
    public Mono<ResponseEntity<ModelServiceRegistry.TestResult>> test(
            Authentication auth, @PathVariable String id, @RequestBody RevisionRequest request) {
        requireAdmin(auth);
        return response(registry.test(id, request.revision()));
    }

    @PostMapping("/{id}/activate")
    public Mono<ResponseEntity<ModelServiceRegistry.View>> activate(
            Authentication auth, @PathVariable String id, @RequestBody RevisionRequest request) {
        requireAdmin(auth);
        return response(
                Mono.fromCallable(
                        () ->
                                registry.activate(
                                        id, request.revision(), (String) auth.getPrincipal())));
    }

    private static <T> Mono<ResponseEntity<T>> response(Mono<T> value) {
        return value.subscribeOn(Schedulers.boundedElastic())
                .map(
                        body ->
                                ResponseEntity.ok()
                                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                                        .body(body));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<java.util.Map<String, String>> rejected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(
                        java.util.Map.of(
                                "message",
                                error.getReason() == null ? "请求被拒绝。" : error.getReason()));
    }

    private static void requireAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (auth.getAuthorities().stream()
                .noneMatch(role -> "ROLE_ADMIN".equals(role.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅全局管理员可管理大模型服务。");
    }
}
