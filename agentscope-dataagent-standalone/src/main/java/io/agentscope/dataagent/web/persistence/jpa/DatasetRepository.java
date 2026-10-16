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

/** Spring Data repository for {@link DatasetEntity}. */
public interface DatasetRepository extends JpaRepository<DatasetEntity, String> {

    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from DatasetEntity d where d.id = :id")
    Optional<DatasetEntity> lockById(
            @org.springframework.data.repository.query.Param("id") String id);

    List<DatasetEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<DatasetEntity> findByOwnerIdAndName(String ownerId, String name);

    Optional<DatasetEntity> findByOwnerIdAndGroupIdAndName(
            String ownerId, String groupId, String name);

    Optional<DatasetEntity> findBySchemaName(String schemaName);

    List<DatasetEntity> findByGroupId(String groupId);

    long countByGroupId(String groupId);

    List<DatasetEntity> findByGroupIdInOrderByCreatedAtDesc(List<String> groupIds);

    interface GroupCount {
        String getGroupId();

        long getTotal();
    }

    @org.springframework.data.jpa.repository.Query(
            "select d.groupId as groupId, count(d) as total from DatasetEntity d where d.groupId in"
                    + " :groupIds group by d.groupId")
    List<GroupCount> countForGroups(List<String> groupIds);
}
