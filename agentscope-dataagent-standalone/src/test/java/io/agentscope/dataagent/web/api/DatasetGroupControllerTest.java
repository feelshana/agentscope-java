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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.BaselineMdlService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.DocEnhanceService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

/** Locks one baseline publication after an all-or-nothing external table association. */
class DatasetGroupControllerTest {

    private DatasetService datasetService;
    private BaselineMdlService baselineMdlService;
    private DatasetGroupController controller;
    private final TestingAuthenticationToken alice = new TestingAuthenticationToken("alice", "n/a");

    @BeforeEach
    void setUp() {
        datasetService = mock(DatasetService.class);
        baselineMdlService = mock(BaselineMdlService.class);
        controller =
                new DatasetGroupController(
                        mock(DatasetGroupService.class),
                        datasetService,
                        mock(DocEnhanceService.class),
                        baselineMdlService);
        when(datasetService.readColumns(any())).thenReturn(List.of());
    }

    @Test
    void associatePublishesBaselineOnceForTheWholeBatch() {
        DatasetGroupController.AssociateRequest request =
                new DatasetGroupController.AssociateRequest(
                        "source-1", "retail", List.of("orders", "customers"), false);
        when(datasetService.associateTables(
                        "alice",
                        "group-1",
                        "source-1",
                        "retail",
                        List.of("orders", "customers"),
                        false))
                .thenReturn(
                        List.of(dataset("dataset-1", "orders"), dataset("dataset-2", "customers")));

        List<DatasetController.DatasetVO> result =
                controller.associate("group-1", request, alice).block();

        assertThat(result)
                .extracting(DatasetController.DatasetVO::name)
                .containsExactly("orders", "customers");
        verify(baselineMdlService).publishAfterDatasetChange("alice", "group-1");
    }

    @Test
    void associationFailureDoesNotAttemptBaselinePublication() {
        DatasetGroupController.AssociateRequest request =
                new DatasetGroupController.AssociateRequest(
                        "source-1", "retail", List.of("orders", "customers"), false);
        when(datasetService.associateTables(
                        "alice",
                        "group-1",
                        "source-1",
                        "retail",
                        List.of("orders", "customers"),
                        false))
                .thenThrow(new DatasetException("外部数据表不存在或没有可读取字段: retail.customers"));

        StepVerifier.create(controller.associate("group-1", request, alice))
                .expectErrorSatisfies(
                        error -> {
                            assertThat(error).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) error).getStatusCode())
                                    .isEqualTo(HttpStatus.BAD_REQUEST);
                        })
                .verify();

        verify(baselineMdlService, never()).publishAfterDatasetChange(any(), any());
    }

    private static DatasetEntity dataset(String id, String name) {
        DatasetEntity entity = new DatasetEntity();
        entity.setId(id);
        entity.setOwnerId("alice");
        entity.setGroupId("group-1");
        entity.setName(name);
        entity.setSchemaName("retail");
        entity.setTableName(name);
        entity.setDescription(name + "业务表");
        entity.setOrigin("datasource");
        entity.setExternalDataSourceId("source-1");
        return entity;
    }
}
