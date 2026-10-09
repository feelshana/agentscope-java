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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link AgentEntity}. */
public interface AgentEntityRepository extends JpaRepository<AgentEntity, Long> {

    /** All agent definitions owned by {@code ownerId}, oldest first. */
    List<AgentEntity> findByOwnerIdOrderByCreatedAtAsc(String ownerId);

    /** Single definition lookup by the {@code (ownerId, agentId)} pair. */
    Optional<AgentEntity> findByOwnerIdAndAgentId(String ownerId, String agentId);

    @Query(
            """
            select distinct a from AgentEntity a left join fetch a.shares
            where exists (select u.userId from UserEntity u where u.userId = a.ownerId)
              and (:agentId is null or a.agentId = :agentId)
              and (a.ownerId = :userId or exists (
                select g.id from AgentShareEntity g where g.agent = a
                  and upper(g.tier) in ('CLONE', 'RUN', 'EDIT')
                  and (g.granteeType = 'WORKSPACE'
                    or (g.granteeType = 'USER' and g.granteeId = :userId))))
            order by a.createdAt, a.ownerId, a.rowId
            """)
    List<AgentEntity> findVisible(@Param("userId") String userId, @Param("agentId") String agentId);

    @Query(
            """
            select distinct a from AgentEntity a left join fetch a.shares
            where a.agentId = :agentId
              and exists (select u.userId from UserEntity u where u.userId = a.ownerId)
            order by a.createdAt, a.ownerId, a.rowId
            """)
    List<AgentEntity> findByAgentId(@Param("agentId") String agentId);
}
