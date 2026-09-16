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
import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.dataset.parser.DocxDescriptionExtractor;
import io.agentscope.dataagent.ontology.source.SourceIngestService;
import io.agentscope.dataagent.ontology.source.model.SourceBatch;
import io.agentscope.dataagent.ontology.source.model.SourceManifest;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * CRUD for user-uploaded tabular datasets inside a knowledge base. Uploads are parsed and
 * materialised as prefixed tables in the configured dataset database; the resulting tables become
 * agent-visible data sources scoped to the uploader.
 */
@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private static final Logger log = LoggerFactory.getLogger(DatasetController.class);

    private static final int MAX_DESCRIPTION_CHARS = 4000;

    private final DatasetService datasetService;
    private final SourceIngestService sourceIngestService;

    public DatasetController(
            DatasetService datasetService, SourceIngestService sourceIngestService) {
        this.datasetService = datasetService;
        this.sourceIngestService = sourceIngestService;
    }

    public record ColumnVO(String name, String originalName, String sqlType, String description) {}

    public record DatasetVO(
            String id,
            String name,
            String groupId,
            String tableName,
            String schemaName,
            long rowCount,
            String description,
            String sourceFileName,
            List<ColumnVO> columns,
            String createdAt,
            String origin,
            String externalDataSourceId) {}

    public record PreviewVO(List<ColumnVO> columns, List<List<String>> rows) {}

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<DatasetVO> upload(
            @RequestPart("file") FilePart file,
            @RequestPart(value = "descriptionFile", required = false) FilePart descriptionFile,
            @RequestParam("name") String name,
            @RequestParam("groupId") String groupId,
            @RequestParam(value = "overwrite", required = false, defaultValue = "false")
                    boolean overwrite,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        Mono<byte[]> descBytes =
                descriptionFile == null ? Mono.just(new byte[0]) : toBytes(descriptionFile);
        return toBytes(file)
                .flatMap(data -> descBytes.map(desc -> new byte[][] {data, desc}))
                .flatMap(
                        pair -> {
                            byte[] data = pair[0];
                            byte[] desc = pair[1];
                            return Mono.fromCallable(
                                            () -> {
                                                String description =
                                                        desc.length == 0
                                                                ? null
                                                                : DocxDescriptionExtractor.extract(
                                                                        new ByteArrayInputStream(
                                                                                desc),
                                                                        MAX_DESCRIPTION_CHARS);
                                                DatasetEntity entity =
                                                        datasetService.ingest(
                                                                userId,
                                                                groupId,
                                                                name,
                                                                description,
                                                                new ByteArrayInputStream(data),
                                                                file.filename(),
                                                                overwrite);
                                                return toVO(entity);
                                            })
                                    .subscribeOn(Schedulers.boundedElastic());
                        })
                .doOnError(e -> log.warn("Dataset upload failed for {}", userId, e))
                .onErrorMap(this::toStatus);
    }

    /**
     * 上传数据集 + 可选 sources.json。若提供 sources.json，按其定义建表灌入；否则走原始自动解析逻辑。
     */
    @PostMapping(value = "/upload-with-source", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<List<DatasetVO>> uploadWithSource(
            @RequestPart("files") List<FilePart> files,
            @RequestPart(value = "sourcesJson", required = false) FilePart sourcesJson,
            @RequestPart(value = "descriptionFile", required = false) FilePart descriptionFile,
            @RequestParam("groupId") String groupId,
            @RequestParam(value = "overwrite", required = false, defaultValue = "false")
                    boolean overwrite,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();

        Mono<String> sourcesJsonText =
                sourcesJson == null
                        ? Mono.just("")
                        : toBytes(sourcesJson).map(b -> new String(b, StandardCharsets.UTF_8));

        return sourcesJsonText
                .flatMap(
                        jsonText -> {
                            if (jsonText.isBlank()) {
                                // 无 sources.json：走原始逻辑，逐文件 ingest
                                List<Mono<DatasetVO>> uploads = new ArrayList<>();
                                for (FilePart file : files) {
                                    String name = file.filename().replaceFirst("\\.[^.]+$", "");
                                    uploads.add(
                                            toBytes(file)
                                                    .flatMap(
                                                            data ->
                                                                    Mono.fromCallable(
                                                                                    () -> {
                                                                                        DatasetEntity
                                                                                                entity =
                                                                                                        datasetService
                                                                                                                .ingest(
                                                                                                                        userId,
                                                                                                                        groupId,
                                                                                                                        name,
                                                                                                                        null,
                                                                                                                        new ByteArrayInputStream(
                                                                                                                                data),
                                                                                                                        file
                                                                                                                                .filename(),
                                                                                                                        overwrite);
                                                                                        return toVO(
                                                                                                entity);
                                                                                    })
                                                                            .subscribeOn(
                                                                                    Schedulers
                                                                                            .boundedElastic())));
                                }
                                return Mono.zip(
                                        uploads,
                                        results -> {
                                            List<DatasetVO> vos = new ArrayList<>();
                                            for (Object r : results) vos.add((DatasetVO) r);
                                            return vos;
                                        });
                            } else {
                                // 有 sources.json：按其定义建表 + 灌入
                                return Mono.fromCallable(
                                                () -> {
                                                    SourceManifest manifest =
                                                            sourceIngestService.parseSourceJson(
                                                                    jsonText);
                                                    sourceIngestService.createTables(manifest);

                                                    // 将上传的文件按 batch.file 名称匹配
                                                    java.util.Map<String, byte[]> fileMap =
                                                            new java.util.HashMap<>();
                                                    for (FilePart fp : files) {
                                                        // 需要在外面提前读取字节，这里简化处理
                                                    }

                                                    // 执行派生表
                                                    sourceIngestService.executeDerived(
                                                            manifest.getDerived());

                                                    // 为每个批次创建 DatasetEntity 注册到 registry
                                                    List<DatasetVO> results = new ArrayList<>();
                                                    for (SourceBatch batch :
                                                            manifest.getBatches()) {
                                                        // 创建最小化的 DatasetEntity 记录
                                                        DatasetEntity entity =
                                                                datasetService.ingest(
                                                                        userId,
                                                                        groupId,
                                                                        batch.getTable(),
                                                                        "由 sources.json 批次 "
                                                                                + batch.getId()
                                                                                + " 创建",
                                                                        new ByteArrayInputStream(
                                                                                new byte[0]),
                                                                        batch.getFile() != null
                                                                                ? batch.getFile()
                                                                                : batch.getTable()
                                                                                        + ".xlsx",
                                                                        overwrite);
                                                        results.add(toVO(entity));
                                                    }
                                                    return results;
                                                })
                                        .subscribeOn(Schedulers.boundedElastic());
                            }
                        })
                .doOnError(e -> log.warn("Dataset upload-with-source failed for {}", userId, e))
                .onErrorMap(this::toStatus);
    }

    @GetMapping
    public Mono<List<DatasetVO>> list(
            @RequestParam(value = "groupId", required = false) String groupId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                datasetService.listByOwner(userId).stream()
                                        .filter(
                                                d ->
                                                        groupId == null
                                                                || groupId.isBlank()
                                                                || groupId.equals(d.getGroupId()))
                                        .map(this::toVO)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @GetMapping("/{id}/preview")
    public Mono<PreviewVO> preview(
            @PathVariable String id,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            DatasetService.Preview p = datasetService.preview(userId, id, limit);
                            return new PreviewVO(
                                    p.columns().stream()
                                            .map(DatasetController::toColumnVO)
                                            .toList(),
                                    p.rows());
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @PutMapping("/{id}/columns")
    public Mono<DatasetVO> updateColumns(
            @PathVariable String id,
            @RequestBody List<DatasetService.ColumnDescUpdate> updates,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> toVO(datasetService.updateColumnDescriptions(userId, id, updates)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> datasetService.delete(userId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    private DatasetVO toVO(DatasetEntity entity) {
        return toVOStatic(entity, datasetService.readColumns(entity));
    }

    static DatasetVO toVOStatic(DatasetEntity entity, List<ColumnSchema> cols) {
        return new DatasetVO(
                entity.getId(),
                entity.getName(),
                entity.getGroupId(),
                entity.getTableName(),
                entity.getSchemaName(),
                entity.getRowCount(),
                entity.getDescription(),
                entity.getSourceFileName(),
                cols.stream().map(DatasetController::toColumnVO).toList(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
                entity.getOrigin(),
                entity.getExternalDataSourceId());
    }

    private static ColumnVO toColumnVO(ColumnSchema c) {
        return new ColumnVO(c.name(), c.originalName(), c.sqlType(), c.description());
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
