package io.agentscope.dataagent.dataset;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

/** Authenticates project selection without inspecting or routing business questions. */
@Component
public final class WrenQueryAccess {
    private final DatasetGroupService groups;
    private final ConversationScopeRegistry scopes;

    public WrenQueryAccess(DatasetGroupService groups, ConversationScopeRegistry scopes) {
        this.groups = groups;
        this.scopes = scopes;
    }

    public List<DatasetGroupEntity> visible(RuntimeContext context) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank())
            throw new DatasetException("缺少问数租户上下文", 403);
        DatasetScope scope = context.get(DatasetScope.class);
        String owner = context.getUserId();
        if (scope != null && !owner.equals(scope.ownerId()))
            throw new DatasetException("问数会话租户不匹配", 403);
        List<String> selected =
                scope != null ? scope.groupIds() : scopes.get(owner, context.getSessionId());
        return groups.queryGroups(owner, selected);
    }

    public DatasetGroupEntity resolve(RuntimeContext context, String id) {
        List<DatasetGroupEntity> available = visible(context);
        List<DatasetGroupEntity> matching =
                available.stream()
                        .filter(
                                g ->
                                        id == null
                                                || id.isBlank()
                                                || id.equals(g.getId())
                                                || id.equals(g.getName()))
                        .toList();
        if (matching.size() != 1) throw new DatasetException("请选择当前已授权知识库的唯一名称或 ID", 400);
        DatasetGroupEntity group = matching.get(0);
        if (group.isModelingPending())
            throw new DatasetException("该知识库数据或资料已变更，请完成基础语义建模并发布后再问数。", 409);
        if (group.getMdlVersion() < 1) throw new DatasetException("该知识库尚未发布 Wren 工程", 409);
        return group;
    }
}
