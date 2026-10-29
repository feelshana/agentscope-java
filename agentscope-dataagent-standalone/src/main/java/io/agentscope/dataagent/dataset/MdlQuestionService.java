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
    private final FactQueryWindowPolicy windowPolicy;
    private final QuestionSuggestionService suggestions;

    public MdlQuestionService(
            DatasetGroupService groups,
            MdlQuestionStore store,
            MdlPublishService publisher,
            WrenQueryGateway gateway) {
        this(groups, store, publisher, gateway, null, null);
    }

    public MdlQuestionService(
            DatasetGroupService groups,
            MdlQuestionStore store,
            MdlPublishService publisher,
            WrenQueryGateway gateway,
            FactQueryWindowPolicy windowPolicy) {
        this(groups, store, publisher, gateway, windowPolicy, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MdlQuestionService(
            DatasetGroupService groups,
            MdlQuestionStore store,
            MdlPublishService publisher,
            WrenQueryGateway gateway,
            FactQueryWindowPolicy windowPolicy,
            QuestionSuggestionService suggestions) {
        this.groups = groups;
        this.store = store;
        this.publisher = publisher;
        this.gateway = gateway;
        this.windowPolicy = windowPolicy;
        this.suggestions = suggestions;
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
        requireWindow(scope, groupId, questionId, "question validate");
        return publisher.validateQuestion(groupId, questionId, gateway);
    }

    /** Same per-table time window as every other SQL execution path (spec090/091). */
    private void requireWindow(
            DatasetScope scope, String groupId, String questionId, String operation) {
        if (windowPolicy == null) return;
        String sql = store.requireQuestion(groupId, questionId).sql();
        if (sql != null && !sql.isBlank())
            windowPolicy.requireAllowed(
                    io.agentscope.core.agent.RuntimeContext.builder()
                            .userId(scope.ownerId())
                            .build(),
                    groupId,
                    sql,
                    operation);
    }

    /**
     * 2026-10-28: a hand-edited SQL with a syntax error was saved and confirmed, then silently
     * dropped by the publish-time dry-plan. Saving and confirming now run the same check first.
     */
    private void requirePlans(String groupId, String sql, String prefix) {
        if (publisher == null || sql == null || sql.isBlank()) return;
        String error = publisher.planDraftSql(groupId, sql);
        if (error != null) throw new DatasetException(prefix + error, 400);
    }

    /** Suggested question texts for the post-modeling dialog; empty when unavailable. */
    public List<String> suggestions(DatasetScope scope, String groupId) {
        requireScope(scope, groupId);
        if (suggestions == null) return List.of();
        List<String> existing =
                store.list(groupId).stream().map(r -> r.question().question()).toList();
        return suggestions.suggest(groupId, existing);
    }

    public String revision(DatasetScope scope, String groupId, String questionId) {
        requireScope(scope, groupId);
        return store.revision(groupId, questionId);
    }

    public MdlQuestionStore.Review correctSql(
            DatasetScope scope, String groupId, String questionId, String revision, String sql) {
        requireScope(scope, groupId);
        requirePlans(groupId, sql, "SQL 检查未通过，未保存：");
        return store.locked(
                groupId,
                () -> {
                    var result = store.correctSql(groupId, questionId, revision, sql);
                    groups.markMdlDirty(scope.ownerId(), groupId);
                    return result;
                });
    }

    public void archive(DatasetScope scope, String groupId, String questionId, String revision) {
        requireScope(scope, groupId);
        store.locked(
                groupId,
                () -> {
                    store.archive(groupId, questionId, revision);
                    groups.markMdlDirty(scope.ownerId(), groupId);
                    return null;
                });
    }

    public MdlQuestionStore.Review decide(
            DatasetScope scope, String groupId, String questionId, boolean accepted) {
        requireScope(scope, groupId);
        if (accepted) {
            requireWindow(scope, groupId, questionId, "question confirm");
            MdlQuestionStore.Question question = store.requireQuestion(groupId, questionId);
            if (question != null) requirePlans(groupId, question.sql(), "SQL 检查未通过，不能确认：");
        }
        return store.locked(
                groupId,
                () -> {
                    MdlQuestionStore.Review result =
                            store.decide(groupId, questionId, accepted, scope.ownerId());
                    groups.markMdlDirty(scope.ownerId(), groupId);
                    return result;
                });
    }
}
