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
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.NodeList;
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
import reactor.core.publisher.Flux;
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

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
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
                                                                file.filename());
                                                return toVO(entity);
                                            })
                                    .subscribeOn(Schedulers.boundedElastic());
                        })
                .doOnError(e -> log.warn("Dataset upload failed for {}", userId, e))
                .onErrorMap(this::toStatus);
    }

    /**
     * Batch upload multiple files concurrently. Each file is processed in parallel on the
     * boundedElastic scheduler. Returns a list of results in the same order as the input files.
     */
    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<List<DatasetVO>> batchUpload(
            @RequestPart("files") List<FilePart> files,
            @RequestParam("groupId") String groupId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();

        return Flux.fromIterable(files)
                .flatMap(
                        file ->
                                toBytes(file)
                                        .flatMap(
                                                data ->
                                                        Mono.fromCallable(
                                                                        () -> {
                                                                            String name =
                                                                                    stripExtension(
                                                                                            file
                                                                                                    .filename());
                                                                            DatasetEntity entity =
                                                                                    datasetService
                                                                                            .ingest(
                                                                                                    userId,
                                                                                                    groupId,
                                                                                                    name,
                                                                                                    null,
                                                                                                    new ByteArrayInputStream(
                                                                                                            data),
                                                                                                    file
                                                                                                            .filename());
                                                                            return toVO(entity);
                                                                        })
                                                                .subscribeOn(
                                                                        Schedulers
                                                                                .boundedElastic()))
                                        .doOnSuccess(
                                                vo ->
                                                        log.info(
                                                                "Batch upload: '{}' succeeded for"
                                                                        + " user {}",
                                                                vo.name(),
                                                                userId))
                                        .doOnError(
                                                e ->
                                                        log.warn(
                                                                "Batch upload: '{}' failed for"
                                                                        + " user {}: {}",
                                                                file.filename(),
                                                                userId,
                                                                e.getMessage())),
                        4) // concurrency limit: 4 files in parallel
                .collectList()
                .doOnError(e -> log.warn("Batch upload failed for {}", userId, e))
                .onErrorMap(this::toStatus);
    }

    /**
     * List sheet names in an Excel file. Used by the frontend to let users pick which sheet to
     * import when a file contains multiple sheets.
     *
     * <p>Uses lightweight ZIP+XML parsing to extract sheet names from workbook.xml without loading
     * the entire workbook into memory via POI, which is significantly faster for large files.
     */
    @PostMapping(value = "/list-sheets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<List<String>> listSheets(@RequestPart("file") FilePart file) {
        return toBytes(file)
                .flatMap(
                        data ->
                                Mono.fromCallable(
                                                () -> listSheetNamesLightweight(data))
                                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnError(e -> log.warn("Failed to list sheets for {}", file.filename(), e))
                .onErrorMap(this::toStatus);
    }

    /**
     * Lightweight sheet name extraction for .xlsx files. Parses workbook.xml directly from the ZIP
     * archive without loading the full workbook model.
     */
    private static List<String> listSheetNamesLightweight(byte[] data) throws Exception {
        List<String> sheets = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("xl/workbook.xml".equals(entry.getName())) {
                    var dbf = DocumentBuilderFactory.newInstance();
                    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    var db = dbf.newDocumentBuilder();
                    var doc = db.parse(zis);
                    var sheetNodes = doc.getElementsByTagName("sheet");
                    for (int i = 0; i < sheetNodes.getLength(); i++) {
                        var nameAttr = sheetNodes.item(i).getAttributes().getNamedItem("name");
                        if (nameAttr != null) {
                            sheets.add(nameAttr.getNodeValue());
                        }
                    }
                    break;
                }
                zis.closeEntry();
            }
        }
        return sheets;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) return "unnamed";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
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
