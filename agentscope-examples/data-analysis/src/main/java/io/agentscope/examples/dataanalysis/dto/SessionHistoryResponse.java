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
package io.agentscope.examples.dataanalysis.dto;

import io.agentscope.examples.dataanalysis.entity.ChatSession;
import java.time.format.DateTimeFormatter;

/**
 * DTO returned by the /api/sessions history list endpoint.
 */
public class SessionHistoryResponse {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private String id;
    private String title;
    private String updatedAt;
    private String createdAt;
    private int messageCount;

    public SessionHistoryResponse() {}

    public static SessionHistoryResponse from(ChatSession session) {
        SessionHistoryResponse resp = new SessionHistoryResponse();
        resp.id = session.getId();
        resp.title = session.getTitle();
        resp.messageCount = session.getMessageCount();
        if (session.getUpdatedAt() != null) {
            resp.updatedAt = session.getUpdatedAt().format(FORMATTER);
        }
        if (session.getCreatedAt() != null) {
            resp.createdAt = session.getCreatedAt().format(FORMATTER);
        }
        return resp;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }
}
