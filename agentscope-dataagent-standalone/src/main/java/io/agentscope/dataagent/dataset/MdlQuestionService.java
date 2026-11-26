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
    private final ModelingActivity activity;

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

    public MdlQuestionService(
            DatasetGroupService groups,
            MdlQuestionStore store,
            MdlPublishService publisher,
            WrenQueryGateway gateway,
            FactQueryWindowPolicy windowPolicy,
            QuestionSuggestionService suggestions) {
        this(groups, store, publisher, gateway, windowPolicy, suggestions, new ModelingActivity());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MdlQuestionService(
            DatasetGroupService groups,
            MdlQuestionStore store,
            MdlPublishService publisher,
            WrenQueryGateway gateway,
            FactQueryWindowPolicy windowPolicy,
            QuestionSuggestionService suggestions,
            ModelingActivity activity) {
        this.groups = groups;
        this.store = store;
        this.publisher = publisher;
        this.gateway = gateway;
        this.windowPolicy = windowPolicy;
        this.suggestions = suggestions;
        this.activity = activity;
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

    public List<MdlQuestionStore.Review> listWithPendingRemovals(
            DatasetScope scope, String groupId) {
        requireScope(scope, groupId);
        return store.list(groupId, true);
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
        requireSqlWindow(scope, groupId, sql, operation);
    }

    private void requireSqlWindow(
            DatasetScope scope, String groupId, String sql, String operation) {
        if (windowPolicy == null) return;
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

    /**
     * spec103: the one recommended question of the draft model for the trial panel (not stored).
     * Same access as {@link #suggestions}.
     */
    public RecommendedQuestionService.Recommendation recommended(
            DatasetScope scope, String groupId, RecommendedQuestionService recommended) {
        requireScope(scope, groupId);
        return recommended == null
                ? new RecommendedQuestionService.Recommendation(null, false)
                : recommended.trial(groupId);
    }

    /** spec098: the modeler edits the question ({@code regenerate} asks for a new one). */
    public RecommendedQuestionService.Recommendation changeRecommended(
            DatasetScope scope,
            String groupId,
            RecommendedQuestionService recommended,
            String question,
            boolean regenerate) {
        requireScope(scope, groupId);
        if (recommended == null) throw new DatasetException("推荐问题不可用", 503);
        return regenerate ? recommended.regenerate(groupId) : recommended.edit(groupId, question);
    }

    public String revision(DatasetScope scope, String groupId, String questionId) {
        requireScope(scope, groupId);
        return store.revision(groupId, questionId);
    }

    public String reviewTitle(
            DatasetScope scope, String groupId, String questionId, String revision) {
        requireScope(scope, groupId);
        return store.locked(
                groupId, () -> store.requireUnchanged(groupId, questionId, revision).question());
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
        archive(scope, groupId, questionId, revision, null);
    }

    public void archive(
            DatasetScope scope,
            String groupId,
            String questionId,
            String revision,
            Boolean expectedPublished) {
        requireScope(scope, groupId);
        store.locked(
                groupId,
                () -> {
                    if (activity.running(groupId)) {
                        throw new DatasetException("助手正在修改草稿，请等当前轮次完成后再移出题目", 409);
                    }
                    if (expectedPublished == null) store.archive(groupId, questionId, revision);
                    else store.archive(groupId, questionId, revision, expectedPublished);
                    groups.markMdlDirty(scope.ownerId(), groupId);
                    return null;
                });
    }

    public MdlQuestionStore.Review decide(
            DatasetScope scope, String groupId, String questionId, boolean accepted) {
        return decide(scope, groupId, questionId, accepted, null, null);
    }

    public MdlQuestionStore.Review decide(
            DatasetScope scope,
            String groupId,
            String questionId,
            boolean accepted,
            String revision) {
        return decide(scope, groupId, questionId, accepted, null, revision);
    }

    /** spec114: the question's SQL expanded by the official dry-plan on the draft; not executed. */
    public MdlPublishService.DraftPlan plan(DatasetScope scope, String groupId, String questionId) {
        requireScope(scope, groupId);
        MdlQuestionStore.Question question = store.requireQuestion(groupId, questionId);
        if (question.sql().isBlank()) throw new DatasetException("本题尚未生成 SQL", 409);
        if (publisher == null) throw new DatasetException("发布服务不可用", 503);
        return publisher.planDraft(groupId, List.of(question.sql())).get(0);
    }

    /**
     * spec112: a question the user judged as expected while verifying a semantic asset becomes a
     * confirmed question, through the same checks as a confirmation in the trial panel.
     */
    public MdlQuestionStore.Review confirmVerified(
            DatasetScope scope, String groupId, String question, String definition, String sql) {
        requireScope(scope, groupId);
        String id = store.saveVerifiedQuestion(groupId, question, definition, sql);
        return decide(scope, groupId, id, true, MdlQuestionStore.ORIGIN_ASSET_VERIFY, null);
    }

    /** Called under the asset decision's publication and workspace locks. */
    MdlQuestionStore.Review confirmVerified(
            DatasetScope scope,
            String groupId,
            String question,
            String definition,
            String sql,
            MdlQuestionStore.Replacement replacement) {
        requireScope(scope, groupId);
        requireSqlWindow(scope, groupId, sql, "question confirm");
        requirePlans(groupId, sql, "SQL 检查未通过，不能保存为问答：");
        String id =
                store.saveVerifiedQuestion(groupId, question, definition, sql, replacement, true);
        return recordDecision(scope, groupId, id, true, MdlQuestionStore.ORIGIN_ASSET_VERIFY);
    }

    private MdlQuestionStore.Review recordDecision(
            DatasetScope scope,
            String groupId,
            String questionId,
            boolean accepted,
            String origin) {
        MdlQuestionStore.Review result =
                store.decide(groupId, questionId, accepted, scope.ownerId(), origin);
        groups.markMdlDirty(scope.ownerId(), groupId);
        return result;
    }

    private MdlQuestionStore.Review decide(
            DatasetScope scope,
            String groupId,
            String questionId,
            boolean accepted,
            String origin,
            String revision) {
        requireScope(scope, groupId);
        // A publish waits for this whole decision, so a confirmed question is never left out of
        // the version being published (2026-10-31: it forced a second publish straight after).
        java.util.function.Supplier<MdlQuestionStore.Review> decision =
                () -> {
                    if (accepted) {
                        requireWindow(scope, groupId, questionId, "question confirm");
                        MdlQuestionStore.Question question =
                                revision == null
                                        ? store.requireQuestion(groupId, questionId)
                                        : store.requireUnchanged(groupId, questionId, revision);
                        if (question != null)
                            requirePlans(groupId, question.sql(), "SQL 检查未通过，不能确认：");
                    }
                    return store.locked(
                            groupId,
                            () -> {
                                // The dry-plan can take time; check again under the write lock.
                                if (revision != null)
                                    store.requireUnchanged(groupId, questionId, revision);
                                return recordDecision(scope, groupId, questionId, accepted, origin);
                            });
                };
        return publisher == null ? decision.get() : publisher.beforeNextPublish(groupId, decision);
    }
}
