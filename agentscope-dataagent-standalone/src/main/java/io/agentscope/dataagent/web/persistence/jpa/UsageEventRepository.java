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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link UsageEventEntity}. */
public interface UsageEventRepository extends JpaRepository<UsageEventEntity, Long> {

    List<UsageEventEntity> findAllByOrderByTimestampMsDesc(Pageable pageable);

    List<UsageEventEntity> findByUserIdOrderByTimestampMsDesc(
            @Param("userId") String userId, Pageable pageable);

    @Query("SELECT COUNT(e) FROM UsageEventEntity e WHERE e.timestampMs >= :sinceMs")
    long countSince(@Param("sinceMs") long sinceMs);

    @Query(
            "SELECT COUNT(e) FROM UsageEventEntity e"
                    + " WHERE e.userId = :userId AND e.timestampMs >= :sinceMs")
    long countByUserSince(@Param("userId") String userId, @Param("sinceMs") long sinceMs);

    @Query("SELECT AVG(e.durationMs) FROM UsageEventEntity e WHERE e.userId = :userId")
    Long avgDurationByUser(@Param("userId") String userId);

    @Query("SELECT AVG(e.durationMs) FROM UsageEventEntity e")
    Long avgDuration();

    @Query("SELECT COUNT(DISTINCT e.userId) FROM UsageEventEntity e")
    long countDistinctUsers();
}
