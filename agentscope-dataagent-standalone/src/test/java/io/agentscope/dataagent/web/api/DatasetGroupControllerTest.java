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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.BaselineMdlService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.DocEnhanceService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Locks one baseline publication after an all-or-nothing external table association. */
class DatasetGroupControllerTest {

    private DatasetService datasetService;
    private BaselineMdlService baselineMdlService;
    private DatasetGroupService groupService;
    private DocEnhanceService docEnhanceService;
    private DatasetGroupController controller;
    private final TestingAuthenticationToken alice = new TestingAuthenticationToken("alice", "n/a");

    @BeforeEach
    void setUp() {
        datasetService = mock(DatasetService.class);
        baselineMdlService = mock(BaselineMdlService.class);
        groupService = mock(DatasetGroupService.class);
        docEnhanceService = mock(DocEnhanceService.class);
        controller =
                new DatasetGroupController(
                        groupService,
                        datasetService,
                        docEnhanceService,
                        baselineMdlService,
                        mock(
                                io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository
                                        .class));
        when(datasetService.readColumns(any())).thenReturn(List.of());
    }

    @Test
    void queryAndManageUseTheSameDatasetProjectionWithoutExposingDraftKnowledge() {
        var group =
                new io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity(
                        "group-1", "owner", "销售", "说明");
        group.setMdlVersion(3);
        var datasets = List.of(dataset("dataset-1", "orders"));
        when(groupService.queryGroup("alice", "group-1")).thenReturn(group);
        when(datasetService.listReadableForGroup("alice", "group-1")).thenReturn(datasets);
        when(datasetService.knowledgeText("group-1")).thenReturn("unpublished business draft");
        when(groupService.roles("alice", List.of(group)))
                .thenReturn(
                        Map.of(
                                "group-1",
                                io.agentscope.dataagent.dataset.KnowledgeBaseAccessService.Role
                                        .QUERY));

        var reader = controller.detail("group-1", alice).block();
        assertThat(reader.group().canManage()).isFalse();
        assertThat(reader.group().canQuery()).isTrue();
        assertThat(reader.publishedVersion()).isEqualTo(3);
        assertThat(reader.knowledge()).isEmpty();
        verify(datasetService, never()).knowledgeText("group-1");

        when(groupService.roles("alice", List.of(group)))
                .thenReturn(
                        Map.of(
                                "group-1",
                                io.agentscope.dataagent.dataset.KnowledgeBaseAccessService.Role
                                        .MANAGE));
        var manager = controller.detail("group-1", alice).block();
        assertThat(manager.group().canManage()).isTrue();
        assertThat(manager.datasets()).isEqualTo(reader.datasets());
        assertThat(manager.knowledge()).isEqualTo("unpublished business draft");
        verify(groupService, never()).getGroup(any(), any());
    }

    @Test
    void unauthorizedDetailDoesNotReadDatasetMetadata() {
        when(groupService.queryGroup("alice", "other"))
                .thenThrow(new DatasetException("知识库不存在或未授权", 404));
        StepVerifier.create(controller.detail("other", alice))
                .expectError(ResponseStatusException.class)
                .verify();
        verifyNoInteractions(datasetService);
    }

    @Test
    void documentUploadSavesSourceWithoutStartingLegacyAnalysis() {
        var result = controller.uploadKnowledge("group-1", document(), alice).block();

        assertThat(result).containsEntry("content", "业务文档");
        verify(groupService).getGroup("alice", "group-1");
        verify(datasetService).saveKnowledge("group-1", "业务文档");
        verifyNoInteractions(docEnhanceService);
    }

    @Test
    void anotherOwnersDocumentCannotBeSavedOrAnalyzed() {
        when(groupService.getGroup("alice", "bobs-group"))
                .thenThrow(new DatasetException("知识库不存在", 404));

        StepVerifier.create(controller.uploadKnowledge("bobs-group", document(), alice))
                .expectError(ResponseStatusException.class)
                .verify();

        verify(datasetService, never()).saveKnowledge(any(), any());
        verifyNoInteractions(docEnhanceService);
    }

    private static FilePart document() {
        FilePart file = mock(FilePart.class);
        when(file.filename()).thenReturn("business.md");
        when(file.content())
                .thenReturn(
                        Flux.just(
                                DefaultDataBufferFactory.sharedInstance.wrap(
                                        "业务文档".getBytes(StandardCharsets.UTF_8))));
        return file;
    }

    @Test
    void associatePublishesBaselineOnceForTheWholeBatch() {
        DatasetGroupController.AssociateRequest request =
                new DatasetGroupController.AssociateRequest(
                        "source-1", "retail", List.of("orders", "customers"), false, null);
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
    void tablesCanBeAssociatedOneByOneAndPublishedOnceAtTheEnd() {
        // Per-table requests show sampling progress; only the final empty request publishes.
        when(datasetService.associateTables(
                        "alice", "group-1", "source-1", "retail", List.of("orders"), true))
                .thenReturn(List.of(dataset("dataset-1", "orders")));
        controller
                .associate(
                        "group-1",
                        new DatasetGroupController.AssociateRequest(
                                "source-1", "retail", List.of("orders"), true, false),
                        alice)
                .block();
        org.mockito.Mockito.verifyNoInteractions(baselineMdlService);
        assertThat(
                        controller
                                .associate(
                                        "group-1",
                                        new DatasetGroupController.AssociateRequest(
                                                "source-1", "retail", List.of(), true, true),
                                        alice)
                                .block())
                .isEmpty();
        verify(baselineMdlService).publishAfterDatasetChange("alice", "group-1");
    }

    @Test
    void associationFailureDoesNotAttemptBaselinePublication() {
        DatasetGroupController.AssociateRequest request =
                new DatasetGroupController.AssociateRequest(
                        "source-1", "retail", List.of("orders", "customers"), false, null);
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
