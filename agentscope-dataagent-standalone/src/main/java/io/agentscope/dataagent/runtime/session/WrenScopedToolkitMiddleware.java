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
 * Creates an invocation-scoped toolkit holding the official Wren MCP tools of the selected
 * knowledge base, using only the framework's public middleware contract.
 *
 * <p>The query agent and its toolkit serve every user, so the shared toolkit never receives a
 * project-bound MCP client. Instead: the model call's tool list is rewritten here (shared tools
 * plus this invocation's official schemas), and the shared toolkit carries {@link
 * WrenToolForwarder} stand-ins that hand each execution to the scoped toolkit kept in the
 * RuntimeContext.
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
                                context.put(Scoped.class, null);
                                if (scoped.client() != null) scoped.client().close();
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
        if (existing != null) return next.apply(withTools(input, existing));
        long start = System.nanoTime();
        return Mono.fromCallable(
                        () -> java.util.Optional.ofNullable(createScopedToolkit(agent, context)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        optional -> {
                            if (optional.isEmpty()) return next.apply(withTools(input, null));
                            Scoped scoped = optional.get();
                            context.put(Scoped.class, scoped);
                            io.agentscope.dataagent.web.middleware.ExecutionTimingMiddleware.record(
                                    context, "wren.registration", "", start, "SUCCESS");
                            return next.apply(withTools(input, scoped));
                        });
    }

    /**
     * Shared tools without the stand-ins, plus the selected knowledge base's official schemas.
     * With no knowledge base selected the official tools are simply not offered.
     */
    private io.agentscope.core.middleware.ModelCallInput withTools(
            io.agentscope.core.middleware.ModelCallInput input, Scoped scoped) {
        java.util.List<io.agentscope.core.model.ToolSchema> tools = new java.util.ArrayList<>();
        if (input.tools() != null)
            for (var tool : input.tools())
                if (!WrenScopedMcpClient.READ_TOOLS.contains(tool.getName())) tools.add(tool);
        if (scoped != null) tools.addAll(scoped.toolkit().getToolSchemas());
        return new io.agentscope.core.middleware.ModelCallInput(
                input.messages(), tools, input.options(), input.model());
    }

    /** Binds a scoped toolkit without an MCP client; tests of the forwarding path only. */
    static void attach(RuntimeContext context, Toolkit toolkit) {
        context.put(Scoped.class, new Scoped(toolkit, null));
    }

    /** The invocation's scoped toolkit, or null when no knowledge base is bound this turn. */
    public static Toolkit scopedToolkit(RuntimeContext context) {
        Scoped scoped = context == null ? null : context.get(Scoped.class);
        return scoped == null ? null : scoped.toolkit();
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
        // Only the official tools: the shared tools stay on the agent's own toolkit.
        Toolkit toolkit = new Toolkit();
        toolkit.registerMcpClient(client).block();
        return new Scoped(toolkit, client);
    }

    private record Scoped(Toolkit toolkit, WrenScopedMcpClient client) {}
}
