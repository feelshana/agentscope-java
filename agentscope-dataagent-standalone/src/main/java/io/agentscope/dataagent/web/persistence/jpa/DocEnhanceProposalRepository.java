/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.dataagent.web.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link DocEnhanceProposalEntity}. */
public interface DocEnhanceProposalRepository
        extends JpaRepository<DocEnhanceProposalEntity, String> {

    List<DocEnhanceProposalEntity> findByTaskIdOrderByCreatedAtAsc(String taskId);

    boolean existsByOwnerIdAndGroupIdAndFingerprintAndStatus(
            String ownerId, String groupId, String fingerprint, String status);
}
