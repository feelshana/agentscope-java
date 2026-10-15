/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.parser.SchemaGenerationService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DatasetImportLimitsTest {
    private TableProvisioner tables;
    private SchemaGenerationService schema;
    private DatasetImportService service;
    private DatasetUploadProperties limits;

    @BeforeEach
    void setup() {
        tables = mock(TableProvisioner.class);
        schema = mock(SchemaGenerationService.class);
        service = new DatasetImportService(tables, schema);
        limits = new DatasetUploadProperties();
        ReflectionTestUtils.setField(service, "uploadProperties", limits);
        when(schema.generateSchema(anyList(), anyMap()))
                .thenReturn(
                        new SchemaGenerationService.SchemaResult(
                                List.of(new SchemaGenerationService.ColumnInfo("id", "编号")), "测试"));
    }

    private void csv(String content) {
        service.importFile(
                "u",
                "fresh-id",
                "sample",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "sample.csv");
    }

    @Test
    void rowLimitRejectsWithoutTruncating() {
        limits.setMaxRows(2);
        assertThatThrownBy(() -> csv("id\n1\n2\n3\n"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("行数");
        verify(tables, never()).createTable(any(), any());
    }

    @Test
    void columnLimitIncludesSparseHeaders() {
        limits.setMaxColumns(2);
        assertThatThrownBy(() -> csv("a,b,c\n1,2,3\n"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("列数");
    }

    @Test
    void cellLimitIsCombinedWithRows() {
        limits.setMaxCells(3);
        assertThatThrownBy(() -> csv("a,b\n1,2\n3,4\n"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("单元格");
    }

    @Test
    void exactRowBoundarySucceeds() {
        limits.setMaxRows(2);
        csv("id\n1\n2\n");
        verify(tables).bulkInsert(any(), any(), argThat(rows -> rows.size() == 2));
    }

    @Test
    void headerOnlyFileNeverCreatesTable() {
        assertThatThrownBy(() -> csv("id\n"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("No data rows");
        verify(tables, never()).createTable(any(), any());
    }

    @Test
    void limitAfterFirstCommitDropsOnlyFreshTable() {
        limits.setMaxRows(5001);
        StringBuilder data = new StringBuilder("id\n");
        for (int i = 0; i < 6000; i++) data.append(i).append('\n');
        assertThatThrownBy(() -> csv(data.toString()))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("行数");
        verify(tables).bulkInsert(any(), any(), any());
        verify(tables).dropTableStrict(TableProvisioner.buildTableName("u", "fresh-id", "sample"));
    }

    @Test
    void cleanupFailureStaysVisible() {
        doThrow(new DatasetException("write failed")).when(tables).bulkInsert(any(), any(), any());
        doThrow(new DatasetException("drop failed")).when(tables).dropTableStrict(any());
        assertThatThrownBy(() -> csv("id\n1\n"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("待清理");
    }

    @Test
    void asyncFailureIsDrainedBeforeDrop() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(
                        i -> {
                            int n = calls.incrementAndGet();
                            if (n > 1) {
                                active.incrementAndGet();
                                try {
                                    if (n == 2) throw new DatasetException("async failed");
                                    Thread.sleep(30);
                                } finally {
                                    active.decrementAndGet();
                                }
                            }
                            return null;
                        })
                .when(tables)
                .bulkInsert(any(), any(), any());
        doAnswer(
                        i -> {
                            assertThat(active.get()).isZero();
                            return null;
                        })
                .when(tables)
                .dropTableStrict(any());
        StringBuilder data = new StringBuilder("id\n");
        for (int i = 0; i < 25000; i++) data.append(i).append('\n');
        assertThatThrownBy(() -> csv(data.toString())).isInstanceOf(DatasetException.class);
        verify(tables).dropTableStrict(any());
    }

    @Test
    void unfinishedBatchWindowIsBounded() throws Exception {
        var latch = new CountDownLatch(1);
        var started = new CountDownLatch(4);
        var count = new AtomicInteger();
        doAnswer(
                        i -> {
                            if (count.incrementAndGet() > 1) {
                                started.countDown();
                                latch.await(5, TimeUnit.SECONDS);
                            }
                            return null;
                        })
                .when(tables)
                .bulkInsert(any(), any(), any());
        var listener = new DatasetImportService.BatchListener("fresh", tables, schema);
        listener.invokeHeadMap(Map.of(0, "id"), null);
        var producer =
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> {
                            for (int i = 0; i < 35000; i++)
                                listener.invoke(Map.of(0, Integer.toString(i)), null);
                            listener.doAfterAllAnalysed(null);
                            listener.shutdownExecutor();
                        });
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            assertThat(count.get()).isEqualTo(5);
            assertThat(producer.isDone()).isFalse();
        } finally {
            latch.countDown();
        }
        producer.get(10, TimeUnit.SECONDS);
    }
}
