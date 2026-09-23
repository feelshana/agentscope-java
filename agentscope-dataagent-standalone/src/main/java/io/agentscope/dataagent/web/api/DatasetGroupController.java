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

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.GraphDto;
import io.agentscope.dataagent.dataset.KnowledgeGraphService;
import io.agentscope.dataagent.dataset.SchemaRelationInferrer;
import io.agentscope.dataagent.dataset.parser.DocxDescriptionExtractor;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Knowledge-base (KB) CRUD: named per-user containers grouping datasets plus one relationship
 * document per KB (the TC DataAgent "知识库" analogue).
 */
@RestController
@RequestMapping("/api/dataset-groups")
public class DatasetGroupController {

    private static final Logger log = LoggerFactory.getLogger(DatasetGroupController.class);
    private static final int MAX_KNOWLEDGE_CHARS = 20000;

    private final DatasetGroupService groupService;
    private final DatasetService datasetService;
    private final KnowledgeGraphService knowledgeGraphService;

    public DatasetGroupController(
            DatasetGroupService groupService,
            DatasetService datasetService,
            KnowledgeGraphService knowledgeGraphService) {
        this.groupService = groupService;
        this.datasetService = datasetService;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    public record GroupVO(
            String id, String name, String description, int datasetCount, String createdAt) {}

    public record CreateGroupRequest(String name, String description) {}

    public record GroupDetailVO(
            GroupVO group, List<DatasetController.DatasetVO> datasets, String knowledge) {}

    @PostMapping
    public Mono<GroupVO> create(@RequestBody CreateGroupRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                toVO(
                                        groupService.createGroup(
                                                userId, req.name(), req.description()),
                                        0))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @GetMapping
    public Mono<List<GroupVO>> list(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                groupService.listGroups(userId).stream()
                                        .map(
                                                g ->
                                                        toVO(
                                                                g,
                                                                (int)
                                                                        groupService.countDatasets(
                                                                                userId, g.getId())))
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @GetMapping("/{id}")
    public Mono<GroupDetailVO> detail(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            DatasetGroupEntity group = groupService.getGroup(userId, id);
                            var datasets = groupService.listDatasets(userId, id);
                            return new GroupDetailVO(
                                    toVO(group, datasets.size()),
                                    datasets.stream()
                                            .map(
                                                    d ->
                                                            DatasetController.toVOStatic(
                                                                    d,
                                                                    datasetService.readColumns(d)))
                                            .toList(),
                                    datasetService.knowledgeText(id));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> groupService.deleteGroup(userId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @PutMapping(value = "/{id}/knowledge", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, String>> uploadKnowledge(
            @PathVariable String id, @RequestPart("file") FilePart file, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return toBytes(file)
                .flatMap(
                        bytes ->
                                Mono.fromCallable(
                                                () -> {
                                                    groupService.getGroup(userId, id);
                                                    String text =
                                                            extractText(file.filename(), bytes);
                                                    datasetService.saveKnowledge(id, text);
                                                    try {
                                                        knowledgeGraphService.triggerBuild(
                                                                userId, id, true, null);
                                                    } catch (RuntimeException ignore) {
                                                        // already running or no model configured
                                                    }
                                                    return Map.of("content", text);
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnError(
                        e ->
                                log.warn(
                                        "KB knowledge upload failed for {}: {}",
                                        userId,
                                        e.getMessage(),
                                        e))
                .onErrorMap(this::toStatus);
    }

    @GetMapping("/{id}/knowledge")
    public Mono<Map<String, String>> getKnowledge(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, id);
                            Map<String, String> out = new java.util.HashMap<>();
                            out.put("content", datasetService.knowledgeText(id));
                            out.put("updatedAt", datasetService.knowledgeUpdatedAt(id));
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /**
     * Deterministic table-relationship graph for the KB "知识图谱" tab: live schema-inferred edges
     * merged with persisted structured relations (doc/inferred) so each edge carries an origin badge
     * and confidence. No LLM involved.
     */
    @GetMapping("/{id}/graph")
    public Mono<GraphDto> graph(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            GraphDto base =
                                    SchemaRelationInferrer.infer(
                                            groupService.listDatasets(userId, id),
                                            datasetService::readColumns);
                            return mergePersistedRelations(
                                    base, datasetService.relationsForGroup(id));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    private static GraphDto mergePersistedRelations(
            GraphDto base, List<DatasetRelationEntity> relations) {
        if (relations.isEmpty()) {
            return base;
        }
        Map<String, GraphDto.Edge> byPair = new LinkedHashMap<>();
        for (GraphDto.Edge e : base.edges()) {
            byPair.put(pairKey(e.source(), e.target()), e);
        }
        for (DatasetRelationEntity r : relations) {
            String label =
                    (r.getSourceColumn() != null && r.getTargetColumn() != null)
                            ? r.getSourceColumn() + " = " + r.getTargetColumn()
                            : r.getRelationType();
            GraphDto.Edge e =
                    new GraphDto.Edge(
                            r.getId(),
                            r.getSourceDatasetId(),
                            r.getTargetDatasetId(),
                            r.getRelationType(),
                            r.getConfidence(),
                            label,
                            r.getOrigin() == null ? "inferred" : r.getOrigin());
            String key = pairKey(e.source(), e.target());
            GraphDto.Edge existing = byPair.get(key);
            // Persisted (doc/inferred) relations win ties; otherwise keep higher confidence.
            if (existing == null || e.confidence() >= existing.confidence()) {
                byPair.put(key, e);
            }
        }
        return new GraphDto(base.nodes(), List.copyOf(byPair.values()));
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** Associates existing tables of an external data source into this KB as read-only datasets. */
    @PostMapping("/{id}/associate")
    public Mono<List<DatasetController.DatasetVO>> associate(
            @PathVariable String id, @RequestBody AssociateRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                datasetService
                                        .associateTables(
                                                userId,
                                                id,
                                                req.dataSourceId(),
                                                req.schema(),
                                                req.tables(),
                                                req.sampling() == null || req.sampling())
                                        .stream()
                                        .map(
                                                d ->
                                                        DatasetController.toVOStatic(
                                                                d, datasetService.readColumns(d)))
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    public record AssociateRequest(
            String dataSourceId, String schema, List<String> tables, Boolean sampling) {}

    private GroupVO toVO(DatasetGroupEntity g, int datasetCount) {
        return new GroupVO(
                g.getId(),
                g.getName(),
                g.getDescription(),
                datasetCount,
                g.getCreatedAt() == null ? null : g.getCreatedAt().toString());
    }

    private String extractText(String fileName, byte[] bytes) throws java.io.IOException {
        if (fileName != null && fileName.toLowerCase().endsWith(".docx")) {
            return DocxDescriptionExtractor.extract(
                    new ByteArrayInputStream(bytes), MAX_KNOWLEDGE_CHARS);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Mono<byte[]> toBytes(FilePart part) {
        return DataBufferUtils.join(part.content())
                .map(
                        db -> {
                            byte[] bytes = new byte[db.readableByteCount()];
                            db.read(bytes);
                            DataBufferUtils.release(db);
                            return bytes;
                        });
    }

    private Throwable toStatus(Throwable t) {
        if (t instanceof DatasetException de) {
            return new ResponseStatusException(
                    HttpStatus.valueOf(de.status()), de.getMessage(), de);
        }
        return t;
    }
}
