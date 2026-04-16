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
import io.agentscope.examples.dataanalysis.entity.ChatMessage;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis-Plus Mapper for ChatMessage.
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * Find all messages for a session, ordered by creation time ascending.
     */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(@Param("sessionId") String sessionId);

    /**
     * Count total messages in a session.
     */
    @Select("SELECT COUNT(*) FROM chat_message WHERE session_id = #{sessionId}")
    long countBySessionId(@Param("sessionId") String sessionId);

    /**
     * Get the N most recent messages in a session (for keep-recent-messages logic).
     * Results are returned in DESC order; caller should reverse if needed.
     */
    @Select(
            "SELECT * FROM chat_message WHERE session_id = #{sessionId}"
                    + " ORDER BY created_at DESC LIMIT #{limit}")
    List<ChatMessage> findRecentBySessionId(
            @Param("sessionId") String sessionId, @Param("limit") int limit);

    /**
     * Delete all messages for a session (used when deleting a session).
     */
    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    /**
     * Find latest message in a session.
     */
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT 1")
    ChatMessage findLatestBySessionId(@Param("sessionId") String sessionId);

    /**
     * Find the latest COMPLETED assistant message for a session.
     * Used by resume() to recover content when the stream has already finished.
     */
    @Select(
            "SELECT * FROM chat_message WHERE session_id = #{sessionId}"
                    + " AND role = 'assistant' AND streaming_status = 'COMPLETED'"
                    + " ORDER BY id DESC LIMIT 1")
    ChatMessage findLatestCompletedAssistantBySessionId(@Param("sessionId") String sessionId);

    /**
     * Update message content by id (for streaming incremental persistence).
     */
    @Update("UPDATE chat_message SET content = #{content} WHERE id = #{id}")
    int updateContentById(@Param("id") Long id, @Param("content") String content);

    /**
     * Update streaming status for an assistant message.
     * Called when stream starts (RUNNING) and when it completes or errors (COMPLETED).
     */
    @Update("UPDATE chat_message SET streaming_status = #{status} WHERE id = #{id}")
    int updateStreamingStatusById(@Param("id") Long id, @Param("status") String status);

    /**
     * Update both content and streaming status atomically (used on stream completion).
     */
    @Update(
            "UPDATE chat_message SET content = #{content}, streaming_status = #{status}"
                    + " WHERE id = #{id}")
    int updateContentAndStatusById(
            @Param("id") Long id, @Param("content") String content, @Param("status") String status);

    /**
     * Find the latest assistant message with RUNNING status for a session.
     * Used by /api/chat/resume to check if a stream is still in progress.
     */
    @Select(
            "SELECT * FROM chat_message WHERE session_id = #{sessionId}"
                    + " AND role = 'assistant' AND streaming_status = 'RUNNING'"
                    + " ORDER BY id DESC LIMIT 1")
    ChatMessage findRunningBySessionId(@Param("sessionId") String sessionId);
}
