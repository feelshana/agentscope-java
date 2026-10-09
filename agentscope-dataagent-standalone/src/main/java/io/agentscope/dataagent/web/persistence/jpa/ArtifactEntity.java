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
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "session_artifact",
        indexes = {
            @Index(name = "ix_artifact_session", columnList = "owner_id,session_id"),
            @Index(name = "ix_artifact_owner", columnList = "owner_id")
        })
public class ArtifactEntity {
    @Id
    @Column(length = 36)
    public String id;

    @Column(name = "owner_id", length = 128, nullable = false)
    public String ownerId;

    @Column(name = "session_id", length = 255, nullable = false)
    public String sessionId;

    @Column(name = "run_id", length = 36, nullable = false)
    public String runId;

    @Column(length = 255, nullable = false)
    public String filename;

    @Column(name = "source_path", length = 1024, nullable = false)
    public String sourcePath;

    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;

    @Column(name = "created_at_ms", nullable = false)
    public long createdAtMs;

    @Column(name = "input_data", nullable = false)
    public boolean inputData;
}
