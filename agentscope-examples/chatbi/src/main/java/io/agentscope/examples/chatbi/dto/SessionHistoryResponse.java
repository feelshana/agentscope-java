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
package io.agentscope.examples.chatbi.dto;

import io.agentscope.examples.chatbi.entity.ChatSession;
import java.time.LocalDateTime;

/**
 * DTO for chat session history list item (sidebar display).
 */
public class SessionHistoryResponse {

    private String sessionId;
    private String title;
    private int messageCount;
    private LocalDateTime updatedAt;

    public SessionHistoryResponse() {}

    public static SessionHistoryResponse from(ChatSession session) {
        SessionHistoryResponse resp = new SessionHistoryResponse();
        resp.setSessionId(session.getId());
        resp.setTitle(session.getTitle());
        resp.setMessageCount(session.getMessageCount());
        resp.setUpdatedAt(session.getUpdatedAt());
        return resp;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
