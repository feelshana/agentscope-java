package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import java.util.List;
import org.springframework.stereotype.Service;

/** Tenant-scoped requirement review; execution receipts are never accepted from the client. */
@Service
public class MdlQuestionService {
    private final DatasetGroupService groups;
    private final MdlQuestionStore store;
    private final MdlPublishService publisher;
    private final WrenQueryGateway gateway;

    public MdlQuestionService(
            DatasetGroupService groups,
            MdlQuestionStore store,
            MdlPublishService publisher,
            WrenQueryGateway gateway) {
        this.groups = groups;
        this.store = store;
        this.publisher = publisher;
        this.gateway = gateway;
    }

    private void requireScope(DatasetScope scope, String groupId) {
        if (scope == null
                || scope.ownerId() == null
                || (scope.hasGroupFilter() && !scope.groupIds().contains(groupId))) {
            throw new DatasetException("知识库不存在或不可见", 404);
        }
        groups.getGroup(scope.ownerId(), groupId);
    }

    public List<MdlQuestionStore.Review> list(DatasetScope scope, String groupId) {
        requireScope(scope, groupId);
        return store.list(groupId);
    }

    public MdlQuestionStore.Review validate(DatasetScope scope, String groupId, String questionId) {
        requireScope(scope, groupId);
        return publisher.validateQuestion(groupId, questionId, gateway);
    }

    public String revision(DatasetScope scope, String groupId, String questionId) {
        requireScope(scope, groupId);
        return store.revision(groupId, questionId);
    }

    public MdlQuestionStore.Review correctSql(
            DatasetScope scope, String groupId, String questionId, String revision, String sql) {
        requireScope(scope, groupId);
        var result = store.correctSql(groupId, questionId, revision, sql);
        groups.markMdlDirty(scope.ownerId(), groupId);
        return result;
    }

    public void archive(DatasetScope scope, String groupId, String questionId, String revision) {
        requireScope(scope, groupId);
        store.archive(groupId, questionId, revision);
        groups.markMdlDirty(scope.ownerId(), groupId);
    }

    public MdlQuestionStore.Review decide(
            DatasetScope scope,
            String groupId,
            String questionId,
            String validationId,
            boolean accepted) {
        requireScope(scope, groupId);
        MdlQuestionStore.Review result =
                store.decide(groupId, questionId, validationId, accepted, scope.ownerId());
        groups.markMdlDirty(scope.ownerId(), groupId);
        return result;
    }
}
