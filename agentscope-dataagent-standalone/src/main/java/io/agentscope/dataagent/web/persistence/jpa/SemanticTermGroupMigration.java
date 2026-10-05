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

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One-shot startup migration for specs/026: terms created before group binding recorded their
 * knowledge base in the free-form {@code scope} label (specs/011 decision A). Rows whose scope is
 * a UUID therefore move that value into {@code group_id}; anything else (legacy "global" rows)
 * stays unbound and is simply never injected — the owner can re-create or delete it on the page.
 */
@Component
public class SemanticTermGroupMigration {

    private static final Logger log = LoggerFactory.getLogger(SemanticTermGroupMigration.class);

    private static final Pattern UUID_SHAPE =
            Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final SemanticTermRepository repository;

    public SemanticTermGroupMigration(SemanticTermRepository repository) {
        this.repository = repository;
    }

    /** Idempotent: only rows with a UUID-shaped scope and no group yet are touched. */
    @PostConstruct
    void migrate() {
        List<SemanticTermEntity> pending =
                repository.findAllByOrderByCreatedAtDesc().stream()
                        .filter(
                                t ->
                                        t.getGroupId() == null
                                                && t.getScope() != null
                                                && UUID_SHAPE
                                                        .matcher(t.getScope().trim())
                                                        .matches())
                        .toList();
        if (pending.isEmpty()) {
            return;
        }
        for (SemanticTermEntity t : pending) {
            t.setGroupId(t.getScope().trim());
            repository.save(t);
        }
        log.info(
                "SemanticTermGroupMigration: bound {} legacy term(s) to their knowledge base"
                        + " from the scope label (specs/026)",
                pending.size());
    }
}
