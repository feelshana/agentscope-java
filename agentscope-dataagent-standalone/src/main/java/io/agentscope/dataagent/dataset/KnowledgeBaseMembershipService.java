package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantAuditEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantRepository;
import io.agentscope.dataagent.web.persistence.jpa.UserEntity;
import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseMembershipService {
    public record Member(
            String userId,
            String username,
            String role,
            boolean owner,
            boolean admin,
            String updatedAt) {}

    public record Members(Member creator, List<Member> items, long total, int page, int size) {}

    public record Directory(List<Member> items, long total, int page, int size) {}

    private final KnowledgeBaseAccessService access;
    private final DatasetGroupRepository groups;
    private final KnowledgeBaseGrantRepository grants;
    private final UserEntityRepository users;
    @PersistenceContext private EntityManager entityManager;

    public KnowledgeBaseMembershipService(
            KnowledgeBaseAccessService access,
            DatasetGroupRepository groups,
            KnowledgeBaseGrantRepository grants,
            UserEntityRepository users) {
        this.access = access;
        this.groups = groups;
        this.grants = grants;
        this.users = users;
    }

    public Members members(String actor, String groupId, int page, int size) {
        DatasetGroupEntity group = access.requireManage(actor, groupId);
        var entries =
                grants.findByGroupId(
                        groupId,
                        pagination(page, size)
                                .withSort(
                                        org.springframework.data.domain.Sort.by("updatedAt")
                                                .descending()
                                                .and(
                                                        org.springframework.data.domain.Sort.by(
                                                                "userId"))));
        Map<String, UserEntity> names = new HashMap<>();
        users.findAllById(entries.stream().map(KnowledgeBaseGrantEntity::getUserId).toList())
                .forEach(u -> names.put(u.getUserId(), u));
        Member creator =
                new Member(
                        group.getOwnerId(),
                        users.findById(group.getOwnerId())
                                .map(UserEntity::getUsername)
                                .orElse("已移除用户"),
                        "MANAGE",
                        true,
                        access.isAdmin(group.getOwnerId()),
                        null);
        List<Member> items =
                entries.stream()
                        .map(
                                g -> {
                                    UserEntity u = names.get(g.getUserId());
                                    return new Member(
                                            g.getUserId(),
                                            u == null ? "已移除用户" : u.getUsername(),
                                            g.getRole(),
                                            false,
                                            admin(u),
                                            g.getUpdatedAt().toString());
                                })
                        .toList();
        return new Members(creator, items, entries.getTotalElements(), page, size);
    }

    public Directory directory(String actor, String groupId, String search, int page, int size) {
        DatasetGroupEntity group = access.requireManage(actor, groupId);
        if (search != null && search.length() > 100)
            throw new DatasetException("搜索内容最多 100 字", 400);
        var found =
                users.findByUsernameContainingIgnoreCase(
                        search == null ? "" : search.trim(),
                        pagination(page, size)
                                .withSort(
                                        org.springframework.data.domain.Sort.by("username")
                                                .and(
                                                        org.springframework.data.domain.Sort.by(
                                                                "userId"))));
        Map<String, String> existing = new HashMap<>();
        grants.findByGroupIdAndUserIdIn(groupId, found.stream().map(UserEntity::getUserId).toList())
                .forEach(g -> existing.put(g.getUserId(), g.getRole()));
        List<Member> result =
                found.stream()
                        .map(
                                u -> {
                                    boolean owner = u.getUserId().equals(group.getOwnerId());
                                    boolean admin = admin(u);
                                    return new Member(
                                            u.getUserId(),
                                            u.getUsername(),
                                            owner || admin ? "MANAGE" : existing.get(u.getUserId()),
                                            owner,
                                            admin,
                                            null);
                                })
                        .toList();
        return new Directory(result, found.getTotalElements(), page, size);
    }

    @Transactional
    public void grant(String actor, String groupId, String userId, String role) {
        lockAndRequire(actor, groupId, userId);
        if (!users.existsById(userId)) throw new DatasetException("用户不存在", 404);
        if (!Set.of("QUERY", "MANAGE").contains(role == null ? "" : role))
            throw new DatasetException("权限只能为 QUERY 或 MANAGE", 400);
        var grant =
                grants.findByGroupIdAndUserId(groupId, userId)
                        .orElseGet(() -> new KnowledgeBaseGrantEntity(groupId, userId));
        String previous = grant.getRole();
        grant.grant(role, actor);
        grants.save(grant);
        entityManager.persist(
                new KnowledgeBaseGrantAuditEntity(groupId, userId, actor, previous, role));
    }

    @Transactional
    public void revoke(String actor, String groupId, String userId) {
        lockAndRequire(actor, groupId, userId);
        grants.findByGroupIdAndUserId(groupId, userId)
                .ifPresent(
                        g -> {
                            entityManager.persist(
                                    new KnowledgeBaseGrantAuditEntity(
                                            groupId, userId, actor, g.getRole(), null));
                            grants.delete(g);
                        });
    }

    private void lockAndRequire(String actor, String groupId, String userId) {
        groups.findByIdForUpdate(groupId)
                .orElseThrow(() -> new DatasetException("知识库不存在或未授权", 404));
        DatasetGroupEntity group = access.requireManage(actor, groupId);
        UserEntity target = users.findById(userId).orElse(null);
        if (group.getOwnerId().equals(userId) || admin(target))
            throw new DatasetException("创建者及全局管理员固定拥有管理权限，无需单独授权", 400);
    }

    private static boolean admin(UserEntity u) {
        return u != null
                && u.getRolesCsv() != null
                && Arrays.stream(u.getRolesCsv().split(","))
                        .map(String::trim)
                        .anyMatch("admin"::equals);
    }

    private static PageRequest pagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new DatasetException("分页参数无效", 400);
        return PageRequest.of(page, size);
    }
}
