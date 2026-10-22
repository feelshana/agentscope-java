package io.agentscope.dataagent.runtime.session;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.tools.data.WrenScopedMcpClient;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * A knowledge-base-free stand-in for one official Wren MCP tool on the shared query agent
 * toolkit. The shared toolkit serves every user, so it never holds a project-bound client; the
 * framework executes tools from that toolkit, and this stand-in hands each call to the same-named
 * tool in the invocation's scoped toolkit created by {@link WrenScopedToolkitMiddleware}. The
 * official tool still validates arguments, authorization and the time window.
 *
 * <p>The model never sees these stand-ins: the middleware replaces them with the official
 * schemas of the selected knowledge base, or drops them when none is selected.
 */
public final class WrenToolForwarder implements AgentTool {
    /** Accepts any arguments; the official tool behind it owns the real schema. */
    private static final Map<String, Object> ANY_ARGUMENTS =
            Map.of("type", "object", "additionalProperties", true);

    private final String name;

    public WrenToolForwarder(String name) {
        this.name = Objects.requireNonNull(name);
    }

    /** Registers one stand-in per read-only official tool, skipping names already present. */
    public static void registerAll(Toolkit toolkit) {
        for (String tool : WrenScopedMcpClient.READ_TOOLS)
            if (toolkit.getTool(tool) == null)
                toolkit.registerAgentTool(new WrenToolForwarder(tool));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return "官方 Wren 工具（选择知识库后提供）";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ANY_ARGUMENTS;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Toolkit scoped = WrenScopedToolkitMiddleware.scopedToolkit(param.getRuntimeContext());
        AgentTool official = scoped == null ? null : scoped.getTool(name);
        if (official == null || official instanceof WrenToolForwarder)
            return Mono.just(ToolResultBlock.error("请先在问数页面选择一个已发布的知识库，再使用 " + name));
        return official.callAsync(param);
    }
}
