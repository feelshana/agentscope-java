package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExternalDataSourceGrantRepository
        extends JpaRepository<ExternalDataSourceGrantEntity, String> {
    Optional<ExternalDataSourceGrantEntity> findByDatasourceIdAndUserId(
            String datasourceId, String userId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query(
            "select g from ExternalDataSourceGrantEntity g where g.datasourceId = :source and"
                    + " g.userId = :user")
    Optional<ExternalDataSourceGrantEntity> findCurrentGrant(
            @Param("source") String source, @Param("user") String user);

    List<ExternalDataSourceGrantEntity> findByUserId(String userId);

    List<ExternalDataSourceGrantEntity> findByDatasourceIdAndUserIdIn(
            String datasourceId, List<String> userIds);

    Page<ExternalDataSourceGrantEntity> findByDatasourceId(String datasourceId, Pageable pageable);

    void deleteByDatasourceId(String datasourceId);
}
