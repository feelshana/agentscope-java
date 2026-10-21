package io.agentscope.dataagent.runtime.model;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Stable model identity for cached agents; the native call context pins each turn. */
public final class SwitchingModel implements Model, MiddlewareBase {
    static final Object CONTEXT_KEY = SwitchingModel.class;
    private final ModelServiceRegistry registry;
    private final Model startup;
    private final boolean startupConfigured;

    public SwitchingModel(ModelServiceRegistry registry, Model startup) {
        this.registry = registry;
        this.startupConfigured = startup != null;
        this.startup =
                startup == null
                        ? new Model() {
                            @Override
                            public Flux<ChatResponse> stream(
                                    List<Msg> messages,
                                    List<ToolSchema> tools,
                                    GenerateOptions options) {
                                return Flux.error(
                                        new IllegalStateException("尚未配置大模型，请由全局管理员配置并应用模型服务。"));
                            }

                            @Override
                            public String getModelName() {
                                return "未配置";
                            }
                        }
                        : startup;
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                        reactorContext -> {
                            var pinned =
                                    reactorContext.hasKey(CONTEXT_KEY)
                                            ? reactorContext.<ModelServiceRegistry.Snapshot>get(
                                                    CONTEXT_KEY)
                                            : registry.capture(startup);
                            return next.apply(input)
                                    .contextWrite(view -> view.put(CONTEXT_KEY, pinned));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.deferContextual(
                        context -> {
                            var pinned =
                                    context.hasKey(CONTEXT_KEY)
                                            ? context.<ModelServiceRegistry.Snapshot>get(
                                                    CONTEXT_KEY)
                                            : registry.capture(startup);
                            // Connection identity cannot be overridden by stale per-agent defaults.
                            var effective =
                                    pinned.connection() == null
                                            ? options
                                            : GenerateOptions.mergeOptions(
                                                    pinned.connection(), options);
                            return pinned.model().stream(messages, tools, effective);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String getModelName() {
        return "全局模型服务";
    }

    public boolean isAvailable() {
        return registry.capture(startup).id() != null || startupConfigured;
    }
}
