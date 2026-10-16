package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class DatasetSamplingTest {
    @TempDir Path root;
    private final List<DataSourceIntrospector.ColumnInfo> columns =
            List.of(
                    new DataSourceIntrospector.ColumnInfo("status", "VARCHAR", "状态"),
                    new DataSourceIntrospector.ColumnInfo("payload", "BLOB", "附件"));

    @Test
    void boundsRowsAndQueriesOnceWithoutAggregates() throws Exception {
        var introspector = mock(DataSourceIntrospector.class);
        var source = new ExternalDataSourceEntity();
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(introspector.open(source)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        AtomicInteger n = new AtomicInteger();
        when(rows.getString(1)).thenAnswer(inv -> "value-" + n.incrementAndGet());
        var sample = DatasetSampling.read(introspector, source, "retail", "orders", columns, true);
        assertThat(sample.sampledRows()).isEqualTo(1000);
        assertThat(sample.columns().get(0).values()).hasSize(20);
        assertThat(sample.columns().get(0).observedDistinct()).isEqualTo(1000);
        assertThat(sample.columns().get(0).valuesTruncated()).isTrue();
        assertThat(sample.columns().get(1).skipped()).isTrue();
        verify(rows, never()).getString(2);
        verify(rows, times(1000)).next();
        verify(statement).setQueryTimeout(30);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).executeQuery(sql.capture());
        assertThat(sql.getValue())
                .endsWith("LIMIT 1000")
                .doesNotContain("DISTINCT", "COUNT(", "ORDER BY");
        assertThat(DatasetSampling.decode(DatasetSampling.encode(sample))).isEqualTo(sample);
    }

    @Test
    void handlesEmptyNullRepeatedAndLongValues() throws Exception {
        var rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, true, true, true, false);
        when(rows.getString(1)).thenReturn(null, "PAID", "PAID", "x".repeat(257));
        var sample = DatasetSampling.collect(rows, columns);
        assertThat(sample.sampledRows()).isEqualTo(4);
        var col = sample.columns().get(0);
        assertThat(col.nullCount()).isEqualTo(1);
        assertThat(col.observedDistinct()).isEqualTo(2);
        assertThat(col.values()).containsExactly("PAID", "x".repeat(256));
        assertThat(col.valuesTruncated()).isTrue();
        assertThat(DatasetSampling.collect(mock(ResultSet.class), columns).sampledRows()).isZero();
    }

    @Test
    void disabledDoesNotConnectAndFailureDoesNotRetry() throws Exception {
        var introspector = mock(DataSourceIntrospector.class);
        var source = new ExternalDataSourceEntity();
        assertThat(
                        DatasetSampling.read(
                                        introspector, source, "retail", "orders", columns, false)
                                .status())
                .isEqualTo("DISABLED");
        verifyNoInteractions(introspector);
        when(introspector.open(source)).thenThrow(new SQLException("timeout"));
        assertThat(
                        DatasetSampling.read(
                                        introspector, source, "retail", "orders", columns, true)
                                .status())
                .isEqualTo("FAILED");
        verify(introspector, times(1)).open(source);
        verify(introspector, never()).countRows(any(), any(), any());
    }

    @Test
    void writesBoundedOfficialDocumentsAndRemovesDeletedDatasets() throws Exception {
        DatasetEntity entity = new DatasetEntity();
        entity.setId("dataset-1");
        entity.setTableName("orders");
        entity.setSampleSummaryJson(
                DatasetSampling.encode(
                        new DatasetSampling.Summary(
                                1,
                                "SUCCESS",
                                "2026-10-15",
                                1000,
                                1000,
                                "有限样本",
                                List.of(
                                        new DatasetSampling.ColumnSample(
                                                "status",
                                                List.of("PAID", "x".repeat(30000)),
                                                0,
                                                2,
                                                true,
                                                false)))));
        assertThat(DatasetSampleDocuments.sync(root, List.of(entity))).isTrue();
        assertThat(DatasetSampleDocuments.sync(root, List.of(entity))).isFalse();
        assertThat(DatasetSampleDocuments.context(root))
                .hasSizeLessThanOrEqualTo(6000)
                .contains("摘要截断");
        assertThat(Files.readString(root.resolve("knowledge/rules/data-sampling.md")))
                .contains("不能用样本代替", "PAID");
        DatasetSampleDocuments.sync(root, List.of());
        assertThat(Files.readString(root.resolve("raw/data-samples.md")))
                .doesNotContain("dataset-1", "PAID");
    }
}
