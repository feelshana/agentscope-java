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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** A reviewable semantic difference extracted from one knowledge document analysis task. */
@Entity
@Table(
        name = "dataagent_doc_enhance_proposal",
        indexes = {
            @Index(name = "ix_doc_enhance_proposal_task", columnList = "task_id"),
            @Index(name = "ix_doc_enhance_proposal_owner_group", columnList = "owner_id,group_id"),
            @Index(name = "ix_doc_enhance_proposal_group_status", columnList = "group_id,status")
        })
public class DocEnhanceProposalEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ADOPTED = "ADOPTED";
    public static final String STATUS_IGNORED = "IGNORED";

    @Id
    @Column(name = "proposal_id", length = 64, nullable = false)
    private String id;

    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "proposal_type", length = 24, nullable = false)
    private String proposalType;

    @Column(name = "classification", length = 16, nullable = false)
    private String classification;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Column(name = "payload_json", length = 12000, nullable = false)
    private String payloadJson = "{}";

    @Column(name = "source_quote", length = 500)
    private String sourceQuote;

    @Column(name = "confidence", length = 8, nullable = false)
    private String confidence = "MEDIUM";

    @Column(name = "fingerprint", length = 64, nullable = false)
    private String fingerprint;

    @Column(name = "status", length = 16, nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "decision_error", length = 1000)
    private String decisionError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    public DocEnhanceProposalEntity() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getProposalType() {
        return proposalType;
    }

    public void setProposalType(String proposalType) {
        this.proposalType = proposalType;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getSourceQuote() {
        return sourceQuote;
    }

    public void setSourceQuote(String sourceQuote) {
        this.sourceQuote = sourceQuote;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDecisionError() {
        return decisionError;
    }

    public void setDecisionError(String decisionError) {
        this.decisionError = decisionError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}
