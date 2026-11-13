package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceGrantRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import org.springframework.stereotype.Service;

/** Source membership governs new bindings; committed KB bindings have independent authorization. */
@Service
public class ExternalDataSourceAccess {
    public enum Role {
        USE,
        MANAGE
    }

    private final ExternalDataSourceRepository sources;
    private final ExternalDataSourceGrantRepository grants;
    private final DatasetRepository datasets;
    private final UserStore users;
    private final DemoDataService demo;

    public ExternalDataSourceAccess(
            ExternalDataSourceRepository sources,
            ExternalDataSourceGrantRepository grants,
            DatasetRepository datasets,
            UserStore users,
            DemoDataService demo) {
        this.sources = sources;
        this.grants = grants;
        this.datasets = datasets;
        this.users = users;
        this.demo = demo;
    }

    public boolean isAdmin(String actor) {
        return actor != null && users.findById(actor).map(u -> u.hasRole("admin")).orElse(false);
    }

    public void requireUser(String actor) {
        if (actor == null || users.findById(actor).isEmpty())
            throw new DatasetException("请重新登录后操作", 401);
    }

    public boolean canGovern(String actor, ExternalDataSourceEntity source) {
        return source.getOwnerId().equals(actor) || isAdmin(actor);
    }

    public Role role(String actor, ExternalDataSourceEntity source) {
        requireUser(actor);
        if (canGovern(actor, source)) return Role.MANAGE;
        if (DemoDataService.isBuiltin(source))
            return DemoDataService.isCurrentBuiltin(source) && demo.sourceAvailable()
                    ? Role.USE
                    : null;
        return grants.findByDatasourceIdAndUserId(source.getId(), actor)
                .map(g -> Role.valueOf(g.getRole()))
                .orElse(null);
    }

    public ExternalDataSourceEntity source(String id) {
        return sources.findById(id).orElseThrow(() -> new DatasetException("数据源不存在或未授权", 404));
    }

    public ExternalDataSourceEntity requireUse(String actor, String id) {
        return requireUse(actor, source(id));
    }

    public ExternalDataSourceEntity requireUse(String actor, ExternalDataSourceEntity source) {
        if (role(actor, source) == null) throw new DatasetException("数据源不存在或未授权", 404);
        requireActive(source);
        return source;
    }

    public ExternalDataSourceEntity requireManage(String actor, String id) {
        var source = source(id);
        var role = role(actor, source);
        if (role == null) throw new DatasetException("数据源不存在或未授权", 404);
        if (role != Role.MANAGE) throw new DatasetException("需要数据源管理权限", 403);
        mutable(source);
        return source;
    }

    public ExternalDataSourceEntity requireGovern(String actor, String id) {
        var source = source(id);
        requireUser(actor);
        if (!canGovern(actor, source)) throw new DatasetException("仅创建人或全局管理员可管理数据源成员、停用和删除", 403);
        mutable(source);
        return source;
    }

    public ExternalDataSourceEntity lock(String id) {
        return sources.lockById(id).orElseThrow(() -> new DatasetException("数据源不存在或未授权", 404));
    }

    /** Must follow the source row lock; current reads recheck grants after a queued revocation. */
    public ExternalDataSourceEntity requireUseLocked(
            String actor, ExternalDataSourceEntity source) {
        requireUser(actor);
        if (!canGovern(actor, source)
                && !(DemoDataService.isCurrentBuiltin(source) && demo.sourceAvailable())
                && grants.findCurrentGrant(source.getId(), actor).isEmpty()) {
            throw new DatasetException("数据源不存在或未授权", 404);
        }
        requireActive(source);
        return source;
    }

    public void requireBindingsActive(String groupId) {
        for (String id : datasets.findExternalSourceIdsByGroup(groupId)) requireActive(source(id));
    }

    public static void requireActive(ExternalDataSourceEntity source) {
        if (!source.isEnabled())
            throw new DatasetException("数据源已停用，暂时无法读取外部数据。请联系数据源创建人或全局管理员启用后重试。", 409);
    }

    private static void mutable(ExternalDataSourceEntity source) {
        if (DemoDataService.isBuiltin(source))
            throw new DatasetException("内置演示数据源不支持修改、授权或删除", 403);
    }
}
