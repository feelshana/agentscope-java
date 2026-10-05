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

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** Spring Data repository for {@link DatasetGroupEntity}. */
public interface DatasetGroupRepository extends JpaRepository<DatasetGroupEntity, String> {

    List<DatasetGroupEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<DatasetGroupEntity> findByOwnerIdAndName(String ownerId, String name);

    /**
     * One-shot startup backfill for rows created before the specs/010 M1 mdl_* columns existed:
     * {@code ddl-auto=update} adds the columns but leaves them NULL on legacy rows, and hydrating
     * SQL NULL into the primitive {@code mdlVersion} field throws JpaSystemException on every
     * group load. Native SQL on purpose — loading the affected rows through JPA is exactly what
     * fails.
     */
    @Modifying
    @Transactional
    @Query(
            value =
                    "UPDATE dataagent_dataset_group SET mdl_version = 0, mdl_state = 'NONE'"
                            + " WHERE mdl_version IS NULL OR mdl_state IS NULL",
            nativeQuery = true)
    void backfillMdlDefaults();
}
