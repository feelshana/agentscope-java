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

public interface SessionHistoryRepository extends JpaRepository<SessionHistoryEntity, String> {
    @Query(
            """
            select s, h, r from SessionRegistryEntity s
            left join SessionHistoryEntity h on h.sessionKey = s.sessionKey
            left join SessionReadStateEntity r on r.sessionKey = s.sessionKey and r.userId = s.userId
            where s.userId = :owner and s.kind = 'main'
            and locate(concat(concat('|x:agentId=', :agent), '|'), concat(s.gateKey, '|')) > 0
            and (:unread = false or coalesce(h.activityMs, s.lastActivityMs) > coalesce(r.lastReadAtMs, 0))
            and (coalesce(h.activityMs, s.lastActivityMs) < :before
              or (coalesce(h.activityMs, s.lastActivityMs) = :before and s.sessionKey < :key))
            order by coalesce(h.activityMs, s.lastActivityMs) desc, s.sessionKey desc
            """)
    List<Object[]> inbox(
            @Param("owner") String owner,
            @Param("agent") String agent,
            @Param("unread") boolean unread,
            @Param("before") long before,
            @Param("key") String key,
            Pageable page);
}
