package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A versioned, encrypted global configuration aggregate. */
@Entity
@Table(name = "dataagent_model_service_config")
public class ModelServiceConfigurationEntity {
    @Id
    @Column(name = "config_id", length = 32, nullable = false)
    private String id;

    @Lob
    @Column(name = "encrypted_payload", columnDefinition = "LONGTEXT", nullable = false)
    private String encryptedPayload;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public ModelServiceConfigurationEntity() {}

    public ModelServiceConfigurationEntity(String id) {
        this.id = id;
    }

    public String getEncryptedPayload() {
        return encryptedPayload;
    }

    public void setEncryptedPayload(String value) {
        encryptedPayload = value;
    }
}
