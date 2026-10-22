package io.agentscope.dataagent.web.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FactQueryWindowRepository extends JpaRepository<FactQueryWindowEntity, String> {
    List<FactQueryWindowEntity> findByGroupId(String groupId);

    Optional<FactQueryWindowEntity> findByGroupIdAndDatasetId(String groupId, String datasetId);
}
