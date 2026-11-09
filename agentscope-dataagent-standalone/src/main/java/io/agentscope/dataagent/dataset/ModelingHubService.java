package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Bounded list projection. The workbench continues to own its detailed reads. */
@Service
public class ModelingHubService {
    private static final Logger log = LoggerFactory.getLogger(ModelingHubService.class);
    private final KnowledgeBaseAccessService access;
    private final KnowledgeBaseBrowseService browse;
    private final DraftReviewService draft;
    private final ModelingConfirmationService confirmations;

    public ModelingHubService(
            KnowledgeBaseAccessService access,
            KnowledgeBaseBrowseService browse,
            DraftReviewService draft,
            ModelingConfirmationService confirmations) {
        this.access = access;
        this.browse = browse;
        this.draft = draft;
        this.confirmations = confirmations;
    }

    public record Summary(
            String groupId,
            KnowledgeBaseBrowseService.Summary published,
            DraftReviewService.Summary draft,
            Integer confirmations) {}

    public List<Summary> summaries(String actor, List<String> ids) {
        access.requireUser(actor);
        if (ids.size() > 10) throw new DatasetException("最多查询 10 个知识库的建模摘要", 400);
        var result = new ArrayList<Summary>();
        for (String id : ids.stream().distinct().toList()) {
            try {
                DatasetGroupEntity group = access.requireQuery(actor, id);
                boolean manage =
                        access.role(actor, group) == KnowledgeBaseAccessService.Role.MANAGE;
                var published = publishedSummary(group);
                var changes = manage ? draft.summary(actor, id) : null;
                Integer pending = manage ? confirmations.list(actor, id).size() : null;
                var current = access.requireQuery(actor, id);
                if (manage
                        && access.role(actor, current) != KnowledgeBaseAccessService.Role.MANAGE) {
                    changes = null;
                    pending = null;
                }
                result.add(new Summary(id, published, changes, pending));
            } catch (DatasetException e) {
                if (e.status() != 403 && e.status() != 404)
                    log.warn("Could not read modeling summary for {}", id, e);
            }
        }
        return List.copyOf(result);
    }

    private KnowledgeBaseBrowseService.Summary publishedSummary(DatasetGroupEntity group) {
        try {
            return browse.publishedSummary(group);
        } catch (IOException e) {
            log.warn("Could not read published model counts for {}", group.getId(), e);
            return null;
        }
    }
}
