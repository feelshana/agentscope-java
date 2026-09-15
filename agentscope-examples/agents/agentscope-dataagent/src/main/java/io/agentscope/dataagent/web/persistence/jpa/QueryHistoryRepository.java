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
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link QueryHistoryEntity}. */
public interface QueryHistoryRepository extends JpaRepository<QueryHistoryEntity, Long> {

    List<QueryHistoryEntity> findByGroupIdOrderByCreatedAtDesc(String groupId);

    List<QueryHistoryEntity> findByGroupIdAndSourceOrderByCreatedAtDesc(
            String groupId, String source);

    /**
     * 根据关键词模糊搜索查询历史（LIKE 全文匹配 nl_query）。
     * 后续可升级为向量相似度搜索。
     */
    @Query(
            "SELECT q FROM QueryHistoryEntity q WHERE q.groupId = :groupId "
                    + "AND q.nlQuery LIKE %:keyword% ORDER BY q.createdAt DESC")
    List<QueryHistoryEntity> findByGroupIdAndKeyword(
            @Param("groupId") String groupId, @Param("keyword") String keyword);

    /**
     * 查询最近的查询记录，用于召回 few-shot 示例。
     */
    List<QueryHistoryEntity> findTop20ByGroupIdOrderByCreatedAtDesc(String groupId);
}
