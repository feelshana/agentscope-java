package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceAuditEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceGrantEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceGrantRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import io.agentscope.dataagent.web.persistence.jpa.UserEntity;
import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional source lifecycle and membership; never exposes stored credentials. */
@Service
public class ExternalDataSourceService {
    public record ConnectionRequest(
            String name,
            String kind,
            String jdbcUrl,
            String username,
            String password,
            Boolean sampling,
            String host,
            Integer port,
            String database,
            String sslMode) {}

    public record View(
            String id,
            String name,
            String kind,
            String jdbcUrl,
            String username,
            boolean sampling,
            String createdAt,
            boolean builtin,
            String ownerId,
            String creatorName,
            String creatorAccount,
            String role,
            boolean canManage,
            boolean canGovern,
            boolean enabled,
            long knowledgeBaseCount,
            boolean targetLocked,
            boolean shared) {}

    public record Status(boolean connected, String error) {}

    public record Member(
            String userId,
            String username,
            String displayName,
            String department,
            String role,
            boolean fixed) {}

    public record Members(List<Member> items, long total, int page, int size, Member creator) {}

    private final ExternalDataSourceRepository sources;
    private final ExternalDataSourceGrantRepository grants;
    private final DatasetRepository datasets;
    private final UserEntityRepository users;
    private final ExternalDataSourceAccess access;
    private final DataSourceIntrospector introspector;
    private final ExternalDataSourcePolicy policy;
    private final DemoDataService demo;
    private final EntityManager em;
    private final ApplicationEventPublisher events;

    public ExternalDataSourceService(
            ExternalDataSourceRepository sources,
            ExternalDataSourceGrantRepository grants,
            DatasetRepository datasets,
            UserEntityRepository users,
            ExternalDataSourceAccess access,
            DataSourceIntrospector introspector,
            ExternalDataSourcePolicy policy,
            DemoDataService demo,
            EntityManager em,
            ApplicationEventPublisher events) {
        this.sources = sources;
        this.grants = grants;
        this.datasets = datasets;
        this.users = users;
        this.access = access;
        this.introspector = introspector;
        this.policy = policy;
        this.demo = demo;
        this.em = em;
        this.events = events;
    }

