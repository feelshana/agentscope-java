package io.agentscope.dataagent.runtime.session;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.FactQueryWindowPolicy;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.tools.data.WrenScopedMcpClient;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Creates an invocation-scoped AgentScope Toolkit containing the official Wren MCP tools.
 *
 * <p>The base agent toolkit is shared by concurrent sessions and therefore never receives a
 * project-bound MCP client. The scoped toolkit is copied from that base and attached to the
 * RuntimeContext; the AgentScope core resolves it for both model schema generation and tool
 * execution.
 */
public final class WrenScopedToolkitMiddleware implements HarnessRuntimeMiddleware {
    private final WrenQueryAccess access;
    private final WrenQueryGateway gateway;
    private final WrenPublishedProject projects;
    private final MdlCatalog mdlCatalog;
    private final FactQueryWindowPolicy windowPolicy;

    public WrenScopedToolkitMiddleware(
            WrenQueryAccess access,
            WrenQueryGateway gateway,
            WrenPublishedProject projects,
            MdlCatalog mdlCatalog,
            FactQueryWindowPolicy windowPolicy) {
        this.access = access;
        this.gateway = gateway;
        this.projects = projects;
        this.mdlCatalog = mdlCatalog;
        this.windowPolicy = windowPolicy;
    }

    public WrenScopedToolkitMiddleware(
            WrenQueryAccess access,
            WrenQueryGateway gateway,
            WrenPublishedProject projects,
            MdlCatalog mdlCatalog) {
        this(access, gateway, projects, mdlCatalog, null);
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public Flux<io.agentscope.core.event.AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            java.util.function.Function<AgentInput, Flux<io.agentscope.core.event.AgentEvent>>
                    next) {
        return next.apply(input)
                .doFinally(
                        signal -> {
                            Scoped scoped = context.get(Scoped.class);
                            if (scoped != null) {
                                context.put(Toolkit.class, null);
                                context.put(Scoped.class, null);
                                scoped.client().close();
                            }
                        });
    }

    @Override
    public Flux<io.agentscope.core.event.AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            io.agentscope.core.middleware.ModelCallInput input,
            java.util.function.Function<
                            io.agentscope.core.middleware.ModelCallInput,
                            Flux<io.agentscope.core.event.AgentEvent>>
                    next) {
        Scoped existing = context.get(Scoped.class);
        if (existing != null) return next.apply(withTools(context, input, existing));
        long start = System.nanoTime();
        return Mono.fromCallable(
                        () -> java.util.Optional.ofNullable(createScopedToolkit(agent, context)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        optional -> {
                            if (optional.isEmpty()) return next.apply(input);
                            Scoped scoped = optional.get();
                            context.put(Toolkit.class, scoped.toolkit());
                            context.put(Scoped.class, scoped);
                            io.agentscope.dataagent.web.middleware.ExecutionTimingMiddleware.record(
                                    context, "wren.registration", "", start, "SUCCESS");
                            return next.apply(withTools(context, input, scoped));
                        });
    }

    private io.agentscope.core.middleware.ModelCallInput withTools(
            RuntimeContext context,
            io.agentscope.core.middleware.ModelCallInput input,
            Scoped scoped) {
        return new io.agentscope.core.middleware.ModelCallInput(
                input.messages(),
                scoped.toolkit()
                        .getToolSchemas(
                                context.getAgentState() == null
                                        ? java.util.Set.of()
                                        : context.getAgentState()
                                                .getToolContext()
                                                .getActivatedGroups()),
                input.options(),
                input.model());
    }

    private Scoped createScopedToolkit(Agent agent, RuntimeContext context) {
        DatasetScope scope = context != null ? context.get(DatasetScope.class) : null;
        if (scope == null || !scope.hasGroupFilter() || scope.groupIds().isEmpty()) return null;
        if (scope.groupIds().size() != 1) {
            throw new IllegalArgumentException("原生 Wren 工具一次只能绑定一个知识库，请先选择一个知识库");
        }
        String groupId = scope.groupIds().get(0);
        access.resolve(context, groupId);
        WrenScopedMcpClient client =
                new WrenScopedMcpClient(
                        context, groupId, access, gateway, projects, mdlCatalog, windowPolicy);
        Toolkit toolkit = agent.getToolkit().copy();
        toolkit.registerMcpClient(client).block();
        return new Scoped(toolkit, client);
    }

    private record Scoped(Toolkit toolkit, WrenScopedMcpClient client) {}
}
