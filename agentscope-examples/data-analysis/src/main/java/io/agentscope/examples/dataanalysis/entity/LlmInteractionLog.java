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
package io.agentscope.examples.dataanalysis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * Records one round of LLM interaction within a ReActAgent iteration.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code sessionId} – chat session this interaction belongs to</li>
 *   <li>{@code iter} – zero-based ReAct iteration index</li>
 *   <li>{@code userQuestion} – the original user question for this session turn</li>
 *   <li>{@code messagesJson} – JSON array of messages sent to LLM; SYSTEM messages are replaced
 *       with a compact placeholder {@code {"role":"system","content":"[system-prompt]"}}</li>
 *   <li>{@code llmResponse} – serialized LLM response (text blocks + tool-use blocks)</li>
 * </ul>
 */
@TableName("llm_interaction_log")
public class LlmInteractionLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    /** Zero-based ReAct iteration index. */
    @TableField("iter")
    private int iter;

    /** The original user question that triggered this ReAct chain. */
    @TableField("user_question")
    private String userQuestion;

    /**
     * JSON array of messages sent to LLM.
     * SYSTEM messages are replaced with: {"role":"system","content":"[system-prompt, len=NNN]"}
     */
    @TableField("messages_json")
    private String messagesJson;

    /** Serialized LLM response: text + any tool-use calls. */
    @TableField("llm_response")
    private String llmResponse;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public LlmInteractionLog() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getIter() {
        return iter;
    }

    public void setIter(int iter) {
        this.iter = iter;
    }

    public String getUserQuestion() {
        return userQuestion;
    }

    public void setUserQuestion(String userQuestion) {
        this.userQuestion = userQuestion;
    }

    public String getMessagesJson() {
        return messagesJson;
    }

    public void setMessagesJson(String messagesJson) {
        this.messagesJson = messagesJson;
    }

    public String getLlmResponse() {
        return llmResponse;
    }

    public void setLlmResponse(String llmResponse) {
        this.llmResponse = llmResponse;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
