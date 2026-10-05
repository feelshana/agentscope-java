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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.SchemaGenerationService;
import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticBusinessRuleRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-level tests for {@link DatasetService#evidenceFor(String, List, String, int)}: the
 * real chunk/term/score pipeline of {@link KnowledgeEvidence} running against mocked JPA
 * repositories, mirroring the {@code DatasetServiceMdlDirtyTest} wiring.
 */
class DatasetServiceEvidenceTest {

    private DatasetGroupRepository groupRepository;
    private DatasetKnowledgeRepository knowledgeRepository;
    private DatasetService service;

    private final Map<String, DatasetGroupEntity> groupsById = new java.util.HashMap<>();
    private final Map<String, String> knowledgeByGroup = new java.util.HashMap<>();

    /** Two-section markdown doc (>600 chars so heading splitting kicks in). */
    private static final String KPI_DOC =
            "# 2026年9月咪咕视频考核口径\n\n"
                    + "日活跃用户目标为 1000 万，统计口径为全国去重活跃用户。".repeat(20)
                    + "\n\n## 付费用户口径\n\n"
                    + "付费用户目标为 200 万，按省份拆分下发考核。".repeat(20);

    /** Compact note (<600 chars): injected whole without keyword filtering. */
    private static final String SHORT_KPI_NOTE = "# 2026年9月考核目标\n日活跃用户目标 1000 万，付费用户目标 200 万。";

    /** Short note without KPI vocabulary, to exercise the whole-doc fallback. */
    private static final String SHORT_UNRELATED_NOTE = "# 会议室守则\n预约后 15 分钟未签到自动释放，白板笔用完请归位。";

    @BeforeEach
    void setUp() {
        groupRepository = mock(DatasetGroupRepository.class);
        knowledgeRepository = mock(DatasetKnowledgeRepository.class);
        DatasetStoreProperties storeProps = mock(DatasetStoreProperties.class);
        when(storeProps.url()).thenReturn("jdbc:mysql://localhost:3306/data_agent");
        when(storeProps.username()).thenReturn("dataagent");
        when(storeProps.password()).thenReturn("secret");
        service =
                new DatasetService(
                        mock(DatasetRepository.class),
                        groupRepository,
                        knowledgeRepository,
                        mock(SemanticTermRepository.class),
                        mock(SemanticViewRepository.class),
                        mock(SemanticBusinessRuleRepository.class),
                        mock(ExternalDataSourceRepository.class),
                        mock(DataSourceIntrospector.class),
                        mock(DatasetRelationRepository.class),
                        new InMemoryDataSourceRegistry(List.of()),
                        mock(TableProvisioner.class),
                        List.of(),
                        storeProps,
                        new ObjectMapper(),
                        mock(DatasetImportService.class),
                        mock(SchemaGenerationService.class),
                        mock(MdlWorkspaceReader.class));

        when(groupRepository.findByOwnerIdOrderByCreatedAtDesc(anyString()))
                .thenAnswer(inv -> List.copyOf(groupsById.values()));
        when(knowledgeRepository.findById(anyString()))
                .thenAnswer(
                        inv ->
                                Optional.ofNullable(
                                                knowledgeByGroup.get(
                                                        inv.getArgument(0, String.class)))
                                        .map(
                                                c ->
                                                        new DatasetKnowledgeEntity(
                                                                inv.getArgument(0), c)));
    }

    // ------------------------------------------------------------------ fixtures

    private void registerGroup(String groupId, String name) {
        DatasetGroupEntity g = new DatasetGroupEntity(groupId, "owner", name, null);
        g.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        g.setUpdatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        groupsById.put(groupId, g);
    }

    // ------------------------------------------------------------------ evidenceFor semantics

    @Test
    void returnsTopPassageWithCitation() {
        registerGroup("g1", "测试MDL");
        knowledgeByGroup.put("g1", KPI_DOC);

        String out = service.evidenceFor("owner", null, "活跃用户 考核目标", 1);

        assertTrue(out != null && !out.isBlank(), "a matching passage must come back");
        assertTrue(out.contains("【知识库「测试MDL」 › 2026年9月咪咕视频考核口径】"));
        assertTrue(out.contains("日活跃用户目标为 1000 万"));
    }

    @Test
    void unrelatedQueryYieldsNull() {
        registerGroup("g1", "测试MDL");
        knowledgeByGroup.put("g1", KPI_DOC);

        assertNull(service.evidenceFor("owner", null, "量子物理与元宇宙科普", 3));
    }

    @Test
    void shortDocIsInjectedWholeEvenWithoutTermOverlap() {
        registerGroup("g1", "测试MDL");
        knowledgeByGroup.put("g1", SHORT_KPI_NOTE);

        String out = service.evidenceFor("owner", null, "量子物理与元宇宙科普", 3);

        assertTrue(out != null && !out.isBlank(), "short docs bypass the keyword gate");
        assertTrue(out.contains("【知识库「测试MDL」 › 2026年9月考核目标】"));
        assertTrue(out.contains("日活跃用户目标 1000 万"));
    }

    @Test
    void matchedPassageOutranksWholeDocFallback() {
        registerGroup("g1", "长文库");
        registerGroup("g2", "便签库");
        knowledgeByGroup.put("g1", KPI_DOC);
        knowledgeByGroup.put("g2", SHORT_UNRELATED_NOTE);

        String out = service.evidenceFor("owner", null, "活跃用户 考核目标", 1);

        assertTrue(out != null && out.contains("长文库"));
        assertTrue(!out.contains("便签库"), "keyword hits outrank the whole-doc fallback");
    }

    @Test
    void termlessQueryStillInjectsShortDoc() {
        registerGroup("g1", "便签库");
        knowledgeByGroup.put("g1", SHORT_UNRELATED_NOTE);

        String out = service.evidenceFor("owner", null, "？？？", 3);

        assertTrue(out != null && out.contains("会议室守则"));
    }

    @Test
    void onlyGroupsFilterNarrowsTheSearch() {
        registerGroup("g1", "甲库");
        registerGroup("g2", "乙库");
        knowledgeByGroup.put("g1", KPI_DOC);
        knowledgeByGroup.put("g2", KPI_DOC);

        String out = service.evidenceFor("owner", List.of("g2"), "活跃用户 考核目标", 3);

        assertTrue(out != null && out.contains("乙库"));
        assertTrue(!out.contains("甲库"), "groups outside onlyGroups must stay invisible");
    }

    @Test
    void groupWithoutDocumentIsSkipped() {
        registerGroup("g1", "空库");

        assertNull(service.evidenceFor("owner", null, "活跃用户", 3));
    }

    @Test
    void nullOwnerBlankQueryAndBadLimitReturnNull() {
        registerGroup("g1", "测试MDL");
        knowledgeByGroup.put("g1", KPI_DOC);

        assertNull(service.evidenceFor(null, null, "活跃用户", 3));
        assertNull(service.evidenceFor("owner", null, "  ", 3));
        assertNull(service.evidenceFor("owner", null, "活跃用户", 0));
    }
}
