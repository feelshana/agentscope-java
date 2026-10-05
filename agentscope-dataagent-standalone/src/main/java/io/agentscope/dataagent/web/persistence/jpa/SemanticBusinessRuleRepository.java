/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.dataagent.web.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for tenant-scoped semantic business rules. */
public interface SemanticBusinessRuleRepository
        extends JpaRepository<SemanticBusinessRuleEntity, String> {

    List<SemanticBusinessRuleEntity> findByOwnerIdAndGroupIdOrderByCreatedAtAsc(
            String ownerId, String groupId);

    List<SemanticBusinessRuleEntity> findByOwnerIdAndGroupIdInOrderByCreatedAtAsc(
            String ownerId, List<String> groupIds);

    Optional<SemanticBusinessRuleEntity> findByOwnerIdAndGroupIdAndName(
            String ownerId, String groupId, String name);
}
