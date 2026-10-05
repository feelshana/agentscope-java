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

import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Coordinates one baseline MDL publication after a knowledge base's datasets change. */
@Service
public class BaselineMdlService {

    private final DatasetGroupRepository groupRepository;
    private final DatasetRepository datasetRepository;
    private final MdlPublishService publishService;
    private final WrenQueryGateway wrenGateway;

    public BaselineMdlService(
            DatasetGroupRepository groupRepository,
            DatasetRepository datasetRepository,
            MdlPublishService publishService,
            WrenQueryGateway wrenGateway) {
        this.groupRepository = groupRepository;
        this.datasetRepository = datasetRepository;
        this.publishService = publishService;
        this.wrenGateway = wrenGateway;
    }

    /**
     * Publishes the current table/column baseline once. The caller must already run on a blocking
     * scheduler because this method performs JPA, filesystem and subprocess work.
     */
    public MdlPublishService.MdlPublishResult publishAfterDatasetChange(
            String ownerId, String groupId) {
        // Taken before any lock queueing: the tick strictly postdates this request's committed
        // change, so a publish that began assembling later necessarily observed it
        // (MdlPublishService#publishBaseline coalesces on that invariant).
        long requestTick = System.nanoTime();
        DatasetGroupEntity group = requireOwnedGroup(ownerId, groupId);
        if (datasetRepository.countByGroupId(groupId) == 0) {
            publishService.deleteArtifacts(groupId);
            wrenGateway.invalidate(groupId);
            group.setMdlState("NONE");
            group.setMdlVersion(0);
            group.setMdlPublishedAt(null);
            group.setMdlLastError(null);
            group.setUpdatedAt(Instant.now());
            groupRepository.save(group);
            publishService.recordBaselineCoverage(groupId);
            return new MdlPublishService.MdlPublishResult(
                    true, java.util.List.of(), "", "NONE", 0, null);
        }

        boolean hadPublishedSnapshot = group.getMdlVersion() > 0;
        MdlPublishService.MdlPublishResult result;
        try {
            result = publishService.publishBaseline(groupId, requestTick);
        } catch (RuntimeException error) {
            throw publishFailure(hadPublishedSnapshot, rootMessage(error), error);
        }
        if (!result.ok()) {
            String reason =
                    result.issues().stream()
                            .filter(issue -> "error".equals(issue.severity()))
                            .map(MdlPublishService.MdlIssue::message)
                            .findFirst()
                            .orElse("未知错误");
            throw publishFailure(hadPublishedSnapshot, reason, null);
        }
        wrenGateway.invalidate(groupId);
        return result;
    }

    private DatasetGroupEntity requireOwnedGroup(String ownerId, String groupId) {
        return groupRepository
                .findById(groupId)
                .filter(group -> group.getOwnerId().equals(ownerId))
                .orElseThrow(
                        () -> new DatasetException("Knowledge base not found: " + groupId, 404));
    }

    private static DatasetException publishFailure(
            boolean hadPublishedSnapshot, String reason, RuntimeException cause) {
        String prefix =
                hadPublishedSnapshot ? "数据已保存，但基础 MDL 重建失败，新数据尚不可查询：" : "数据已保存，但基础 MDL 初始化失败：";
        return new DatasetException(prefix + reason, 500, cause);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
