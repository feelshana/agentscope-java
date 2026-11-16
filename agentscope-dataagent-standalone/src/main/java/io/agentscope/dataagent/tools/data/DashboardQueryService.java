package io.agentscope.dataagent.tools.data;

import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.FactQueryWindowPolicy;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Live preview bridge to the same official MCP and SQL gates used by question answering. */
@Service
public class DashboardQueryService {
    private final DashboardService dashboards;
    private final WrenQueryAccess access;
    private final WrenQueryGateway gateway;
    private final FactQueryWindowPolicy windows;

    public DashboardQueryService(
            DashboardService dashboards,
            WrenQueryAccess access,
            WrenQueryGateway gateway,
            FactQueryWindowPolicy windows) {
        this.dashboards = dashboards;
        this.access = access;
        this.gateway = gateway;
        this.windows = windows;
    }

    public String query(String user, String id, boolean draft, String revision, String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 20000)
            throw new DatasetException("查询SQL无效或过长", 400);
        String group = dashboards.queryGroup(user, id, draft, revision);
        var context = DashboardService.context(user, group);
        OfficialWrenToolkit.requireReadOnly(sql);
        access.resolve(context, group);
        windows.requireAllowed(context, group, sql, "run_sql");
        var result =
                gateway.callAuthorized(
                        group,
                        "run_sql",
                        Map.of("sql", sql, "limit", 1000),
                        () -> {
                            dashboards.queryGroup(user, id, draft, revision);
                            access.resolve(context, group);
                        });
        if (!result.ok()) throw new DatasetException("看板查询失败：" + result.payload(), 422);
        return result.payload();
    }
}
