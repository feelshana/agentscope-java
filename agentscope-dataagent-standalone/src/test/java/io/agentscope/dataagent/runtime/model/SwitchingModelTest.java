package io.agentscope.dataagent.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SwitchingModelTest {
    @Test
    void dashboardOutputBudgetSurvivesPinnedConnectionSettings() {
        var registry = mock(ModelServiceRegistry.class);
        Model model = mock(Model.class);
        var connection =
                io.agentscope.core.model.GenerateOptions.builder()
                        .modelName("configured-model")
                        .apiKey("fixture-key")
                        .stream(true)
                        .build();
        when(registry.capture(any()))
                .thenReturn(new ModelServiceRegistry.Snapshot("a", 1, "first", model, connection));
        var observed =
                new java.util.concurrent.atomic.AtomicReference<
                        io.agentscope.core.model.GenerateOptions>();
        when(model.stream(any(), any(), any()))
                .thenAnswer(
                        call -> {
                            observed.set(call.getArgument(2));
                            return Flux.empty();
                        });
        new SwitchingModel(registry, model)
                .stream(
                                java.util.List.of(),
                                java.util.List.of(),
                                io.agentscope.core.model.GenerateOptions.builder()
                                        .maxTokens(16384)
                                        .build())
                        .blockLast();
        assertThat(observed.get().getMaxTokens()).isEqualTo(16384);
        assertThat(observed.get().getModelName()).isEqualTo("configured-model");
        assertThat(observed.get().getApiKey()).isEqualTo("fixture-key");
    }

    @Test
    void independentSubscriptionsKeepTheParentTurnWhileNewOperationsSeeTheSwitch() {
        var registry = mock(ModelServiceRegistry.class);
        Model startup = mock(Model.class);
        Model first = mock(Model.class);
        Model second = mock(Model.class);
        when(first.getModelName()).thenReturn("first");
        when(second.getModelName()).thenReturn("second");
        var a = new ModelServiceRegistry.Snapshot("a", 1, "first", first, null);
        var b = new ModelServiceRegistry.Snapshot("b", 1, "second", second, null);
        when(registry.capture(any())).thenReturn(a);
        var switching = new SwitchingModel(registry, startup);
        var context = RuntimeContext.builder().userId("user").sessionId("turn").build();

        switching
                .onAgent(
                        null,
                        context,
                        null,
                        ignored -> {
                            when(registry.capture(any())).thenReturn(b);
                            assertThat(SwitchingModel.forCall(switching, context).getModelName())
                                    .isEqualTo("first");
                            assertThat(SwitchingModel.forCall(switching, null).getModelName())
                                    .isEqualTo("second");
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();

        switching
                .onAgent(
                        null,
                        context,
                        null,
                        ignored -> {
                            assertThat(SwitchingModel.forCall(switching, context).getModelName())
                                    .isEqualTo("second");
                            return Flux.<AgentEvent>empty();
                        })
                .blockLast();
    }

    @Test
    void unmanagedModelsAndOtherCallContextsRemainIndependent() {
        Model model = mock(Model.class);
        var context = RuntimeContext.builder().userId("other").sessionId("other").build();
        assertThat(SwitchingModel.forCall(model, context)).isSameAs(model);
        assertThat(SwitchingModel.forCall(null, context)).isNull();
    }
}
