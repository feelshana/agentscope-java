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
        assertThat(context.get(Toolkit.class)).isNull();
        verifyNoInteractions(access, gateway);
    }
}
