package io.agentscope.dataagent.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class WrenScopedToolkitMiddlewareTest {
    @Test
    void emptySelectionStillRunsOrdinaryChatWithoutRegisteringWrenTools() {
        var access = mock(WrenQueryAccess.class);
        var gateway = mock(WrenQueryGateway.class);
        var middleware =
                new WrenScopedToolkitMiddleware(
                        access, gateway, mock(WrenPublishedProject.class), mock(MdlCatalog.class));
        var context =
                RuntimeContext.builder()
                        .put(DatasetScope.class, new DatasetScope("alice", List.of()))
                        .build();
        var invoked = new AtomicBoolean();
        middleware
                .onAgent(
                        mock(Agent.class),
                        context,
                        null,
                        input -> {
                            invoked.set(true);
                            return Flux.empty();
                        })
                .blockLast();
        assertThat(invoked).isTrue();
        assertThat(WrenScopedToolkitMiddleware.scopedToolkit(context)).isNull();
        verifyNoInteractions(access, gateway);
    }

    private static String text(io.agentscope.core.message.ToolResultBlock block) {
        StringBuilder out = new StringBuilder();
        for (var c : block.getOutput())
            out.append(
                    c instanceof io.agentscope.core.message.TextBlock t
                            ? t.getText()
                            : String.valueOf(c));
        return out.toString();
    }

    /** A fake official tool standing in for the scoped MCP tool. */
    private static io.agentscope.core.tool.AgentTool official(String name, String answer) {
        return new io.agentscope.core.tool.AgentTool() {
            public String getName() {
                return name;
            }

            public String getDescription() {
                return "official";
            }

            public java.util.Map<String, Object> getParameters() {
                return java.util.Map.of("type", "object");
            }

            public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock>
                    callAsync(io.agentscope.core.tool.ToolCallParam param) {
                return reactor.core.publisher.Mono.just(
                        io.agentscope.core.message.ToolResultBlock.text(answer));
            }
        };
    }

    private static io.agentscope.core.tool.ToolCallParam call(RuntimeContext context, String tool) {
        return io.agentscope.core.tool.ToolCallParam.builder()
                .toolUseBlock(
                        io.agentscope.core.message.ToolUseBlock.builder()
                                .id("t1")
                                .name(tool)
                                .input(java.util.Map.of("sql", "SELECT 1"))
                                .build())
                .runtimeContext(context)
                .build();
    }

    @Test
    void standInsOnTheSharedToolkitForwardOnlyToThisInvocationsScopedTools() {
        // Without the framework patch the agent executes tools from its shared toolkit, so the
        // stand-ins there must reach the invocation-scoped official tool.
        var shared = new Toolkit();
        WrenToolForwarder.registerAll(shared);
        assertThat(shared.getToolNames()).contains("run_sql", "recall_queries");

        var unbound = RuntimeContext.builder().build();
        var refused = shared.getTool("run_sql").callAsync(call(unbound, "run_sql")).block();
        assertThat(text(refused)).contains("请先在问数页面选择一个已发布的知识库");

        var alice = RuntimeContext.builder().build();
        var scoped = new Toolkit();
        scoped.registerAgentTool(official("run_sql", "alice 的结果"));
        WrenScopedToolkitMiddleware.attach(alice, scoped);
        var bob = RuntimeContext.builder().build();
        var bobs = new Toolkit();
        bobs.registerAgentTool(official("run_sql", "bob 的结果"));
        WrenScopedToolkitMiddleware.attach(bob, bobs);
        assertThat(text(shared.getTool("run_sql").callAsync(call(alice, "run_sql")).block()))
                .contains("alice 的结果")
                .doesNotContain("bob");
        assertThat(text(shared.getTool("run_sql").callAsync(call(bob, "run_sql")).block()))
                .contains("bob 的结果");
    }

    @Test
    void modelSeesOfficialSchemasOnlyWhenAKnowledgeBaseIsBound() {
        var middleware =
                new WrenScopedToolkitMiddleware(
                        mock(WrenQueryAccess.class),
                        mock(WrenQueryGateway.class),
                        mock(WrenPublishedProject.class),
                        mock(MdlCatalog.class));
        var shared = new Toolkit();
        WrenToolForwarder.registerAll(shared);
        shared.registerAgentTool(official("render_chart", "chart"));
        var input =
                new io.agentscope.core.middleware.ModelCallInput(
                        List.of(), shared.getToolSchemas(), null, null);
        var seen = new java.util.ArrayList<List<String>>();
        java.util.function.Function<
                        io.agentscope.core.middleware.ModelCallInput,
                        Flux<io.agentscope.core.event.AgentEvent>>
                next =
                        in -> {
                            seen.add(
                                    in.tools().stream()
                                            .map(io.agentscope.core.model.ToolSchema::getName)
                                            .toList());
                            return Flux.empty();
                        };
        var none =
                RuntimeContext.builder()
                        .put(DatasetScope.class, new DatasetScope("alice", List.of()))
                        .build();
        middleware.onModelCall(mock(Agent.class), none, input, next).blockLast();
        assertThat(seen.get(0)).containsExactly("render_chart");

        var bound = RuntimeContext.builder().build();
        var scoped = new Toolkit();
        scoped.registerAgentTool(official("run_sql", "x"));
        WrenScopedToolkitMiddleware.attach(bound, scoped);
        middleware.onModelCall(mock(Agent.class), bound, input, next).blockLast();
        assertThat(seen.get(1)).containsExactlyInAnyOrder("render_chart", "run_sql");
    }
}
