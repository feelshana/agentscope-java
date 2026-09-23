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
package io.agentscope.dataagent.web.session;

import io.agentscope.dataagent.web.persistence.jpa.SessionReadStateEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionReadStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user, per-session "last read at" tracker, used by the Threads inbox to derive an unread flag.
 *
 * <p>A session is considered <em>unread</em> when its {@code lastActivityMs} is greater than the
 * stored last-read timestamp. Marking-as-read updates the stored timestamp to {@link
 * System#currentTimeMillis()} (or to a caller-supplied value).
 *
 * <p>State is persisted in the {@code session_read_state} MySQL table so that read state survives
 * restarts.
 */
@Component
public class SessionReadStateStore {

    private static final Logger log = LoggerFactory.getLogger(SessionReadStateStore.class);

    private final SessionReadStateRepository repository;

    public SessionReadStateStore(SessionReadStateRepository repository) {
        this.repository = repository;
    }

    /** Marks the (user, session) pair as read at {@code System.currentTimeMillis()}. */
    @Transactional
    public long markRead(String userId, String sessionKey) {
        return markRead(userId, sessionKey, System.currentTimeMillis());
    }

    /** Marks the (user, session) pair as read at a specific epoch-ms timestamp. */
    @Transactional
    public long markRead(String userId, String sessionKey, long readAtMs) {
        String effectiveUser = userId != null ? userId : "__anon__";
        SessionReadStateEntity entity =
                repository
                        .findByUserIdAndSessionKey(effectiveUser, sessionKey)
                        .orElse(new SessionReadStateEntity(effectiveUser, sessionKey, 0L));
        entity.setLastReadAtMs(readAtMs);
        repository.save(entity);
        return readAtMs;
    }

    /** Returns the last-read timestamp for the (user, session) pair, or {@code 0L} if never. */
    public long lastReadAt(String userId, String sessionKey) {
        String effectiveUser = userId != null ? userId : "__anon__";
        return repository
                .findByUserIdAndSessionKey(effectiveUser, sessionKey)
                .map(SessionReadStateEntity::getLastReadAtMs)
                .orElse(0L);
    }

    /**
     * Whether the session is unread for this user — i.e. its {@code lastActivityMs} is strictly
     * greater than the stored last-read timestamp. Sessions that have never been read are treated
     * as unread.
     */
    public boolean isUnread(String userId, String sessionKey, long lastActivityMs) {
        return lastActivityMs > lastReadAt(userId, sessionKey);
    }
}
