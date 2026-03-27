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
import io.agentscope.examples.dataanalysis.entity.ChatSession;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis-Plus Mapper for ChatSession.
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * List all sessions for a specific user, ordered by updated_at desc.
     */
    @Select(
            "SELECT * FROM chat_session WHERE user_name = #{userName}"
                    + " ORDER BY updated_at DESC LIMIT 100")
    List<ChatSession> findByUserNameOrderByUpdatedAtDesc(@Param("userName") String userName);

    /**
     * List all sessions ordered by updated_at desc (for history sidebar).
     * @deprecated prefer {@link #findByUserNameOrderByUpdatedAtDesc}
     */
    @Select("SELECT * FROM chat_session ORDER BY updated_at DESC LIMIT 100")
    List<ChatSession> findAllOrderByUpdatedAtDesc();

    /**
     * Update session title, message_count and updated_at.
     */
    @Update(
            "UPDATE chat_session SET title = #{title}, message_count = #{messageCount},"
                    + " updated_at = NOW(3) WHERE id = #{id}")
    int updateTitleAndCount(
            @Param("id") String id,
            @Param("title") String title,
            @Param("messageCount") int messageCount);

    /**
     * Update summarized flag and summary text.
     */
    @Update(
            "UPDATE chat_session SET is_summarized = 1, summary_text = #{summaryText},"
                    + " updated_at = NOW(3) WHERE id = #{id}")
    int updateSummary(@Param("id") String id, @Param("summaryText") String summaryText);
}
