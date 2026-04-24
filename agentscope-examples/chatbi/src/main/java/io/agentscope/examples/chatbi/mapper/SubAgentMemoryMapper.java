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
package io.agentscope.examples.chatbi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.agentscope.examples.chatbi.entity.SubAgentMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis-Plus Mapper for {@link SubAgentMemory}.
 */
@Mapper
public interface SubAgentMemoryMapper extends BaseMapper<SubAgentMemory> {

    /**
     * Find by session ID and type.
     */
    @Select(
            "SELECT * FROM sub_agent_memory WHERE session_id = #{sessionId} AND type = #{type}"
                    + " LIMIT 1")
    SubAgentMemory findBySessionIdAndType(
            @Param("sessionId") String sessionId, @Param("type") String type);

    /**
     * Update the content (JSON array) for an existing session row.
     */
    @Update(
            "UPDATE sub_agent_memory SET content = #{content}, updated_at = NOW(3) "
                    + "WHERE session_id = #{sessionId} AND type = #{type}")
    int updateContent(
            @Param("sessionId") String sessionId,
            @Param("type") String type,
            @Param("content") String content);
}
