package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.artifact.ArtifactStore;
import io.agentscope.dataagent.web.persistence.jpa.ArtifactEntity;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QueryDataFilesTest {
    private final AbstractSandboxFilesystem filesystem = mock(AbstractSandboxFilesystem.class);
    private final ArtifactStore store = mock(ArtifactStore.class);
    private final RuntimeContext context =
            RuntimeContext.builder().userId("alice").sessionId("session-1").build();

    @Test
    void ordinaryQueriesPersistExactCsvAndExposeDownloadWithoutStartingDocker() throws Exception {
        var file = new ArtifactEntity();
        var bytes = ArgumentCaptor.forClass(byte[].class);
        when(store.save(
                        eq(context),
                        anyString(),
                        anyString(),
                        anyString(),
                        bytes.capture(),
                        eq(true)))
                .thenReturn(file);
        when(store.reference(file)).thenReturn("/api/artifacts/test-id");
        String note =
                new QueryDataFiles(filesystem, store)
                        .note(
                                context,
                                "{\"columns\":[\"部门\",\"次数\"],\"rows\":[{\"部门\":\"市场,经营部\",\"次数\":3},{\"部门\":null,\"次数\":0}]}");
        assertThat(note).contains("数据文件", "2 行 × 2 列", "[下载查询数据](/api/artifacts/test-id)");
        assertThat(new String(bytes.getValue(), StandardCharsets.UTF_8))
                .isEqualTo("部门,次数\n\"市场,经营部\",3\n,0\n");
        verifyNoInteractions(filesystem);
    }

    @Test
    void csvPreservesQuotesNewlinesLeadingZeroIdentifiersAndFalsyValues() throws Exception {
        var file = new ArtifactEntity();
        var bytes = ArgumentCaptor.forClass(byte[].class);
        when(store.save(
                        eq(context),
                        anyString(),
                        anyString(),
                        anyString(),
                        bytes.capture(),
                        eq(true)))
                .thenReturn(file);
        when(store.reference(file)).thenReturn("/api/artifacts/test-id");
        String payload =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                                java.util.Map.of(
                                        "columns",
                                        java.util.List.of("值"),
                                        "rows",
                                        java.util.List.of(
                                                java.util.List.of("a\"b\nc"),
                                                        java.util.List.of("001"),
                                                java.util.List.of(false), java.util.List.of(0))));
        assertThat(new QueryDataFiles(filesystem, store).note(context, payload))
                .contains("4 行 × 1 列");
        assertThat(new String(bytes.getValue(), StandardCharsets.UTF_8))
                .isEqualTo("值\n\"a\"\"b\nc\"\n001\nfalse\n0\n");
        verifyNoInteractions(filesystem);
    }

    @Test
    void oversizedResultsNeverWriteAFileOrStartDocker() {
        String payload =
                "{\"columns\":[\"value\"],\"rows\":[[\""
                        + "x".repeat(QueryDataFiles.MAX_DATA_FILE_BYTES)
                        + "\"]]}";
        assertThat(new QueryDataFiles(filesystem, store).note(context, payload)).contains("结果过大");
        verifyNoInteractions(filesystem, store);
    }

    @Test
    void storageFailureDoesNotTurnASuccessfulQueryIntoAnExecutionError() throws Exception {
        when(store.save(any(), anyString(), anyString(), anyString(), any(), eq(true)))
                .thenThrow(new IOException("test storage unavailable"));
        assertThat(
                        new QueryDataFiles(filesystem, store)
                                .note(context, "{\"columns\":[\"n\"],\"rows\":[[1]]}"))
                .contains("查询成功，但数据文件保存失败")
                .doesNotStartWith("error:");
        verifyNoInteractions(filesystem);
    }
}
