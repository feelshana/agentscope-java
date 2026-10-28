package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantAuditEntity;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicKnowledgeBaseService {
    private final KnowledgeBaseAccessService access;
    private final DatasetGroupRepository groups;
    private final EntityManager em;

    public PublicKnowledgeBaseService(
            KnowledgeBaseAccessService access, DatasetGroupRepository groups, EntityManager em) {
        this.access = access;
        this.groups = groups;
        this.em = em;
    }

    public void requireAdmin(String actor) {
        if (!access.isAdmin(actor)) throw new DatasetException("仅全局管理员可配置公共知识库", 403);
    }

    public record Item(String id, String name, int publishedVersion, boolean publicQuery) {}

    public record Page(java.util.List<Item> items, long total, int page) {}

    public Page list(String actor, String search, int page) {
        requireAdmin(actor);
        var found = access.page(actor, page, 20, search, "name", false);
        return new Page(
                found.stream()
                        .map(
                                g ->
                                        new Item(
                                                g.getId(),
                                                g.getName(),
                                                g.getMdlVersion(),
                                                g.isPublicQuery()))
                        .toList(),
                found.getTotalElements(),
                page);
    }

    @Transactional
    public void set(String actor, String id, boolean enabled) {
        requireAdmin(actor);
        var group =
                groups.findByIdForUpdate(id).orElseThrow(() -> new DatasetException("知识库不存在", 404));
        access.requireManage(actor, id);
        if (enabled && group.getMdlVersion() < 1)
            throw new DatasetException("请先发布模型，再设为全员可使用", 409);
        if (group.isPublicQuery() == enabled) return;
        em.persist(
                new KnowledgeBaseGrantAuditEntity(
                        id,
                        "*",
                        actor,
                        group.isPublicQuery() ? "QUERY" : null,
                        enabled ? "QUERY" : null));
        group.setPublicQuery(enabled);
        groups.save(group);
    }
}
