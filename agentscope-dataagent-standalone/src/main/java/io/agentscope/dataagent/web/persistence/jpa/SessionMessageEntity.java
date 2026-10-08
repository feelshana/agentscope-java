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
        name = "session_message",
        indexes = {
            @Index(name = "ix_message_order", columnList = "session_key,position_no", unique = true)
        })
public class SessionMessageEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "session_key", length = 255, nullable = false)
    public String sessionKey;

    @Column(name = "position_no", nullable = false)
    public int position;

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    public String payload;
}
