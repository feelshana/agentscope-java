package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.tools.data.DashboardQueryService;
import io.agentscope.dataagent.web.session.DashboardDeletionService;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Authenticated asset APIs; generated code is returned as JSON, never executable same-origin HTML. */
@RestController
@RequestMapping("/api/dashboards")
public class DashboardController {
    private final DashboardService dashboards;
    private final DashboardQueryService queries;
    private final AgentAccessGuard guard;
    private final DashboardDeletionService deletion;
    private final io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository users;
    private static final ObjectMapper JSON = new ObjectMapper();

    public DashboardController(
            DashboardService dashboards,
            DashboardQueryService queries,
            AgentAccessGuard guard,
            DashboardDeletionService deletion,
            io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository users) {
        this.dashboards = dashboards;
        this.queries = queries;
        this.guard = guard;
        this.deletion = deletion;
        this.users = users;
    }

    public record Create(String groupId, String title, String description) {}

    public record Publish(String revision) {}

    public record Restore(String revision, String targetRevision) {}

    public record Metadata(String title, String description, String expectedUpdatedAt) {}

    public record Query(boolean draft, String revision, String sql) {}

    private <T> Mono<T> authorized(Authentication auth, Callable<T> action) {
        return Mono.fromCallable(
                        () -> {
                            guard.require(
                                    (String) auth.getPrincipal(),
                                    DashboardService.AGENT_ID,
                                    Tier.RUN);
                            return action.call();
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.valueOf(e.status()),
                                        e.getMessage(),
                                        e));
    }

    public record LibraryItem(
            @com.fasterxml.jackson.annotation.JsonUnwrapped DashboardService.View dashboard,
            String creatorName) {}

    @GetMapping
    public Mono<List<LibraryItem>> list(Authentication auth) {
        return authorized(
                auth,
                () -> {
                    var visible = dashboards.list((String) auth.getPrincipal());
                    var names = new java.util.HashMap<String, String>();
                    users.findAllById(
                                    visible.stream()
                                            .map(DashboardService.View::creatorId)
                                            .filter(java.util.Objects::nonNull)
                                            .distinct()
                                            .toList())
                            .forEach(
                                    user ->
                                            names.put(
                                                    user.getUserId(),
                                                    user.getDisplayName() == null
                                                                    || user.getDisplayName()
                                                                            .isBlank()
                                                            ? user.getUsername()
                                                            : user.getDisplayName()));
                    return visible.stream()
                            .map(
                                    item ->
                                            new LibraryItem(
                                                    item,
                                                    names.getOrDefault(item.creatorId(), "已移除用户")))
                            .toList();
                });
    }

    @PostMapping
    public Mono<DashboardService.View> create(Authentication auth, @RequestBody Create body) {
        return authorized(
                auth,
                () ->
                        dashboards.create(
                                (String) auth.getPrincipal(),
                                body.groupId(),
                                body.title(),
                                body.description()));
    }

    @GetMapping("/{id}")
    public Mono<DashboardService.View> get(
            Authentication auth,
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean draft) {
        return authorized(auth, () -> dashboards.get((String) auth.getPrincipal(), id, draft));
    }

    @PatchMapping("/{id}")
    public Mono<DashboardService.View> updateMetadata(
            Authentication auth, @PathVariable String id, @RequestBody Metadata body) {
        return authorized(
                auth,
                () ->
                        dashboards.updateMetadata(
                                (String) auth.getPrincipal(),
                                id,
                                body.title(),
                                body.description(),
                                body.expectedUpdatedAt()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public Mono<Void> delete(Authentication auth, @PathVariable String id) {
        return authorized(
                auth,
                () -> {
                    deletion.delete((String) auth.getPrincipal(), id);
                    return null;
                });
    }

    @PostMapping("/{id}/checkpoint")
    public Mono<DashboardService.Checkpoint> checkpoint(
            Authentication auth, @PathVariable String id, @RequestBody Publish body) {
        return authorized(
                auth,
                () ->
                        dashboards.checkpoint(
                                (String) auth.getPrincipal(),
                                id,
                                body.revision(),
                                () -> deletion.requireIdle(id)));
    }

    @PostMapping("/{id}/restore")
    public Mono<DashboardService.View> restore(
            Authentication auth, @PathVariable String id, @RequestBody Restore body) {
        return authorized(
                auth,
                () ->
                        dashboards.restore(
                                (String) auth.getPrincipal(),
                                id,
                                body.revision(),
                                body.targetRevision(),
                                () -> deletion.requireIdle(id)));
    }

    @PostMapping("/{id}/publish")
    public Mono<DashboardService.View> publish(
            Authentication auth, @PathVariable String id, @RequestBody Publish body) {
        return authorized(
                auth, () -> dashboards.publish((String) auth.getPrincipal(), id, body.revision()));
    }

    @PostMapping("/{id}/query")
    public Mono<java.util.Map<String, Object>> query(
            Authentication auth, @PathVariable String id, @RequestBody Query body) {
        return authorized(
                auth,
                () -> {
                    String result =
                            queries.query(
                                    (String) auth.getPrincipal(),
                                    id,
                                    body.draft(),
                                    body.revision(),
                                    body.sql());
                    JsonNode json = JSON.readTree(result);
                    if (json == null
                            || !json.isObject()
                            || !json.path("rows").isArray()
                            || !json.path("columns").isArray())
                        throw new DatasetException("Wren未返回表格数据，请修改查询后重试", 502);
                    if (json.path("truncated").asBoolean(false))
                        throw new DatasetException("结果超过1000行，请聚合或缩小筛选范围，不能显示不完整统计", 422);
                    // Official MCP versions return object rows or positional rows. The live
                    // bridge promises positional rows so generated pages need no version branch.
                    var normalized =
                            ((com.fasterxml.jackson.databind.node.ObjectNode) json).deepCopy();
                    var rows = JSON.createArrayNode();
                    for (JsonNode row : json.path("rows")) {
                        if (row.isArray()) rows.add(row);
                        else if (row.isObject()) {
                            var values = JSON.createArrayNode();
                            for (JsonNode column : json.path("columns")) {
                                String name =
                                        column.isTextual()
                                                ? column.asText()
                                                : column.path("name").asText();
                                if (!row.has(name)) throw new DatasetException("查询结果的列与数据不匹配", 502);
                                values.add(row.get(name));
                            }
                            rows.add(values);
                        } else throw new DatasetException("查询返回了不支持的数据格式", 502);
                    }
                    normalized.set("rows", rows);
                    // Boot 4 uses Jackson 3 on HTTP; never expose a Jackson 2 tree as a POJO.
                    return JSON.convertValue(
                            normalized,
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    java.util.Map<String, Object>>() {});
                });
    }
}
