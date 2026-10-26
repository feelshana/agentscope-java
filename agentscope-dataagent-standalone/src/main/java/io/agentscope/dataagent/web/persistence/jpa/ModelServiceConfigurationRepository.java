package io.agentscope.dataagent.web.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Stores model configuration independently of per-user agents and workspaces. */
public interface ModelServiceConfigurationRepository
        extends JpaRepository<ModelServiceConfigurationEntity, String> {}
