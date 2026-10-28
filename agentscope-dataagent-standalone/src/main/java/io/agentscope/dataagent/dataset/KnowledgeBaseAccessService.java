package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Caller identity is never replaced by the KB owner; resolve the owner only after ACL checks. */
@Service
public class KnowledgeBaseAccessService {
    public enum Role {
        QUERY,
        MANAGE
    }

    private final DatasetGroupRepository groups;
    private final KnowledgeBaseGrantRepository grants;
    private final UserStore users;
    private final KnowledgeBaseOperations operations;

    public KnowledgeBaseAccessService(
            DatasetGroupRepository groups,
            KnowledgeBaseGrantRepository grants,
            UserStore users,
            KnowledgeBaseOperations operations) {
        this.groups = groups;
        this.grants = grants;
        this.users = users;
        this.operations = operations;
    }

    public boolean isAdmin(String userId) {
        return users.findById(userId).map(u -> u.hasRole("admin")).orElse(false);
    }

    public void requireUser(String userId) {
        if (userId == null || users.findById(userId).isEmpty())
            throw new DatasetException("用户不存在", 401);
    }

    public Role role(String userId, DatasetGroupEntity group) {
        if (users.findById(userId).isEmpty()) return null;
        if (group.getOwnerId().equals(userId) || isAdmin(userId)) return Role.MANAGE;
        return grants.findByGroupIdAndUserId(group.getId(), userId)
                .map(g -> Role.valueOf(g.getRole()))
                .orElse(group.isPublicQuery() ? Role.QUERY : null);
    }

    public DatasetGroupEntity requireQuery(String userId, String groupId) {
        return require(userId, groupId, false, true);
    }

    public DatasetGroupEntity requireManage(String userId, String groupId) {
        return require(userId, groupId, true, true);
    }

    /** Allows managers to retry pending deletion without reopening any data operation. */
    public DatasetGroupEntity requireManageForDeletion(String userId, String groupId) {
        return require(userId, groupId, true, false);
    }

    private DatasetGroupEntity require(
            String userId, String groupId, boolean manage, boolean active) {
        DatasetGroupEntity group =
                groups.findById(groupId == null ? "" : groupId)
                        .orElseThrow(() -> new DatasetException("知识库不存在或未授权", 404));
        Role role = role(userId, group);
        if (role == null) throw new DatasetException("知识库不存在或未授权", 404);
        if (manage && role != Role.MANAGE) throw new DatasetException("需要知识库管理权限", 403);
        if (active) operations.requireActive(groupId);
        return group;
    }

    public Page<DatasetGroupEntity> page(
            String userId, int page, int size, String search, String sort, boolean manageOnly) {
        requireUser(userId);
        if (page < 0 || size < 1 || size > 100)
            throw new DatasetException("分页参数无效，size 为 1-100", 400);
        String term = search == null ? "" : search.trim();
        if (term.length() > 100) throw new DatasetException("搜索内容最多 100 字", 400);
        Sort ordering =
                "name".equals(sort)
                        ? Sort.by("name").ascending().and(Sort.by("id"))
                        : Sort.by("createdAt").descending().and(Sort.by("id"));
        return groups.findAccessible(
                userId,
                isAdmin(userId),
                manageOnly,
                term.toLowerCase(Locale.ROOT),
                PageRequest.of(page, size, ordering));
    }

    public List<DatasetGroupEntity> visible(String userId, List<String> selected) {
        if (selected != null) {
            return selected.stream().distinct().map(id -> requireQuery(userId, id)).toList();
        }
        List<DatasetGroupEntity> result = new ArrayList<>();
        int page = 0;
        Page<DatasetGroupEntity> batch;
        do {
            batch = page(userId, page++, 100, "", "date", false);
            batch.getContent().stream()
                    .filter(g -> !operations.deletionPending(g.getId()))
                    .forEach(result::add);
        } while (batch.hasNext());
        return List.copyOf(result);
    }

    /** One batch lookup for the list page, avoiding a grant query per card. */
    public Map<String, Role> roles(String userId, List<DatasetGroupEntity> page) {
        Map<String, Role> result = new HashMap<>();
        if (page.isEmpty()) return result;
        boolean admin = isAdmin(userId);
        grants.findByUserIdAndGroupIdIn(
                        userId, page.stream().map(DatasetGroupEntity::getId).toList())
                .forEach(g -> result.put(g.getGroupId(), Role.valueOf(g.getRole())));
        page.stream()
                .filter(g -> admin || userId.equals(g.getOwnerId()))
                .forEach(g -> result.put(g.getId(), Role.MANAGE));
        page.stream()
                .filter(DatasetGroupEntity::isPublicQuery)
                .forEach(g -> result.putIfAbsent(g.getId(), Role.QUERY));
        return result;
    }
}
