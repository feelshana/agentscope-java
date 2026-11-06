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
package io.agentscope.dataagent.web.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.TestPropertySource;

/**
 * Locks the legacy-row compatibility contract of the specs/010 M1 {@code mdl_*} columns: groups
 * created before the columns existed carry SQL NULL in {@code mdl_version} (ddl-auto=update adds
 * columns but never backfills them), and hydrating NULL into the primitive {@code mdlVersion}
 * field throws JpaSystemException on every load — the knowledge-base list endpoint fails with
 * 500. {@code backfillMdlDefaults}, invoked first thing in {@code
 * DatasetGroupService#assignOrphans} at startup, must make those rows loadable again.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.sql.init.mode=never"})
class DatasetGroupMdlBackfillTest {

    @Autowired private DatasetGroupRepository repository;
    @Autowired private DatasetRepository datasets;
    @Autowired private KnowledgeBaseGrantRepository grants;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Inserts a row exactly as a pre-M1 schema left it: NULL mdl_state and NULL mdl_version. The
     * ALTER is needed because create-drop DDL maps the primitive int column as NOT NULL, while
     * ddl-auto=update on an existing table adds it nullable.
     */
    private void insertLegacyRow(String id) {
        jdbcTemplate.execute(
                "ALTER TABLE dataagent_dataset_group ALTER COLUMN mdl_version SET NULL");
        jdbcTemplate.update(
                "INSERT INTO dataagent_dataset_group"
                        + " (group_id, owner_id, name, created_at, updated_at, mdl_state,"
                        + " mdl_version) VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,"
                        + " NULL, NULL)",
                id,
                "legacy-owner",
                "Legacy KB");
    }

    @Test
    void nullMdlVersionBreaksEntityLoad() {
        insertLegacyRow("legacy-null");

        // The guarded regression: primitive int field + SQL NULL column = JpaSystemException.
        assertThatThrownBy(() -> repository.findById("legacy-null"))
                .isInstanceOf(JpaSystemException.class);
    }

    @Test
    void backfillRestoresLegacyRows() {
        insertLegacyRow("legacy-backfill");

        repository.backfillMdlDefaults();

        DatasetGroupEntity reloaded = repository.findById("legacy-backfill").orElseThrow();
        assertThat(reloaded.getMdlVersion()).isZero();
        assertThat(reloaded.getMdlState()).isEqualTo("NONE");
    }

    @Test
    void newestQueryableCandidatesIncludeSharedAndPublicButSkipDraftsAndPrivateGroups() {
        saveCandidate("owned", "reader", 1, 1, true);
        saveCandidate("public", "other", 2, 1, true).setPublicQuery(true);
        saveCandidate("granted", "other", 3, 1, true);
        var grant = new KnowledgeBaseGrantEntity("granted", "reader");
        grant.grant("QUERY", "other");
        grants.save(grant);
        saveCandidate("private", "other", 4, 1, true);
        saveCandidate("draft", "reader", 5, 0, true);
        saveCandidate("empty", "reader", 6, 1, false);
        saveCandidate("deleting", "reader", 7, 1, true).setMdlState("DELETING");
        saveCandidate("changed-data", "reader", 8, 1, true);
        repository.requireModeling("changed-data");
        repository.markDraftChanged("granted");

        var ordering = Sort.by("createdAt").descending().and(Sort.by("id"));
        var candidates =
                repository.findAccessible(
                        "reader", false, false, "", true, PageRequest.of(0, 20, ordering));
        assertThat(candidates.getContent())
                .extracting(DatasetGroupEntity::getId)
                .containsExactly("granted", "public", "owned");
    }

    private DatasetGroupEntity saveCandidate(
            String id, String owner, int created, int version, boolean hasDataset) {
        var group = new DatasetGroupEntity(id, owner, id, "");
        group.setCreatedAt(Instant.ofEpochSecond(created));
        group.setMdlVersion(version);
        if (hasDataset) {
            var dataset = new DatasetEntity();
            dataset.setId(id);
            dataset.setGroupId(id);
            dataset.setOwnerId(owner);
            dataset.setName(id);
            dataset.setSchemaName("test");
            dataset.setTableName(id);
            datasets.save(dataset);
        }
        return repository.save(group);
    }
}
