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
