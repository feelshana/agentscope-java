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

import io.agentscope.dataagent.semantic.builder.SemanticModelBuilder;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.service.SemanticModelService;
import io.agentscope.dataagent.web.persistence.jpa.SemanticModelEntity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 语义模型 REST API：上传 ontology.json、获取图谱数据、schema 描述、导出。
 */
@RestController
@RequestMapping("/api/semantic-model")
public class SemanticModelController {

    private static final Logger log = LoggerFactory.getLogger(SemanticModelController.class);

    private final SemanticModelService semanticModelService;
    private final SemanticModelBuilder semanticModelBuilder;

    public SemanticModelController(
            SemanticModelService semanticModelService, SemanticModelBuilder semanticModelBuilder) {
        this.semanticModelService = semanticModelService;
        this.semanticModelBuilder = semanticModelBuilder;
    }

    public record SemanticModelVO(
            String id,
            String name,
            String groupId,
            String origin,
            String mdlHash,
            int modelCount,
            int relationshipCount,
            int cubeCount,
            String createdAt,
            String updatedAt) {}

    /**
     * 上传 ontology.json（multipart/form-data）。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<SemanticModelVO> upload(
            @RequestPart("file") FilePart file,
            @RequestParam("groupId") String groupId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        requireJsonFileName(file.filename());
        return toBytes(file)
                .flatMap(
                        data ->
                                Mono.fromCallable(
                                                () -> {
                                                    String jsonText =
                                                            new String(
                                                                    data, StandardCharsets.UTF_8);
                                                    SemanticModelEntity entity =
                                                            semanticModelService.uploadModel(
                                                                    userId, groupId, jsonText);
                                                    return toVO(entity);
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnError(e -> log.warn("语义模型上传失败 for {}", userId, e))
                .onErrorMap(
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, e.getMessage(), e));
    }

    public record AutoGenerateRequest(List<String> tableNames) {}

    /**
     * 上传 instructions.md（业务规则 + 数据限制）。
     */
    @PostMapping(value = "/instructions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, String>> uploadInstructions(
            @RequestPart("file") FilePart file,
            @RequestParam("groupId") String groupId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        String filename = file.filename();
        if (!filename.endsWith(".md") && !filename.endsWith(".txt")) {
            return Mono.error(
                    new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "仅支持 .md 或 .txt 格式的 instructions 文件"));
        }
        return toBytes(file)
                .flatMap(
                        data ->
                                Mono.fromCallable(
                                                () -> {
                                                    String text =
                                                            new String(
                                                                    data, StandardCharsets.UTF_8);
                                                    semanticModelService.saveInstructions(
                                                            userId, groupId, text);
                                                    return Map.of(
                                                            "status",
                                                            "success",
                                                            "message",
                                                            "instructions 已保存");
                                                })
                                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorMap(
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, e.getMessage(), e));
    }

    /**
     * 获取 instructions 文本。
     */
    @GetMapping("/{groupId}/instructions")
    public Mono<Map<String, Object>> getInstructions(
            @PathVariable String groupId, Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            String text = semanticModelService.getInstructionsText(groupId);
                            return Map.<String, Object>of(
                                    "hasInstructions",
                                    text != null,
                                    "instructionsText",
                                    text != null ? text : "");
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 根据已选数据表自动建模。
     */
    @PostMapping("/auto-generate")
    public Mono<SemanticModelVO> autoGenerate(
            @RequestBody AutoGenerateRequest request,
            @RequestParam("groupId") String groupId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            SemanticModel model =
                                    semanticModelBuilder.build(
                                            userId, groupId, request.tableNames());
                            SemanticModelEntity entity =
                                    semanticModelService.saveAutoGenerated(userId, groupId, model);
                            return toVO(entity);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, e.getMessage(), e));
    }

    /**
     * 获取语义模型图谱数据（nodes + edges），供前端 SemanticGraphView 渲染。
     */
    @GetMapping("/{groupId}/graph")
    public Mono<Map<String, Object>> getGraph(@PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> semanticModelService.getGraphData(userId, groupId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取本体目录形状数据（objects/relationships/metrics/rules/limitations），
     * 供对象目录视图以 legacy 本体契约消费语义模型；无模型时 404。
     */
    @GetMapping("/{groupId}/catalog")
    public Mono<ResponseEntity<Map<String, Object>>> getCatalog(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                semanticModelService
                                        .getCatalogData(userId, groupId)
                                        .<ResponseEntity<Map<String, Object>>>map(
                                                ResponseEntity::ok)
                                        .orElseGet(
                                                () ->
                                                        ResponseEntity.status(HttpStatus.NOT_FOUND)
                                                                .body(
                                                                        Map.of(
                                                                                "error",
                                                                                "No semantic model"
                                                                                    + " for group "
                                                                                        + groupId))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取语义模型的 schema 描述文本（用于注入 LLM 上下文）。
     */
    @GetMapping("/{groupId}/schema-description")
    public Mono<String> getSchemaDescription(@PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            Optional<SemanticModel> model =
                                    semanticModelService.getModel(userId, groupId);
                            if (model.isEmpty()) {
                                return "未找到语义模型";
                            }
                            return semanticModelService.describeSchema(model.get());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取语义模型原始 JSON（用于导出/编辑）。
     */
    @GetMapping("/{groupId}/export")
    public Mono<Map<String, Object>> exportModel(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            Optional<SemanticModel> model =
                                    semanticModelService.getModel(userId, groupId);
                            if (model.isEmpty()) {
                                return Map.<String, Object>of("error", "未找到语义模型");
                            }
                            Map<String, Object> result = new java.util.LinkedHashMap<>();
                            result.put("model", model.get());
                            return result;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 删除语义模型。
     */
    @DeleteMapping("/{modelId}")
    public Mono<Void> delete(@PathVariable String modelId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> semanticModelService.delete(userId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // ---- 辅助方法 ----

    private SemanticModelVO toVO(SemanticModelEntity entity) {
        // 解析 JSON 获取统计信息
        int modelCount = 0;
        int relCount = 0;
        int cubeCount = 0;
        try {
            SemanticModel model =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(entity.getMdlJson(), SemanticModel.class);
            modelCount = model.getModels() != null ? model.getModels().size() : 0;
            relCount = model.getRelationships() != null ? model.getRelationships().size() : 0;
            cubeCount = model.getCubes() != null ? model.getCubes().size() : 0;
        } catch (Exception e) {
            log.warn("解析语义模型统计信息失败", e);
        }

        return new SemanticModelVO(
                entity.getId(),
                entity.getName(),
                entity.getGroupId(),
                entity.getOrigin(),
                entity.getMdlHash(),
                modelCount,
                relCount,
                cubeCount,
                entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null,
                entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
    }

    /** 只允许上传 .json 文件。 */
    private void requireJsonFileName(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        if (!n.endsWith(".json")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported file type: " + fileName + " (expected .json)");
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
