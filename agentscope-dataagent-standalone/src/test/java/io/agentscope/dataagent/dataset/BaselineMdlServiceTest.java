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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies baseline publication orchestration after knowledge-base dataset changes. */
class BaselineMdlServiceTest {

    private DatasetGroupRepository groupRepository;
    private DatasetRepository datasetRepository;
    private MdlPublishService publishService;
    private WrenQueryGateway wrenGateway;
    private BaselineMdlService service;

    @BeforeEach
    void setUp() {
        groupRepository = mock(DatasetGroupRepository.class);
        datasetRepository = mock(DatasetRepository.class);
        publishService = mock(MdlPublishService.class);
        wrenGateway = mock(WrenQueryGateway.class);
        service =
                new BaselineMdlService(
                        groupRepository, datasetRepository, publishService, wrenGateway);
    }

    @Test
    void publishesBaselineAndInvalidatesRunningInstance() {
        DatasetGroupEntity group = group(0);
        MdlPublishService.MdlPublishResult published =
                new MdlPublishService.MdlPublishResult(
                        true, List.of(), "build ok", "PUBLISHED", 1, "2026-10-02T10:00:00Z");
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(datasetRepository.countByGroupId("g1")).thenReturn(2L);
        when(publishService.publishBaseline(eq("g1"), anyLong())).thenReturn(published);

        assertThat(service.publishAfterDatasetChange("alice", "g1")).isSameAs(published);

        verify(publishService).publishBaseline(eq("g1"), anyLong());
        verify(publishService, never()).recordBaselineCoverage("g1");
        verify(wrenGateway).invalidate("g1");
    }

    @Test
    void emptyGroupDeletesArtifactsAndResetsLifecycle() {
        DatasetGroupEntity group = group(3);
        group.setMdlState("DIRTY");
        group.setMdlPublishedAt(Instant.parse("2026-10-01T10:00:00Z"));
        group.setMdlLastError("old error");
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(datasetRepository.countByGroupId("g1")).thenReturn(0L);

        MdlPublishService.MdlPublishResult result =
                service.publishAfterDatasetChange("alice", "g1");

        assertThat(result.ok()).isTrue();
        assertThat(result.mdlState()).isEqualTo("NONE");
        assertThat(group.getMdlState()).isEqualTo("NONE");
        assertThat(group.getMdlVersion()).isZero();
        assertThat(group.getMdlPublishedAt()).isNull();
        assertThat(group.getMdlLastError()).isNull();
        verify(publishService).deleteArtifacts("g1");
        verify(publishService).recordBaselineCoverage("g1");
        verify(publishService, never()).publishBaseline(eq("g1"), anyLong());
        verify(wrenGateway).invalidate("g1");
        verify(groupRepository).save(group);
    }

    @Test
    void firstPublicationFailureReportsInitializationFailure() {
        DatasetGroupEntity group = group(0);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(datasetRepository.countByGroupId("g1")).thenReturn(1L);
        when(publishService.publishBaseline(eq("g1"), anyLong()))
                .thenReturn(
                        new MdlPublishService.MdlPublishResult(
                                false,
                                List.of(
                                        new MdlPublishService.MdlIssue(
                                                "error", "字段类型不受支持", "model")),
                                "",
                                "FAILED",
                                0,
                                null));

        assertThatThrownBy(() -> service.publishAfterDatasetChange("alice", "g1"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("基础 MDL 初始化失败")
                .hasMessageContaining("字段类型不受支持");
        verify(wrenGateway, never()).invalidate("g1");
    }

    @Test
    void rebuildExceptionKeepsPublishedSnapshotMessage() {
        DatasetGroupEntity group = group(2);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        when(datasetRepository.countByGroupId("g1")).thenReturn(1L);
        when(publishService.publishBaseline(eq("g1"), anyLong()))
                .thenThrow(
                        new IllegalStateException(
                                "wrapper", new IllegalArgumentException("wren build failed")));

        assertThatThrownBy(() -> service.publishAfterDatasetChange("alice", "g1"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("基础 MDL 重建失败")
                .hasMessageContaining("新数据尚不可查询")
                .hasMessageContaining("wren build failed");
        verify(wrenGateway, never()).invalidate("g1");
    }

    @Test
    void foreignOwnerIsHiddenAsNotFound() {
        DatasetGroupEntity group = group(0);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(group));

        assertThatThrownBy(() -> service.publishAfterDatasetChange("bob", "g1"))
                .isInstanceOfSatisfying(
                        DatasetException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(404);
                            assertThat(error.getMessage()).contains("Knowledge base not found");
                        });
        verify(datasetRepository, never()).countByGroupId("g1");
        verify(publishService, never()).publishBaseline(eq("g1"), anyLong());
        verify(publishService, never()).recordBaselineCoverage("g1");
    }

    private static DatasetGroupEntity group(int mdlVersion) {
        DatasetGroupEntity group = new DatasetGroupEntity("g1", "alice", "订单域", null);
        group.setMdlVersion(mdlVersion);
        return group;
    }
}
