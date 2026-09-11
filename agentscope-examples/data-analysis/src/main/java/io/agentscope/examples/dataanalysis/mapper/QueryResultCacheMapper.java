/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.dataanalysis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.agentscope.examples.dataanalysis.entity.QueryResultCache;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis-Plus Mapper for QueryResultCache.
 */
@Mapper
public interface QueryResultCacheMapper extends BaseMapper<QueryResultCache> {

    /**
     * Find all cached query results for a session, ordered by created_at ascending.
     */
    @Select(
            "SELECT * FROM query_result_cache WHERE session_id = #{sessionId} ORDER BY created_at"
                    + " DESC")
    List<QueryResultCache> findBySessionIdOrderByRound(@Param("sessionId") String sessionId);

    /**
     * Delete all cached results for a session.
     */
    @Delete("DELETE FROM query_result_cache WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
