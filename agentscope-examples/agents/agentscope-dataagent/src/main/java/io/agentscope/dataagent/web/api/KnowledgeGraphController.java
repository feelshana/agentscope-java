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

import io.agentscope.dataagent.dataset.KnowledgeGraphService;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphBuildTaskEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphBuildUnitEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphEntityEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphRelationEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * TC-style semantic knowledge-graph endpoints for a knowledge base: trigger an async LLM GraphRAG
 * build, poll per-document build status, and fetch the extracted entity/relation graph.
 */
@RestController
@RequestMapping("/api/dataset-groups/{id}/kg")
public class KnowledgeGraphController {

    public record KgBuildTaskDto(String id, String status, String triggeredAt) {}

    public record KgUnitDto(String unitName, String unitType, String status, String errorMsg) {}

    public record KgStatsDto(
            long entityCount,
            long relationCount,
            int docCount,
            int processed,
            int failed,
            int pending,
            int running) {}

    public record KgStatusDto(
            String taskStatus, double progress, KgStatsDto stats, List<KgUnitDto> units) {}

    public record KgNodeDto(String id, String text, String label, String attributes) {}

    public record KgEdgeDto(String id, String source, String target, String label, String text) {}

    public record KgGraphDto(
            List<KgNodeDto> nodes, List<KgEdgeDto> edges, Map<String, Integer> typeDist) {}

    private final KnowledgeGraphService service;

    public KnowledgeGraphController(KnowledgeGraphService service) {
        this.service = service;
    }

    public record KgBuildRequest(Boolean includeDoc, List<String> datasetIds) {}

    @PostMapping("/build")
    public Mono<KgBuildTaskDto> build(
            @PathVariable String id,
            @RequestBody(required = false) KgBuildRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        boolean includeDoc = req == null || req.includeDoc() == null || req.includeDoc();
        List<String> datasetIds = req == null ? null : req.datasetIds();
        return Mono.fromCallable(
                        () -> {
                            KnowledgeGraphBuildTaskEntity task =
                                    service.triggerBuild(userId, id, includeDoc, datasetIds);
                            return new KgBuildTaskDto(
                                    task.getId(),
                                    task.getStatus(),
                                    task.getTriggeredAt() == null
                                            ? null
                                            : task.getTriggeredAt().toString());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/status")
    public Mono<KgStatusDto> status(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            List<KnowledgeGraphBuildUnitEntity> units = service.units(userId, id);
                            KnowledgeGraphBuildTaskEntity task =
                                    service.latestTask(userId, id).orElse(null);
                            int processed = 0;
                            int failed = 0;
                            int pending = 0;
                            int running = 0;
                            for (KnowledgeGraphBuildUnitEntity u : units) {
                                switch (u.getStatus()) {
                                    case KnowledgeGraphBuildUnitEntity.STATUS_SUCCEEDED ->
                                            processed++;
                                    case KnowledgeGraphBuildUnitEntity.STATUS_FAILED -> failed++;
                                    case KnowledgeGraphBuildUnitEntity.STATUS_RUNNING -> running++;
                                    default -> pending++;
                                }
                            }
                            int total = units.size();
                            double progress =
                                    total == 0 ? 0 : (double) (processed + failed) / total;
                            KgStatsDto stats =
                                    new KgStatsDto(
                                            service.entities(userId, id).size(),
                                            service.relations(userId, id).size(),
                                            total,
                                            processed,
                                            failed,
                                            pending,
                                            running);
                            return new KgStatusDto(
                                    task == null ? null : task.getStatus(),
                                    progress,
                                    stats,
                                    units.stream()
                                            .map(
                                                    u ->
                                                            new KgUnitDto(
                                                                    u.getUnitName(),
                                                                    u.getUnitType(),
                                                                    u.getStatus(),
                                                                    u.getErrorMsg()))
                                            .toList());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/graph")
    public Mono<KgGraphDto> graph(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            List<KnowledgeGraphEntityEntity> entities =
                                    service.entities(userId, id);
                            List<KnowledgeGraphRelationEntity> relations =
                                    service.relations(userId, id);
                            Map<String, Integer> typeDist = new LinkedHashMap<>();
                            List<KgNodeDto> nodes =
                                    entities.stream()
                                            .map(
                                                    e -> {
                                                        typeDist.merge(
                                                                e.getLabel(), 1, Integer::sum);
                                                        return new KgNodeDto(
                                                                e.getId(),
                                                                e.getText(),
                                                                e.getLabel(),
                                                                e.getAttributesJson());
                                                    })
                                            .toList();
                            List<KgEdgeDto> edges =
                                    relations.stream()
                                            .map(
                                                    r ->
                                                            new KgEdgeDto(
                                                                    r.getId(),
                                                                    r.getSourceEntityId(),
                                                                    r.getTargetEntityId(),
                                                                    r.getLabel(),
                                                                    r.getText()))
                                            .toList();
                            return new KgGraphDto(nodes, edges, typeDist);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
