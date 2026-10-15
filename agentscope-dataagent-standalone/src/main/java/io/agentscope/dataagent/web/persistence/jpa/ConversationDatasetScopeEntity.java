package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Selected KBs belong to a user's conversation and survive restarts/revocation. */
@Entity
@Table(name = "dataagent_conversation_dataset_scope")
public class ConversationDatasetScopeEntity {
    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "session_key", length = 512, nullable = false)
    private String sessionKey;

    @Column(name = "groups_json", columnDefinition = "TEXT", nullable = false)
    private String groupsJson;

    @Column(name = "selection_json", columnDefinition = "TEXT")
    private String selectionJson;

    public ConversationDatasetScopeEntity() {}

    public ConversationDatasetScopeEntity(String userId, String sessionKey, String groupsJson) {
        id = key(userId, sessionKey);
        this.userId = userId;
        this.sessionKey = sessionKey;
        this.groupsJson = groupsJson;
    }

    public static String key(String userId, String sessionKey) {
        return UUID.nameUUIDFromBytes(
                        (userId + "\u0000" + sessionKey).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    public String getGroupsJson() {
        return groupsJson;
    }

    public String getId() {
        return id;
    }

    public String getSelectionJson() {
        return selectionJson;
    }

    public void setSelectionJson(String selectionJson) {
        this.selectionJson = selectionJson;
    }
}
