package io.agentscope.dataagent.web.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseGrantRepository
        extends JpaRepository<KnowledgeBaseGrantEntity, String> {
    Optional<KnowledgeBaseGrantEntity> findByGroupIdAndUserId(String groupId, String userId);

    List<KnowledgeBaseGrantEntity> findByGroupIdOrderByUpdatedAtDesc(String groupId);

    org.springframework.data.domain.Page<KnowledgeBaseGrantEntity> findByGroupId(
            String groupId, org.springframework.data.domain.Pageable pageable);

    List<KnowledgeBaseGrantEntity> findByUserIdAndGroupIdIn(String userId, List<String> groupIds);

    List<KnowledgeBaseGrantEntity> findByGroupIdAndUserIdIn(String groupId, List<String> userIds);
}
