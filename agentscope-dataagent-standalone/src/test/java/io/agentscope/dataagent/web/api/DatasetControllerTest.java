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
package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.BaselineMdlService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Locks controller-level baseline publication after each dataset mutation workflow. */
class DatasetControllerTest {

    private DatasetService datasetService;
    private BaselineMdlService baselineMdlService;
    private DatasetController controller;
    private final TestingAuthenticationToken alice = new TestingAuthenticationToken("alice", "n/a");

    @BeforeEach
    void setUp() {
        datasetService = mock(DatasetService.class);
        baselineMdlService = mock(BaselineMdlService.class);
        controller = new DatasetController(datasetService, baselineMdlService);
        when(datasetService.readColumns(any())).thenReturn(List.of());
    }

    @Test
    void singleUploadPublishesBaselineOnce() {
        FilePart file = file("orders.csv", "order_id,amount\n1,12.50");
        DatasetEntity entity = dataset("dataset-1", "orders", "group-1");
        when(datasetService.ingest(
                        eq("alice"),
                        eq("group-1"),
                        eq("orders"),
                        eq(null),
                        any(InputStream.class),
                        eq("orders.csv")))
                .thenReturn(entity);

        DatasetController.DatasetVO result =
                controller.upload(file, null, "orders", "group-1", alice).block();

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("orders");
        verify(datasetService)
                .ingest(
                        eq("alice"),
                        eq("group-1"),
                        eq("orders"),
                        eq(null),
                        any(InputStream.class),
                        eq("orders.csv"));
        verify(baselineMdlService).publishAfterDatasetChange("alice", "group-1");
    }

    @Test
    void batchUploadPublishesOnlyAfterAllFilesComplete() {
        FilePart orders = file("orders.csv", "id\n1");
        FilePart customers = file("customers.csv", "id\n2");
        when(datasetService.ingest(
                        eq("alice"), eq("group-1"), any(), eq(null), any(InputStream.class), any()))
                .thenAnswer(
                        invocation -> {
                            String name = invocation.getArgument(2);
                            return dataset("dataset-" + name, name, "group-1");
                        });

        List<DatasetController.DatasetVO> result =
                controller.batchUpload(List.of(orders, customers), "group-1", alice).block();

        assertThat(result)
                .extracting(DatasetController.DatasetVO::name)
                .containsExactlyInAnyOrder("orders", "customers");
        verify(datasetService, times(2))
                .ingest(eq("alice"), eq("group-1"), any(), eq(null), any(InputStream.class), any());
        verify(baselineMdlService, times(1)).publishAfterDatasetChange("alice", "group-1");
    }

    @Test
    void publicationFailureReportsThatSavedDataIsNotQueryable() {
        FilePart file = file("orders.csv", "id\n1");
        when(datasetService.ingest(
                        eq("alice"),
                        eq("group-1"),
                        eq("orders"),
                        eq(null),
                        any(InputStream.class),
                        eq("orders.csv")))
                .thenReturn(dataset("dataset-1", "orders", "group-1"));
        doThrow(new DatasetException("数据已保存，但基础 MDL 初始化失败：构建失败", 500))
                .when(baselineMdlService)
                .publishAfterDatasetChange("alice", "group-1");

        StepVerifier.create(controller.upload(file, null, "orders", "group-1", alice))
                .expectErrorSatisfies(
                        error -> {
                            assertThat(error).isInstanceOf(ResponseStatusException.class);
                            ResponseStatusException status = (ResponseStatusException) error;
                            assertThat(status.getStatusCode())
                                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                            assertThat(status.getReason())
                                    .contains("数据已保存")
                                    .contains("基础 MDL 初始化失败");
                        })
                .verify();

        verify(datasetService)
                .ingest(
                        eq("alice"),
                        eq("group-1"),
                        eq("orders"),
                        eq(null),
                        any(InputStream.class),
                        eq("orders.csv"));
    }

    @Test
    void deleteRepublishesOwningGroupOnce() {
        when(datasetService.get("alice", "dataset-1"))
                .thenReturn(dataset("dataset-1", "orders", "group-1"));

        var response = controller.delete("dataset-1", alice).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(datasetService).delete("alice", "dataset-1");
        verify(baselineMdlService).publishAfterDatasetChange("alice", "group-1");
    }

    private static DatasetEntity dataset(String id, String name, String groupId) {
        DatasetEntity entity = new DatasetEntity();
        entity.setId(id);
        entity.setOwnerId("alice");
        entity.setGroupId(groupId);
        entity.setName(name);
        entity.setSchemaName("retail");
        entity.setTableName("ds_" + name);
        entity.setDescription(name + "业务表");
        entity.setSourceFileName(name + ".csv");
        return entity;
    }

    private static FilePart file(String filename, String content) {
        FilePart file = mock(FilePart.class);
        when(file.filename()).thenReturn(filename);
        when(file.content())
                .thenReturn(
                        Flux.just(
                                DefaultDataBufferFactory.sharedInstance.wrap(
                                        content.getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8))));
        return file;
    }
}