    public List<View> list(String actor) {
        access.requireUser(actor);
        boolean admin = access.isAdmin(actor);
        var visible = new ArrayList<>(sources.findVisible(actor, admin));
        visible.removeIf(
                s ->
                        DemoDataService.isBuiltin(s)
                                && (!DemoDataService.isCurrentBuiltin(s)
                                        || !demo.sourceAvailable()));
        if (demo.sourceAvailable() && visible.stream().noneMatch(DemoDataService::isBuiltin))
            sources.findById(DemoDataService.SOURCE_ID).ifPresent(visible::add);
        if (visible.isEmpty()) return List.of();
        Map<String, String> roles = new HashMap<>();
        grants.findByUserId(actor).forEach(g -> roles.put(g.getDatasourceId(), g.getRole()));
        Map<String, UserEntity> creators = new HashMap<>();
        users.findAllById(
                        visible.stream()
                                .map(ExternalDataSourceEntity::getOwnerId)
                                .distinct()
                                .toList())
                .forEach(u -> creators.put(u.getUserId(), u));
        Map<String, Long> counts = new HashMap<>();
        datasets.countSourceBindings(visible.stream().map(ExternalDataSourceEntity::getId).toList())
                .forEach(c -> counts.put(c.getSourceId(), c.getTotal()));
        return visible.stream()
                .map(
                        s ->
                                view(
                                        actor,
                                        s,
                                        admin,
                                        roles.get(s.getId()),
                                        creators.get(s.getOwnerId()),
                                        counts.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    public View get(String actor, String id) {
        var s = access.source(id);
        var role = access.role(actor, s);
        if (role == null) throw new DatasetException("数据源不存在或未授权", 404);
        String grant =
                grants.findByDatasourceIdAndUserId(id, actor)
                        .map(ExternalDataSourceGrantEntity::getRole)
                        .orElse(null);
        return view(
                actor,
                s,
                access.isAdmin(actor),
                grant,
                users.findById(s.getOwnerId()).orElse(null),
                datasets.findGroupIdsByExternalSource(id).size());
    }

    private static View view(
            String actor,
            ExternalDataSourceEntity s,
            boolean admin,
            String grant,
            UserEntity creator,
            long count) {
        boolean builtin = DemoDataService.isBuiltin(s), owner = s.getOwnerId().equals(actor);
        boolean govern = !builtin && (admin || owner),
                manage = !builtin && (govern || "MANAGE".equals(grant));
        String name = creator == null ? s.getOwnerId() : creator.getDisplayName();
        if (name == null || name.isBlank())
            name = creator == null ? s.getOwnerId() : creator.getUsername();
        return new View(
                s.getId(),
                s.getName(),
                s.getKind(),
                manage ? s.getJdbcUrl() : null,
                manage ? s.getUsername() : null,
                s.isSampling(),
                s.getCreatedAt() == null ? null : s.getCreatedAt().toString(),
                builtin,
                s.getOwnerId(),
                name,
                creator == null ? s.getOwnerId() : creator.getUsername(),
                owner && !builtin ? "OWNER" : manage ? "MANAGE" : "USE",
                manage,
                govern,
                s.isEnabled(),
                count,
                count > 0,
                !owner && grant != null && !builtin);
    }

    @Transactional
    public View create(String actor, ConnectionRequest request) {
        access.requireUser(actor);
        var s = candidate(actor, request, null);
        uniqueName(s);
        supported(s);
        sources.saveAndFlush(s);
        audit(s.getId(), actor, null, "CREATE", null, null);
        return get(actor, s.getId());
    }

    @Transactional
    public View update(String actor, String id, ConnectionRequest request) {
        access.lock(id);
        var s = access.requireManage(actor, id);
        var candidate = candidate(s.getOwnerId(), request, s);
        if (!datasets.findGroupIdsByExternalSource(id).isEmpty() && !sameTarget(s, candidate))
            throw new DatasetException("该数据源已关联知识库，不能更换主机、数据库或数据库账号。请创建新数据源后重新关联。", 409);
        uniqueName(candidate);
        supported(candidate);
        s.setName(candidate.getName());
        s.setKind(candidate.getKind());
        s.setJdbcUrl(candidate.getJdbcUrl());
        s.setUsername(candidate.getUsername());
        s.setPassword(candidate.getPassword());
        s.setSampling(candidate.isSampling());
        s.setUpdatedAt(Instant.now());
        sources.saveAndFlush(s);
        audit(id, actor, null, "UPDATE", null, null);
        events.publishEvent(new ExternalDataSourceConnectionChanged(id));
        return get(actor, id);
    }

    @Transactional
    public View setEnabled(String actor, String id, boolean enabled) {
        access.lock(id);
        var s = access.requireGovern(actor, id);
        if (s.isEnabled() != enabled) {
            s.setEnabled(enabled);
            s.setUpdatedAt(Instant.now());
            sources.saveAndFlush(s);
            audit(id, actor, null, enabled ? "ENABLE" : "DISABLE", null, null);
            events.publishEvent(new ExternalDataSourceConnectionChanged(id));
        }
        return get(actor, id);
    }

    @Transactional
    public void delete(String actor, String id) {
        access.lock(id);
        access.requireGovern(actor, id);
        int count = datasets.findGroupIdsByExternalSource(id).size();
        if (count > 0) throw new DatasetException("该数据源正在被 " + count + " 个知识库使用，请先解除关联后再删除。", 409);
        grants.deleteByDatasourceId(id);
        sources.deleteById(id);
        audit(id, actor, null, "DELETE", null, null);
        events.publishEvent(new ExternalDataSourceConnectionChanged(id));
    }

    public Status test(String actor, ConnectionRequest request, String id) {
        access.requireUser(actor);
        // Credential reuse is restricted to configuration managers, never source consumers.
        var saved = id == null || id.isBlank() ? null : access.requireManage(actor, id);
        var candidate = candidate(actor, request, saved);
        if (saved != null
                && !datasets.findGroupIdsByExternalSource(id).isEmpty()
                && !sameTarget(saved, candidate))
            throw new DatasetException("该数据源已关联知识库，不能借用已有凭据检测其他主机、数据库或账号。", 409);
        String error = introspector.connectionError(candidate);
        return new Status(error == null, error);
    }

    public Status status(String actor, String id) {
        var s = access.requireUse(actor, id);
        String error = introspector.connectionError(s);
        if (error != null && access.role(actor, s) == ExternalDataSourceAccess.Role.USE)
            error = "连接暂不可用，请联系创建人或全局管理员检查。";
        return new Status(error == null, error);
    }

    public Members members(String actor, String id, int page, int size) {
        var s = access.requireGovern(actor, id);
        var p = grants.findByDatasourceId(id, paging(page, size));
        Map<String, UserEntity> people = new HashMap<>();
        users.findAllById(p.map(ExternalDataSourceGrantEntity::getUserId))
                .forEach(u -> people.put(u.getUserId(), u));
        var items =
                p.stream()
                        .map(
                                g ->
                                        member(
                                                people.get(g.getUserId()),
                                                g.getUserId(),
                                                g.getRole(),
                                                false))
                        .toList();
        return new Members(
                items,
                p.getTotalElements(),
                page,
                size,
                member(users.findById(s.getOwnerId()).orElse(null), s.getOwnerId(), "OWNER", true));
    }

    public Members directory(String actor, String id, String search, int page, int size) {
        var s = access.requireGovern(actor, id);
        if (search.length() > 100) throw new DatasetException("搜索内容最多100字", 400);
        var p =
                users.searchDirectory(
                        search.trim().toLowerCase(Locale.ROOT),
                        "",
                        "",
                        paging(page, size).withSort(Sort.by("username")));
        Map<String, String> roles = new HashMap<>();
        if (!p.isEmpty())
            grants.findByDatasourceIdAndUserIdIn(id, p.stream().map(UserEntity::getUserId).toList())
                    .forEach(g -> roles.put(g.getUserId(), g.getRole()));
        return new Members(
                p.stream()
                        .map(
                                u ->
                                        member(
                                                u,
                                                u.getUserId(),
                                                u.getUserId().equals(s.getOwnerId())
                                                        ? "OWNER"
                                                        : admin(u)
                                                                ? "MANAGE"
                                                                : roles.get(u.getUserId()),
                                                u.getUserId().equals(s.getOwnerId()) || admin(u)))
                        .toList(),
                p.getTotalElements(),
                page,
                size,
                null);
    }

    @Transactional
    public void grant(String actor, String id, String userId, String role) {
        access.lock(id);
        var s = access.requireGovern(actor, id);
        var user = target(s, userId);
        if (!Set.of("USE", "MANAGE").contains(role == null ? "" : role))
            throw new DatasetException("权限只能为USE或MANAGE", 400);
        var grant =
                grants.findByDatasourceIdAndUserId(id, user.getUserId())
                        .orElseGet(() -> new ExternalDataSourceGrantEntity(id, userId));
        String previous = grant.getRole();
        grant.grant(role, actor);
        grants.save(grant);
        audit(id, actor, userId, "GRANT", previous, role);
    }

    @Transactional
    public void revoke(String actor, String id, String userId) {
        access.lock(id);
        var s = access.requireGovern(actor, id);
        target(s, userId);
        grants.findByDatasourceIdAndUserId(id, userId)
                .ifPresent(
                        g -> {
                            audit(id, actor, userId, "REVOKE", g.getRole(), null);
                            grants.delete(g);
                        });
    }

    private UserEntity target(ExternalDataSourceEntity source, String userId) {
        var u = users.findById(userId).orElseThrow(() -> new DatasetException("用户不存在", 404));
        if (source.getOwnerId().equals(userId) || admin(u))
            throw new DatasetException("创建人和全局管理员固定拥有完整管理权限，无需单独授权", 400);
        return u;
    }

    private static boolean admin(UserEntity u) {
        return u != null
                && u.getRolesCsv() != null
                && Arrays.stream(u.getRolesCsv().split(","))
                        .map(String::trim)
                        .anyMatch("admin"::equals);
    }

    private static Member member(UserEntity u, String id, String role, boolean fixed) {
        return new Member(
                id,
                u == null ? id : u.getUsername(),
                u == null ? null : u.getDisplayName(),
                u == null ? null : u.getDepartment(),
                role,
                fixed);
    }

    private static PageRequest paging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new DatasetException("分页参数无效", 400);
        return PageRequest.of(page, size, Sort.by("id"));
    }

    private void audit(
            String id, String actor, String subject, String action, String previous, String next) {
        em.persist(new ExternalDataSourceAuditEntity(id, actor, subject, action, previous, next));
    }

    private ExternalDataSourceEntity candidate(
            String actor, ConnectionRequest r, ExternalDataSourceEntity saved) {
        if (r == null
                || r.name() == null
                || r.name().trim().isEmpty()
                || r.name().trim().length() > 64) throw new DatasetException("数据源名称为1至64字", 400);
        String kind = r.kind() == null || r.kind().isBlank() ? "mysql" : r.kind();
        if (r.host() != null && r.jdbcUrl() != null && !r.jdbcUrl().isBlank())
            throw new DatasetException("不能同时提交连接地址和主机字段", 400);
        String url =
                r.host() == null
                        ? policy.normalize(kind, r.jdbcUrl())
                        : policy.build(kind, r.host(), r.port(), r.database(), r.sslMode());
        String password =
                saved != null && (r.password() == null || r.password().isBlank())
                        ? saved.getPassword()
                        : r.password();
        var candidate =
                new ExternalDataSourceEntity(
                        saved == null ? UUID.randomUUID().toString() : saved.getId(),
                        actor,
                        r.name().trim(),
                        kind,
                        url,
                        r.username(),
                        password,
                        r.sampling() == null ? saved == null || saved.isSampling() : r.sampling());
        return candidate;
    }

    private void uniqueName(ExternalDataSourceEntity source) {
        sources.findByOwnerIdAndName(source.getOwnerId(), source.getName())
                .filter(s -> !s.getId().equals(source.getId()))
                .ifPresent(
                        s -> {
                            throw new DatasetException("你已创建同名数据源，请更换名称", 409);
                        });
    }

    private void supported(ExternalDataSourceEntity s) {
        String error = introspector.connectionError(s);
        if (error != null) throw new DatasetException("数据源不可用：" + error, 400);
    }

    private static boolean sameTarget(ExternalDataSourceEntity a, ExternalDataSourceEntity b) {
        return a.getKind().equalsIgnoreCase(b.getKind())
                && Objects.equals(a.getUsername(), b.getUsername())
                && a.getJdbcUrl().split("\\?", 2)[0].equals(b.getJdbcUrl().split("\\?", 2)[0]);
    }
}
