package io.agentscope.dataagent.runtime.wren;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.WrenProfileHome;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.modelcontextprotocol.json.McpJsonMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WrenMcpClassLoaderTest {
    @TempDir Path tmp;

    @Test
    void initializesWithApplicationLoaderAndRestoresRestrictedWorkerOnFailure() {
        var props = mock(WrenProperties.class);
        var home = mock(WrenProfileHome.class);
        when(props.maxInstances()).thenReturn(1);
        when(home.ensure()).thenReturn(tmp);
        when(props.resolveExecutable())
                .thenAnswer(
                        call -> {
                            assertThat(Thread.currentThread().getContextClassLoader())
                                    .isSameAs(WrenInstanceRegistry.class.getClassLoader());
                            assertThat(McpJsonMapper.createDefault()).isNotNull();
                            throw new IllegalStateException("fixture: no process should start");
                        });
        var registry = new WrenInstanceRegistry(props, home);
        Thread caller = Thread.currentThread();
        ClassLoader original = caller.getContextClassLoader();
        ClassLoader restricted = new ClassLoader(ClassLoader.getPlatformClassLoader()) {};
        try {
            caller.setContextClassLoader(restricted);
            assertThatThrownBy(() -> registry.createClient("fixture", tmp, "fixture"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("fixture: no process should start");
            assertThat(caller.getContextClassLoader()).isSameAs(restricted);
        } finally {
            caller.setContextClassLoader(original);
            registry.close();
        }
    }

    @Test
    void diagnosticReproducesMapperDiscoveryFailureUnderRestrictedLoader() {
        Thread caller = Thread.currentThread();
        ClassLoader original = caller.getContextClassLoader();
        try {
            caller.setContextClassLoader(new ClassLoader(ClassLoader.getPlatformClassLoader()) {});
            // Use uncached discovery so earlier MCP tests cannot hide the loading defect.
            assertThatThrownBy(McpJsonMapper::createDefault)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("No default McpJsonMapper implementation found");
        } finally {
            caller.setContextClassLoader(original);
        }
    }
}
