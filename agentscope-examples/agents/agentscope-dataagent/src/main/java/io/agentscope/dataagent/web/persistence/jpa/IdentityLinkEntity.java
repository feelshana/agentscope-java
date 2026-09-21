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
 * Maps a claw {@code userId} to that user's identity on external channels (Slack, Discord, GitHub,
 * ...). Replaces the former {@code identity-links.json} file store. Composite primary key:
 * {@code (userId, channelId)}.
 */
@Entity
@Table(
        name = "identity_link",
        indexes = {
            @Index(name = "ix_identity_link_user_id", columnList = "user_id"),
            @Index(
                    name = "ix_identity_link_channel_external",
                    columnList = "channel_id, external_id")
        })
@IdClass(IdentityLinkEntity.IdentityKey.class)
public class IdentityLinkEntity {

    @Id
    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Id
    @Column(name = "channel_id", length = 64, nullable = false)
    private String channelId;

    @Column(name = "external_id", length = 255, nullable = false)
    private String externalId;

    public IdentityLinkEntity() {}

    public IdentityLinkEntity(String userId, String channelId, String externalId) {
        this.userId = userId;
        this.channelId = channelId;
        this.externalId = externalId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    /** Composite key class for {@link IdClass} mapping. */
    public static class IdentityKey implements Serializable {

        private String userId;
        private String channelId;

        public IdentityKey() {}

        public IdentityKey(String userId, String channelId) {
            this.userId = userId;
            this.channelId = channelId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IdentityKey that)) return false;
            return Objects.equals(userId, that.userId) && Objects.equals(channelId, that.channelId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, channelId);
        }
    }
}
