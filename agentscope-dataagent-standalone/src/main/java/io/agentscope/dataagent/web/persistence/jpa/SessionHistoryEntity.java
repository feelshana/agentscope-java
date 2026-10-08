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
import jakarta.persistence.Table;

@Entity
@Table(name = "session_history")
public class SessionHistoryEntity {
    @Id
    @Column(name = "session_key", length = 255)
    public String sessionKey;

    @Column(name = "session_id", length = 255)
    public String sessionId;

    @Column(length = 160)
    public String title;

    @Column(length = 500)
    public String preview;

    @Column(name = "activity_ms", nullable = false)
    public long activityMs;

    @Column(name = "file_stamp", nullable = false)
    public long fileStamp;

    @Column(name = "file_size", nullable = false)
    public long fileSize;

    @Column(name = "source_path", length = 2048)
    public String sourcePath;

    @Column(name = "message_count", nullable = false)
    public int messageCount;
}
