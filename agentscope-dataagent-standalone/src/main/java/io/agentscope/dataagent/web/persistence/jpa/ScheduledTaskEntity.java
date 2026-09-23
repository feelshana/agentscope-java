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
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Scheduled task entry (TC "例行任务" analogue): a recurring prompt that the agent executes on a
 * fixed schedule, optionally delivering results via email.
 */
@Entity
@Table(name = "dataagent_scheduled_task")
public class ScheduledTaskEntity {

    @Id
    @Column(name = "task_id", length = 64, nullable = false)
    private String id;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "prompt", length = 5000, nullable = false)
    private String prompt;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "knowledge_base_id", length = 64)
    private String knowledgeBaseId;

    @Column(name = "agent_id", length = 64, nullable = false)
    private String agentId;

    /** Schedule frequency: daily, weekly, monthly. */
    @Column(name = "schedule_frequency", length = 20, nullable = false)
    private String scheduleFrequency = "daily";

    /** Schedule time in HH:mm format. */
    @Column(name = "schedule_time", length = 10, nullable = false)
    private String scheduleTime = "00:00";

    /** Effective from date (ISO format) or "permanent". */
    @Column(name = "effective_from", length = 50)
    private String effectiveFrom = "permanent";

    /** Task status: active, paused. */
    @Column(name = "status", length = 20, nullable = false)
    private String status = "active";

    @Column(name = "run_count")
    private int runCount = 0;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public ScheduledTaskEntity() {}

    public ScheduledTaskEntity(
            String id,
            String title,
            String prompt,
            String email,
            String knowledgeBaseId,
            String agentId,
            String scheduleFrequency,
            String scheduleTime,
            String effectiveFrom,
            String createdBy) {
        this.id = id;
        this.title = title;
        this.prompt = prompt;
        this.email = email;
        this.knowledgeBaseId = knowledgeBaseId;
        this.agentId = agentId;
        this.scheduleFrequency = scheduleFrequency != null ? scheduleFrequency : "daily";
        this.scheduleTime = scheduleTime != null ? scheduleTime : "00:00";
        this.effectiveFrom = effectiveFrom != null ? effectiveFrom : "permanent";
        this.status = "active";
        this.runCount = 0;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(String knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getScheduleFrequency() {
        return scheduleFrequency;
    }

    public void setScheduleFrequency(String scheduleFrequency) {
        this.scheduleFrequency = scheduleFrequency;
    }

    public String getScheduleTime() {
        return scheduleTime;
    }

    public void setScheduleTime(String scheduleTime) {
        this.scheduleTime = scheduleTime;
    }

    public String getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(String effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getRunCount() {
        return runCount;
    }

    public void setRunCount(int runCount) {
        this.runCount = runCount;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
