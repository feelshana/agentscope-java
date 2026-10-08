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

/** Spring Data repository for {@link SessionRegistryEntity}. */
public interface SessionRegistryRepository extends JpaRepository<SessionRegistryEntity, String> {
    boolean existsByUserIdAndSessionId(String userId, String sessionId);

    @Query(
            """
            select s from SessionRegistryEntity s
            where s.kind = 'main' and s.lastActivityMs < :cutoff
            order by s.lastActivityMs asc, s.sessionKey asc
            """)
    List<SessionRegistryEntity> findRetentionCandidates(
            @Param("cutoff") long cutoff, Pageable pageable);
}
