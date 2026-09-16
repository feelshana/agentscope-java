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

import io.agentscope.dataagent.ontology.OntologyService;
import io.agentscope.dataagent.ontology.model.OntologyModel;
import io.agentscope.dataagent.web.persistence.jpa.OntologyEntity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 本体模型 REST API：上传 model.yaml、自动生成、获取图谱数据和格式化目录。
 */
@RestController
@RequestMapping("/api/ontology")
public class OntologyController {

    private static final Logger log = LoggerFactory.getLogger(OntologyController.class);

    private final OntologyService ontologyService;

    public OntologyController(OntologyService ontologyService) {
        this.ontologyService = ontologyService;
    }

    public record OntologyVO(
            String id,
            String name,
            String version,
            String groupId,
            String origin,
            String createdAt,
            String updatedAt) {}

    public record AutoGenerateRequest(List<String> tableNames, String businessDoc) {}

    /**
     * 上传 model.yaml（multipart/form-data）。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<OntologyVO> upload(
            @RequestPart("file") FilePart file,
            @RequestParam("groupId") String groupId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        requireYamlFileName(file.filename());
        return toBytes(file)
                .flatMap(
                        data ->
                                Mono.fromCallable(
                                                () -> {
                                                    String yamlText =
                                                            new String(
                                                                    data, StandardCharsets.UTF_8);
                                                    OntologyEntity entity =
                                                            ontologyService.uploadModel(
                                                                    userId, groupId, yamlText);
                                                    return toVO(entity);
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnError(e -> log.warn("本体上传失败 for {}", userId, e))
                .onErrorMap(
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, e.getMessage(), e));
    }

    /**
     * 根据已选表 + 可选业务文档自动生成本体。
     */
    @PostMapping("/auto-generate")
    public Mono<OntologyVO> autoGenerate(
            @RequestBody AutoGenerateRequest request,
            @RequestParam("groupId") String groupId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            OntologyEntity entity =
                                    ontologyService.autoGenerateModel(
                                            userId,
                                            groupId,
                                            request.tableNames(),
                                            request.businessDoc());
                            return toVO(entity);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, e.getMessage(), e));
    }

    /**
     * 获取图谱数据（nodes + edges）。
     */
    @GetMapping("/{groupId}/graph")
    public Mono<Map<String, Object>> graph(@PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> ontologyService.getOntologyGraph(userId, groupId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取涉及指定对象的子图。
     */
    @GetMapping("/{groupId}/sub-graph")
    public Mono<Map<String, Object>> subGraph(
            @PathVariable String groupId,
            @RequestParam("objects") List<String> objects,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> ontologyService.getSubGraph(userId, groupId, objects))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取格式化的本体目录文本。
     */
    @GetMapping("/{groupId}/catalog")
    public Mono<String> catalog(@PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> ontologyService.formatOntologyCatalog(userId, groupId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 删除本体。
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> ontologyService.delete(userId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    /**
     * 获取本体模型的完整结构化数据（对象、属性、关系、指标），用于前端对象目录视图。
     */
    @GetMapping("/{groupId}/model")
    public Mono<ResponseEntity<Map<String, Object>>> model(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                ontologyService
                                        .getOntologyWithOrigin(userId, groupId)
                                        .map(
                                                m ->
                                                        ResponseEntity.ok(
                                                                buildModelResponse(
                                                                        m.model(),
                                                                        groupId,
                                                                        m.origin())))
                                        .orElse(
                                                ResponseEntity.status(HttpStatus.NOT_FOUND)
                                                        .body(
                                                                Map.of(
                                                                        "error",
                                                                        "No ontology for group "
                                                                                + groupId))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 下载本体模型 YAML 文件。
     */
    @GetMapping(value = "/{groupId}/yaml", produces = "application/x-yaml")
    public Mono<ResponseEntity<byte[]>> downloadYaml(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                ontologyService
                                        .getOntology(userId, groupId)
                                        .map(
                                                m -> {
                                                    String yaml = ontologyService.toYaml(m);
                                                    byte[] bytes =
                                                            yaml.getBytes(StandardCharsets.UTF_8);
                                                    HttpHeaders headers = new HttpHeaders();
                                                    headers.setContentType(
                                                            MediaType.parseMediaType(
                                                                    "application/x-yaml"));
                                                    headers.setContentDisposition(
                                                            org.springframework.http
                                                                    .ContentDisposition.attachment()
                                                                    .filename(
                                                                            groupId
                                                                                    + "-ontology.yaml")
                                                                    .build());
                                                    return new ResponseEntity<>(
                                                            bytes, headers, HttpStatus.OK);
                                                })
                                        .orElse(
                                                ResponseEntity.status(HttpStatus.NOT_FOUND)
                                                        .body(new byte[0])))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 上传编辑后的 YAML 本体文件（更新元数据：label/description/relationships），
     * 不重建物理表。如果 derived SQL 变更，提示用户重新导入。
     */
    @PutMapping(value = "/{groupId}/ontology", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<OntologyVO> uploadOntology(
            @PathVariable String groupId, @RequestPart("file") FilePart file, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        requireYamlFileName(file.filename());
        return toBytes(file)
                .flatMap(
                        data ->
                                Mono.fromCallable(
                                                () -> {
                                                    String yamlText =
                                                            new String(
                                                                    data, StandardCharsets.UTF_8);
                                                    OntologyEntity entity =
                                                            ontologyService.uploadModel(
                                                                    userId, groupId, yamlText);
                                                    return toVO(entity);
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnError(e -> log.warn("本体 YAML 上传失败 for {}", userId, e))
                .onErrorMap(
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, e.getMessage(), e));
    }

    private Map<String, Object> buildModelResponse(
            OntologyModel model, String groupId, String origin) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("groupId", groupId);
        out.put("name", model.getName());
        out.put("version", model.getVersion());
        out.put("origin", origin);
        out.put("objects", model.getObjects());
        out.put("relationships", model.getRelationships());
        out.put("metrics", model.getMetrics());
        out.put("rules", model.getRules());
        out.put("limitations", model.getLimitations());
        return out;
    }

    private OntologyVO toVO(OntologyEntity entity) {
        return new OntologyVO(
                entity.getId(),
                entity.getName(),
                entity.getVersion(),
                entity.getGroupId(),
                entity.getOrigin(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
    }

    /** 本体文件只支持上传 YAML：校验扩展名，非法扩展名直接返回 400。 */
    private void requireYamlFileName(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        if (!n.endsWith(".yaml") && !n.endsWith(".yml")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported file type: " + fileName + " (expected .yaml / .yml)");
        }
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
}
