/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.dataagent.web.persistence.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link DocEnhanceTaskEntity}. */
public interface DocEnhanceTaskRepository extends JpaRepository<DocEnhanceTaskEntity, String> {

    Optional<DocEnhanceTaskEntity> findFirstByOwnerIdAndGroupIdOrderByCreatedAtDesc(
            String ownerId, String groupId);

    boolean existsByOwnerIdAndGroupIdAndStatus(String ownerId, String groupId, String status);
}
