package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DataSourceIntrospector;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DemoDataService;
import io.agentscope.dataagent.dataset.ExternalDataSourceAccess;
import io.agentscope.dataagent.dataset.ExternalDataSourceService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** HTTP binding only; source and knowledge-base permissions remain separate service contracts. */
@RestController
@RequestMapping("/api/datasources")
public class ExternalDataSourceController {
    public record DataSourceRequest(
            String name,
            String kind,
            String jdbcUrl,
            String username,
            String password,
            Boolean sampling,
            String host,
            Integer port,
            String database,
            String sslMode) {
        ExternalDataSourceService.ConnectionRequest command() {
            return new ExternalDataSourceService.ConnectionRequest(
                    name, kind, jdbcUrl, username, password, sampling, host, port, database,
                    sslMode);
        }
    }

    public record GrantRequest(String role) {}

    public record EnabledRequest(Boolean enabled) {}

    public record ColumnVO(String name, String type, String description) {}

    public record TableVO(String name, String type) {}

    private final ExternalDataSourceService service;
    private final ExternalDataSourceAccess access;
    private final DataSourceIntrospector introspector;

    public ExternalDataSourceController(
            ExternalDataSourceService service,
            ExternalDataSourceAccess access,
            DataSourceIntrospector introspector) {
        this.service = service;
        this.access = access;
        this.introspector = introspector;
    }

    private static String actor(Authentication auth) {
        return (String) auth.getPrincipal();
    }

    private static <T> Mono<T> read(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(ExternalDataSourceController::status);
    }

    @GetMapping
    public Mono<List<ExternalDataSourceService.View>> list(Authentication auth) {
        return read(() -> service.list(actor(auth)));
    }

    @GetMapping("/{id}")
    public Mono<ExternalDataSourceService.View> get(@PathVariable String id, Authentication auth) {
        return read(() -> service.get(actor(auth), id));
    }

    @PostMapping
    public Mono<ExternalDataSourceService.View> create(
            @RequestBody DataSourceRequest req, Authentication auth) {
        return read(() -> service.create(actor(auth), req.command()));
    }

    @PutMapping("/{id}")
    public Mono<ExternalDataSourceService.View> update(
            @PathVariable String id, @RequestBody DataSourceRequest req, Authentication auth) {
        return read(() -> service.update(actor(auth), id, req.command()));
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable String id, Authentication auth) {
        return read(() -> {
                    service.delete(actor(auth), id);
                    return true;
                })
                .then();
    }

    @PostMapping("/test-connection")
    public Mono<ExternalDataSourceService.Status> testConnection(
            @RequestBody DataSourceRequest req,
            @RequestParam(required = false) String sourceId,
            Authentication auth) {
        return read(() -> service.test(actor(auth), req.command(), sourceId));
    }

    @GetMapping("/{id}/status")
    public Mono<ExternalDataSourceService.Status> status(
            @PathVariable String id, Authentication auth) {
        return read(() -> service.status(actor(auth), id));
    }

    @PutMapping("/{id}/enabled")
    public Mono<ExternalDataSourceService.View> enabled(
            @PathVariable String id, @RequestBody EnabledRequest req, Authentication auth) {
        return read(
                () -> {
                    if (req.enabled() == null) throw new DatasetException("请选择启用或停用", 400);
                    return service.setEnabled(actor(auth), id, req.enabled());
                });
    }

    @GetMapping("/{id}/members")
    public Mono<ExternalDataSourceService.Members> members(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        return read(() -> service.members(actor(auth), id, page, size));
    }

    @GetMapping("/{id}/member-users")
    public Mono<ExternalDataSourceService.Members> directory(
            @PathVariable String id,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        return read(() -> service.directory(actor(auth), id, search, page, size));
    }

    @PutMapping("/{id}/members/{userId}")
    public Mono<Void> grant(
            @PathVariable String id,
            @PathVariable String userId,
            @RequestBody GrantRequest req,
            Authentication auth) {
        return read(() -> {
                    service.grant(actor(auth), id, userId, req.role());
                    return true;
                })
                .then();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Mono<Void> revoke(
            @PathVariable String id, @PathVariable String userId, Authentication auth) {
        return read(() -> {
                    service.revoke(actor(auth), id, userId);
                    return true;
                })
                .then();
    }

    @GetMapping("/{id}/schemas")
    public Mono<List<String>> schemas(@PathVariable String id, Authentication auth) {
        return read(
                () -> {
                    var s = access.requireUse(actor(auth), id);
                    return DemoDataService.isBuiltin(s)
                            ? List.of(DemoDataService.SCHEMA)
                            : introspector.listSchemas(s);
                });
    }

    @GetMapping("/{id}/schemas/{schema}/tables")
    public Mono<List<TableVO>> tables(
            @PathVariable String id, @PathVariable String schema, Authentication auth) {
        return read(
                () -> {
                    var s = access.requireUse(actor(auth), id);
                    builtinSchema(s, schema);
                    return introspector.listTables(s, schema).stream()
                            .filter(
                                    t ->
                                            !DemoDataService.isBuiltin(s)
                                                    || DemoDataService.TABLE_NAMES.contains(
                                                            t.name()))
                            .map(t -> new TableVO(t.name(), t.type()))
                            .toList();
                });
    }

    @GetMapping("/{id}/schemas/{schema}/tables/{table}/columns")
    public Mono<List<ColumnVO>> columns(
            @PathVariable String id,
            @PathVariable String schema,
            @PathVariable String table,
            Authentication auth) {
        return read(
                () -> {
                    var s = access.requireUse(actor(auth), id);
                    builtinSchema(s, schema);
                    if (DemoDataService.isBuiltin(s)
                            && !DemoDataService.TABLE_NAMES.contains(table))
                        throw new DatasetException("表不存在", 404);
                    return introspector.listColumns(s, schema, table).stream()
                            .map(c -> new ColumnVO(c.name(), c.type(), c.description()))
                            .toList();
                });
    }

    private static void builtinSchema(
            io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity source,
            String schema) {
        if (DemoDataService.isBuiltin(source) && !DemoDataService.SCHEMA.equals(schema))
            throw new DatasetException("数据库不存在", 404);
    }

    private static Throwable status(Throwable t) {
        return t instanceof DatasetException d
                ? new ResponseStatusException(HttpStatus.valueOf(d.status()), d.getMessage(), d)
                : t;
    }
}
