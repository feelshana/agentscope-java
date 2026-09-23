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
package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.web.persistence.jpa.ScheduledTaskEntity;
import io.agentscope.dataagent.web.persistence.jpa.ScheduledTaskRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Scheduled task CRUD (TC "例行任务" page backend). Tasks define a recurring prompt that the agent
 * executes on a fixed schedule, optionally delivering results via email.
 */
@RestController
@RequestMapping("/api/scheduled-tasks")
public class ScheduledTaskController {

    public record TaskVO(
            String id,
            String title,
            String prompt,
            String email,
            String knowledgeBaseId,
            String agentId,
            String scheduleFrequency,
            String scheduleTime,
            String effectiveFrom,
            String status,
            int runCount,
            String createdBy,
            String createdAt,
            String updatedAt) {}

    public record TaskRequest(
            String title,
            String prompt,
            String email,
            String knowledgeBaseId,
            String agentId,
            String scheduleFrequency,
            String scheduleTime,
            String effectiveFrom) {}

    private final ScheduledTaskRepository repository;

    public ScheduledTaskController(ScheduledTaskRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public Mono<List<TaskVO>> list() {
        return Mono.fromCallable(
                        () ->
                                repository.findAllByOrderByCreatedAtDesc().stream()
                                        .map(ScheduledTaskController::toVO)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<TaskVO> create(@RequestBody TaskRequest req) {
        return Mono.fromCallable(
                        () -> {
                            validate(req);
                            String createdBy = "当前用户";
                            return toVO(
                                    repository.save(
                                            new ScheduledTaskEntity(
                                                    UUID.randomUUID().toString(),
                                                    req.title().trim(),
                                                    req.prompt(),
                                                    req.email(),
                                                    req.knowledgeBaseId(),
                                                    req.agentId(),
                                                    req.scheduleFrequency(),
                                                    req.scheduleTime(),
                                                    req.effectiveFrom(),
                                                    createdBy)));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<TaskVO> update(@PathVariable String id, @RequestBody TaskRequest req) {
        return Mono.fromCallable(
                        () -> {
                            validate(req);
                            ScheduledTaskEntity e =
                                    repository
                                            .findById(id)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "task not found: " + id));
                            e.setTitle(req.title().trim());
                            e.setPrompt(req.prompt());
                            e.setEmail(req.email());
                            e.setKnowledgeBaseId(req.knowledgeBaseId());
                            e.setAgentId(req.agentId());
                            e.setScheduleFrequency(req.scheduleFrequency());
                            e.setScheduleTime(req.scheduleTime());
                            e.setEffectiveFrom(req.effectiveFrom());
                            e.setUpdatedAt(java.time.Instant.now());
                            return toVO(repository.save(e));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}/status")
    public Mono<TaskVO> updateStatus(@PathVariable String id, @RequestBody StatusRequest req) {
        return Mono.fromCallable(
                        () -> {
                            ScheduledTaskEntity e =
                                    repository
                                            .findById(id)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "task not found: " + id));
                            if (!"active".equals(req.status()) && !"paused".equals(req.status())) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "status must be active or paused");
                            }
                            e.setStatus(req.status());
                            e.setUpdatedAt(java.time.Instant.now());
                            return toVO(repository.save(e));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable String id) {
        return Mono.fromRunnable(
                        () -> {
                            if (!repository.existsById(id)) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "task not found: " + id);
                            }
                            repository.deleteById(id);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.empty());
    }

    public record StatusRequest(String status) {}

    private static void validate(TaskRequest req) {
        if (req.title() == null || req.title().isBlank() || req.title().trim().length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must be 1-200 chars");
        }
        if (req.prompt() == null || req.prompt().isBlank() || req.prompt().length() > 5000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "prompt must be 1-5000 chars");
        }
        if (req.agentId() == null || req.agentId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId is required");
        }
        if (req.scheduleFrequency() == null || req.scheduleFrequency().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "scheduleFrequency is required");
        }
        if (req.scheduleTime() == null || req.scheduleTime().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduleTime is required");
        }
    }

    private static TaskVO toVO(ScheduledTaskEntity e) {
        return new TaskVO(
                e.getId(),
                e.getTitle(),
                e.getPrompt(),
                e.getEmail(),
                e.getKnowledgeBaseId(),
                e.getAgentId(),
                e.getScheduleFrequency(),
                e.getScheduleTime(),
                e.getEffectiveFrom(),
                e.getStatus(),
                e.getRunCount(),
                e.getCreatedBy(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null,
                e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null);
    }
}
