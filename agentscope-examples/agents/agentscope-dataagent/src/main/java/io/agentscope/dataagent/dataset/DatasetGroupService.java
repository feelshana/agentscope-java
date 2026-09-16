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

import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knowledge-base (KB) lifecycle: create/list/get/delete per-user groups that containerise
 * datasets and one relationship document each. Deleting a group cascades to its datasets (physical
 * tables dropped, registry entries removed) and its knowledge document.
 */
@Service
public class DatasetGroupService {

    private static final Logger log = LoggerFactory.getLogger(DatasetGroupService.class);
    private static final Pattern NAME = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z0-9_-]{1,100}$");
    public static final String DEFAULT_GROUP_NAME = "默认知识库";

    private final DatasetGroupRepository groupRepository;
    private final DatasetRepository datasetRepository;
    private final DatasetKnowledgeRepository knowledgeRepository;
    private final TableProvisioner provisioner;
    private final InMemoryDataSourceRegistry registry;
    private final DatasetService datasetService;

    public DatasetGroupService(
            DatasetGroupRepository groupRepository,
            DatasetRepository datasetRepository,
            DatasetKnowledgeRepository knowledgeRepository,
            TableProvisioner provisioner,
            InMemoryDataSourceRegistry registry,
            DatasetService datasetService) {
        this.groupRepository = groupRepository;
        this.datasetRepository = datasetRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.provisioner = provisioner;
        this.registry = registry;
        this.datasetService = datasetService;
    }

    /** Pre-group legacy datasets (groupId null) are folded into a per-owner default KB. */
    @PostConstruct
    void assignOrphans() {
        List<DatasetEntity> orphans =
                datasetRepository.findAll().stream()
                        .filter(d -> d.getGroupId() == null || d.getGroupId().isBlank())
                        .toList();
        if (orphans.isEmpty()) {
            return;
        }
        Map<String, String> defaultGroupByOwner = new HashMap<>();
        for (DatasetEntity d : orphans) {
            d.setGroupId(
                    defaultGroupByOwner.computeIfAbsent(
                            d.getOwnerId(), this::ensureDefaultGroupId));
        }
        datasetRepository.saveAll(orphans);
        log.info(
                "DatasetGroupService: assigned {} orphan dataset(s) to default KBs",
                orphans.size());
    }

    @Transactional
    public DatasetGroupEntity createGroup(String ownerId, String name, String description) {
        if (name == null || !NAME.matcher(name.trim()).matches()) {
            throw new DatasetException(
                    "KB name must be 1-100 chars of Chinese/letters/digits/'-'/'_'");
        }
        if (description != null && description.length() > 1000) {
            throw new DatasetException("KB description must not exceed 1000 chars");
        }
        groupRepository
                .findByOwnerIdAndName(ownerId, name.trim())
                .ifPresent(
                        g -> {
                            throw new DatasetException("KB name already exists: " + name, 409);
                        });
        DatasetGroupEntity group =
                new DatasetGroupEntity(
                        UUID.randomUUID().toString(),
                        ownerId,
                        name.trim(),
                        description == null ? null : description.trim());
        return groupRepository.save(group);
    }

    public List<DatasetGroupEntity> listGroups(String ownerId) {
        return groupRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    public DatasetGroupEntity getGroup(String ownerId, String groupId) {
        return groupRepository
                .findById(groupId)
                .filter(g -> g.getOwnerId().equals(ownerId))
                .orElseThrow(
                        () -> new DatasetException("Knowledge base not found: " + groupId, 404));
    }

    /** Persist updates to an existing group entity. */
    @Transactional
    public DatasetGroupEntity saveGroup(DatasetGroupEntity group) {
        return groupRepository.save(group);
    }

    public List<DatasetEntity> listDatasets(String ownerId, String groupId) {
        getGroup(ownerId, groupId);
        return datasetRepository.findByGroupId(groupId).stream()
                .filter(d -> d.getOwnerId().equals(ownerId))
                .toList();
    }

    @Transactional
    public void deleteGroup(String ownerId, String groupId) {
        DatasetGroupEntity group = getGroup(ownerId, groupId);
        List<DatasetEntity> datasets = datasetRepository.findByGroupId(groupId);
        for (DatasetEntity d : datasets) {
            datasetService.deleteEntity(d);
        }
        datasetRepository.deleteAll(datasets);
        knowledgeRepository.deleteById(groupId);
        groupRepository.delete(group);
        log.info(
                "DatasetGroupService: deleted KB {} with {} dataset(s) for owner {}",
                groupId,
                datasets.size(),
                ownerId);
    }

    private String ensureDefaultGroupId(String ownerId) {
        return groupRepository
                .findByOwnerIdAndName(ownerId, DEFAULT_GROUP_NAME)
                .map(DatasetGroupEntity::getId)
                .orElseGet(
                        () ->
                                groupRepository
                                        .save(
                                                new DatasetGroupEntity(
                                                        UUID.randomUUID().toString(),
                                                        ownerId,
                                                        DEFAULT_GROUP_NAME,
                                                        "自动创建的默认知识库"))
                                        .getId());
    }
}
