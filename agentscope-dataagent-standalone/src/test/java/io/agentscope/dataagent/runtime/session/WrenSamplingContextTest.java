package io.agentscope.dataagent.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DatasetSampleDocuments;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WrenSamplingContextTest {
    @TempDir Path root;

    @Test
    void contextIsBoundedAndResolvedAgainForEachAuthorizedProject() throws Exception {
        Path first = root.resolve("first"), second = root.resolve("second");
        Files.createDirectories(first.resolve("raw"));
        Files.createDirectories(second.resolve("raw"));
        Files.writeString(
                first.resolve("raw/data-samples.md"), "first-secret-" + "x".repeat(10000));
        Files.writeString(second.resolve("raw/data-samples.md"), "second-sample");
        var access = mock(WrenProjectAccess.class);
        when(access.resolve((io.agentscope.core.agent.RuntimeContext) null))
                .thenReturn(new WrenProjectAccess.Project("alice", "first", "first", first))
                .thenReturn(new WrenProjectAccess.Project("alice", "second", "second", second));
        var middleware = new WrenProjectContextMiddleware(access);
        String one = middleware.onSystemPrompt(null, null, "base").block();
        String two = middleware.onSystemPrompt(null, null, "base").block();
        assertThat(one).contains("first-secret", "摘要截断").hasSizeLessThan(8000);
        assertThat(two)
                .contains("second-sample", DatasetSampleDocuments.POLICY)
                .doesNotContain("first-secret");
    }
}
