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
import io.agentscope.examples.dataanalysis.entity.LlmInteractionLog;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis-Plus Mapper for LlmInteractionLog.
 */
@Mapper
public interface LlmInteractionLogMapper extends BaseMapper<LlmInteractionLog> {

    /**
     * Find all interaction logs for a session, ordered by iter ascending.
     */
    @Select("SELECT * FROM llm_interaction_log WHERE session_id = #{sessionId} ORDER BY iter ASC")
    List<LlmInteractionLog> findBySessionIdOrderByIter(@Param("sessionId") String sessionId);

    /**
     * Delete all logs for a session.
     */
    @Delete("DELETE FROM llm_interaction_log WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
