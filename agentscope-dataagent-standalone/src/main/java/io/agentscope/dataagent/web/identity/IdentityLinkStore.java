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
package io.agentscope.dataagent.web.identity;

import io.agentscope.dataagent.web.persistence.jpa.IdentityLinkEntity;
import io.agentscope.dataagent.web.persistence.jpa.IdentityLinkRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistent registry that maps a claw {@code userId} to that user's identity on other channels
 * (Slack, Discord, GitHub, ...). Backed by the {@code identity_link} MySQL table.
 *
 * <p>In agentscope-dataagent the store is the configuration source — channel adapters consult it to
 * map incoming events to a known {@code userId} and to deliver outbound messages back through the
 * matching channel.
 */
public class IdentityLinkStore {

    private static final Logger log = LoggerFactory.getLogger(IdentityLinkStore.class);

    private final IdentityLinkRepository repository;

    public IdentityLinkStore(IdentityLinkRepository repository) {
        this.repository = repository;
    }

    // -----------------------------------------------------------------
    //  Query
    // -----------------------------------------------------------------

    /** Returns a snapshot of the entire user → channel → externalId map. */
    public Map<String, Map<String, String>> snapshot() {
        List<IdentityLinkEntity> all = repository.findAll();
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (IdentityLinkEntity e : all) {
            out.computeIfAbsent(e.getUserId(), k -> new LinkedHashMap<>())
                    .put(e.getChannelId(), e.getExternalId());
        }
        return out;
    }

    /** Returns the user's channel links, or an empty map when none exist. */
    public Map<String, String> linksFor(String userId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (IdentityLinkEntity e : repository.findByUserId(userId)) {
            result.put(e.getChannelId(), e.getExternalId());
        }
        return result;
    }

    /** Resolves the external identity for {@code userId} on {@code channelId}. */
    public Optional<String> externalIdFor(String userId, String channelId) {
        return repository
                .findByUserIdAndChannelId(userId, channelId)
                .map(IdentityLinkEntity::getExternalId);
    }

    /**
     * Reverse lookup: returns the claw {@code userId} whose link for {@code channelId} matches
     * {@code externalId}. Useful for inbound delivery to translate a channel-native id into the
     * canonical user id.
     */
    public Optional<String> userIdByExternal(String channelId, String externalId) {
        if (channelId == null || externalId == null) return Optional.empty();
        return repository
                .findByChannelIdAndExternalId(channelId, externalId)
                .map(IdentityLinkEntity::getUserId);
    }

    // -----------------------------------------------------------------
    //  Mutations
    // -----------------------------------------------------------------

    /**
     * Records that {@code userId} is known as {@code externalId} on {@code channelId}. Replaces
     * any prior value for the same (user, channel) pair.
     */
    @Transactional
    public void link(String userId, String channelId, String externalId) {
        if (userId == null || channelId == null || externalId == null) {
            throw new IllegalArgumentException("userId, channelId, externalId are required");
        }
        IdentityLinkEntity entity =
                repository
                        .findByUserIdAndChannelId(userId, channelId)
                        .orElse(new IdentityLinkEntity(userId, channelId, null));
        entity.setExternalId(externalId);
        repository.save(entity);
        log.info(
                "Identity link added: user={}, channel={}, externalId={}",
                userId,
                channelId,
                externalId);
    }

    /** Removes the link for {@code (userId, channelId)} if present. */
    @Transactional
    public boolean unlink(String userId, String channelId) {
        return repository
                .findByUserIdAndChannelId(userId, channelId)
                .map(
                        existing -> {
                            repository.delete(existing);
                            return true;
                        })
                .orElse(false);
    }
}
