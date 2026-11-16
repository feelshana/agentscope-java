package io.agentscope.dataagent.tools.data;

import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.WrenToolForwarder;
import io.agentscope.harness.agent.tool.FilesystemTool;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Installs native tools after the programmatic dashboard agent has been built. */
@Component
public class DashboardToolkitRegistrar {
    private final DataAgentBootstrap bootstrap;
    private final DashboardService dashboards;

    public DashboardToolkitRegistrar(DataAgentBootstrap bootstrap, DashboardService dashboards) {
        this.bootstrap = bootstrap;
        this.dashboards = dashboards;
    }

    @PostConstruct
    public void register() {
        var agent = bootstrap.agents().get(DashboardService.AGENT_ID);
        if (agent == null) throw new IllegalStateException("仪表盘Agent未装配");
        var toolkit = agent.getDelegate().getToolkit();
        DataToolkitRegistrar.HARNESS_WEB_TOOLS.forEach(toolkit::removeTool);
        toolkit.registerTool(new FilesystemTool(new DashboardFilesystem(dashboards)));
        toolkit.registerTool(new DashboardToolkit(dashboards));
        WrenToolForwarder.registerAll(toolkit);
    }
}
