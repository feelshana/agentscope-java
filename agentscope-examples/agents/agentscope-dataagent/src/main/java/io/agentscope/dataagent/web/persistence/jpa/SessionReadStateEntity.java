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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * Per-user, per-session "last read at" tracker. Replaces the former {@code session-read-state.json}
 * file store. Composite primary key: {@code (userId, sessionKey)}.
 */
@Entity
@Table(
        name = "session_read_state",
        indexes = {@Index(name = "ix_session_read_state_user_id", columnList = "user_id")})
@IdClass(SessionReadStateEntity.ReadStateKey.class)
public class SessionReadStateEntity {

    @Id
    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Id
    @Column(name = "session_key", length = 255, nullable = false)
    private String sessionKey;

    @Column(name = "last_read_at_ms", nullable = false)
    private long lastReadAtMs;

    public SessionReadStateEntity() {}

    public SessionReadStateEntity(String userId, String sessionKey, long lastReadAtMs) {
        this.userId = userId;
        this.sessionKey = sessionKey;
        this.lastReadAtMs = lastReadAtMs;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public long getLastReadAtMs() {
        return lastReadAtMs;
    }

    public void setLastReadAtMs(long lastReadAtMs) {
        this.lastReadAtMs = lastReadAtMs;
    }

    /** Composite key class for {@link IdClass} mapping. */
    public static class ReadStateKey implements Serializable {

        private String userId;
        private String sessionKey;

        public ReadStateKey() {}

        public ReadStateKey(String userId, String sessionKey) {
            this.userId = userId;
            this.sessionKey = sessionKey;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getSessionKey() {
            return sessionKey;
        }

        public void setSessionKey(String sessionKey) {
            this.sessionKey = sessionKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReadStateKey that)) return false;
            return Objects.equals(userId, that.userId)
                    && Objects.equals(sessionKey, that.sessionKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, sessionKey);
        }
    }
}
