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

import io.agentscope.dataagent.dataset.BaselineMdlService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.DatasetUploadAdmission;
import io.agentscope.dataagent.dataset.DatasetUploadIntake;
import io.agentscope.dataagent.dataset.DatasetUploadProperties;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.dataset.parser.DocxDescriptionExtractor;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    private final BaselineMdlService baselineMdlService;

    private final DatasetUploadProperties uploadProperties;
    private final DatasetUploadAdmission admission;
    private final DatasetUploadIntake intake;

    @Autowired
    public DatasetController(
            DatasetService datasetService,
            BaselineMdlService baselineMdlService,
            DatasetUploadProperties properties,
            DatasetUploadAdmission admission,
            DatasetUploadIntake intake) {
        this.datasetService = datasetService;
        this.baselineMdlService = baselineMdlService;
        this.uploadProperties = properties;
        this.admission = admission;
        this.intake = intake;
    }

    public DatasetController(DatasetService datasetService, BaselineMdlService baselineMdlService) {
        this.datasetService = datasetService;
        this.baselineMdlService = baselineMdlService;
        this.uploadProperties = new DatasetUploadProperties();
        this.admission = new DatasetUploadAdmission(uploadProperties);
        this.intake = new DatasetUploadIntake(uploadProperties, admission);
    }

    public record UploadPolicy(
            long maxFileBytes,
            long maxSelectionBytes,
            int maxFiles,
            long maxRows,
            int maxColumns,
            long maxCells,
            int concurrentFiles) {}

    @GetMapping("/upload-policy")
    public UploadPolicy uploadPolicy() {
        return new UploadPolicy(
                uploadProperties.getMaxFileSize().toBytes(),
                uploadProperties.getMaxSelectionSize().toBytes(),
                uploadProperties.getMaxFiles(),
                uploadProperties.getMaxRows(),
                uploadProperties.getMaxColumns(),
                uploadProperties.getMaxCells(),
                uploadProperties.getConcurrentFiles());
    }

    @GetMapping("/upload-status")
    public Mono<List<DatasetUploadAdmission.Status>> uploadStatuses(
            @RequestParam String groupId, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            datasetService.requireUploadAccess(user, groupId);
                            return admission.statuses(user, groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    public record ColumnVO(String name, String originalName, String sqlType, String description) {}

    public record DatasetVO(
            String id,
            String name,
            String groupId,
            String tableName,
            String schemaName,
            Long rowCount,
            String description,
            String sourceFileName,
            List<ColumnVO> columns,
            String createdAt,
            String origin,
            String externalDataSourceId,
            io.agentscope.dataagent.dataset.DatasetSampling.Summary sampling) {}

    public record PreviewVO(List<ColumnVO> columns, List<List<String>> rows) {}

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<DatasetVO> uploadWithId(
            @RequestPart("file") FilePart file,
            @RequestPart(value = "descriptionFile", required = false) FilePart descriptionFile,
            @RequestParam("name") String name,
            @RequestParam("groupId") String groupId,
            @RequestParam(value = "uploadId", required = false) String uploadId,
            Authentication auth) {
        String user = (String) auth.getPrincipal();
        return Mono.defer(
                        () -> {
                            datasetService.requireUploadAccess(user, groupId);
                            requireFile(file.filename());
                            if (name == null || name.isBlank())
                                throw new DatasetException("数据集名称不能为空");
                            return intake.run(
                                    file,
                                    descriptionFile,
                                    user,
                                    groupId,
                                    name.trim(),
                                    uploadId,
                                    input -> {
                                        try (InputStream data =
                                                Files.newInputStream(input.staged().file())) {
                                            String description = null;
                                            if (input.staged().description() != null)
                                                try (InputStream desc =
                                                        Files.newInputStream(
                                                                input.staged().description())) {
                                                    description =
                                                            DocxDescriptionExtractor.extract(
                                                                    desc, MAX_DESCRIPTION_CHARS);
                                                }
                                            DatasetEntity entity =
                                                    datasetService.ingest(
                                                            user,
                                                            groupId,
                                                            name,
                                                            description,
                                                            data,
                                                            file.filename());
                                            input.lease().saved(entity.getId());
                                            try {
                                                baselineMdlService.publishAfterDatasetChange(
                                                        user, groupId);
                                            } catch (RuntimeException failure) {
                                                throw new UploadPublicationException(
                                                        entity.getId(), failure);
                                            }
                                            input.lease().completed();
                                            return toVO(entity);
                                        } catch (java.io.IOException e) {
                                            throw new DatasetException("读取上传文件失败", e);
                                        }
                                    });
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.warn("Dataset upload failed for {}: {}", user, e.getMessage()))
                .onErrorMap(this::toStatus);
    }

    public Mono<DatasetVO> upload(
            FilePart file,
            FilePart descriptionFile,
            String name,
            String groupId,
            Authentication auth) {
        return uploadWithId(file, descriptionFile, name, groupId, null, auth);
    }

    public static class UploadPublicationException extends ResponseStatusException {
        private final String datasetId;

        UploadPublicationException(String datasetId, Throwable failure) {
            super(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "数据已保存，但模型尚未就绪，请检查与发布：" + failure.getMessage(),
                    failure);
            this.datasetId = datasetId;
        }
    }

    public record UploadError(String code, String message, String datasetId) {}

    @ExceptionHandler(org.springframework.core.io.buffer.DataBufferLimitException.class)
    public ResponseEntity<UploadError> multipartLimit(
            org.springframework.core.io.buffer.DataBufferLimitException error) {
        return ResponseEntity.status(413)
                .body(new UploadError("UPLOAD_LIMIT", "上传文件超过大小限制，请缩小文件后重试", null));
    }

    @ExceptionHandler(UploadPublicationException.class)
    public ResponseEntity<UploadError> publicationError(UploadPublicationException error) {
        return ResponseEntity.status(500)
                .body(
                        new UploadError(
                                "IMPORTED_MODEL_PENDING", error.getReason(), error.datasetId));
    }

    public record BatchUploadResult(
            String fileName, String status, DatasetVO dataset, String error) {}

    @PostMapping(value = "/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<List<BatchUploadResult>> batchUpload(
            @RequestPart("files") List<FilePart> files,
            @RequestParam("groupId") String groupId,
            Authentication auth) {
        String user = (String) auth.getPrincipal();
        return Mono.defer(
                        () -> {
                            datasetService.requireUploadAccess(user, groupId);
                            if (files.isEmpty() || files.size() > uploadProperties.getMaxFiles())
                                throw new DatasetException(
                                        "一次最多上传 " + uploadProperties.getMaxFiles() + " 个文件");
                            var names = new java.util.HashSet<String>();
                            for (FilePart file : files) {
                                requireFile(file.filename());
                                if (!names.add(
                                        stripExtension(file.filename()).toLowerCase(Locale.ROOT)))
                                    throw new DatasetException("本批次存在同名数据集");
                            }
                            return Mono.usingWhen(
                                    intake.stageBatch(files, user),
                                    staged ->
                                            Flux.fromIterable(staged)
                                                    .flatMapSequential(
                                                            file ->
                                                                    importBatchFile(
                                                                            file, user, groupId),
                                                            uploadProperties.getConcurrentFiles(),
                                                            1)
                                                    .collectList()
                                                    .flatMap(
                                                            results ->
                                                                    Mono.fromCallable(
                                                                                    () ->
                                                                                            publishBatch(
                                                                                                    user,
                                                                                                    groupId,
                                                                                                    results))
                                                                            .subscribeOn(
                                                                                    Schedulers
                                                                                            .boundedElastic())),
                                    this::closeStaged,
                                    (staged, error) -> closeStaged(staged),
                                    this::closeStaged);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    private Mono<BatchUploadResult> importBatchFile(
            DatasetUploadIntake.Staged file, String user, String groupId) {
        return intake.runStaged(
                        file,
                        user,
                        groupId,
                        stripExtension(file.filename()),
                        input -> {
                            try (InputStream data = Files.newInputStream(file.file())) {
                                DatasetEntity entity =
                                        datasetService.ingest(
                                                user,
                                                groupId,
                                                stripExtension(file.filename()),
                                                null,
                                                data,
                                                file.filename());
                                input.lease().saved(entity.getId());
                                return new BatchUploadResult(
                                        file.filename(),
                                        "IMPORTED_MODEL_PENDING",
                                        toVO(entity),
                                        null);
                            } catch (java.io.IOException e) {
                                throw new DatasetException("读取上传文件失败", e);
                            }
                        })
                .onErrorResume(
                        error ->
                                Mono.just(
                                        new BatchUploadResult(
                                                file.filename(),
                                                "FAILED",
                                                null,
                                                error.getMessage())));
    }

    private List<BatchUploadResult> publishBatch(
            String user, String groupId, List<BatchUploadResult> results) {
        List<String> ids =
                results.stream()
                        .filter(row -> row.dataset() != null)
                        .map(row -> row.dataset().id())
                        .toList();
        if (ids.isEmpty()) return results;
        String publishError = null;
        try (var publication = admission.acquire(user, "@publication", groupId, null)) {
            publication.start();
            admission.publishing(user, groupId, ids);
            baselineMdlService.publishAfterDatasetChange(user, groupId);
            publication.completed();
        } catch (RuntimeException failure) {
            publishError = "数据已保存，但模型尚未就绪，请检查与发布：" + failure.getMessage();
        }
        admission.published(user, groupId, ids, publishError);
        final String error = publishError;
        return results.stream()
                .map(
                        row ->
                                row.dataset() == null
                                        ? row
                                        : new BatchUploadResult(
                                                row.fileName(),
                                                error == null
                                                        ? "COMPLETED"
                                                        : "IMPORTED_MODEL_PENDING",
                                                row.dataset(),
                                                error))
                .toList();
    }

    private Mono<Void> closeStaged(List<DatasetUploadIntake.Staged> staged) {
        return Mono.fromRunnable(() -> staged.forEach(DatasetUploadIntake.Staged::closeIfIdle))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void requireFile(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls") && !lower.endsWith(".csv"))
            throw new DatasetException("仅支持 .xlsx / .xls / .csv 文件");
    }

    @PostMapping(value = "/list-sheets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<List<String>> listSheetsWithAuth(
            @RequestPart("file") FilePart file, Authentication auth) {
        return listSheetsFor(file, (String) auth.getPrincipal());
    }

    public Mono<List<String>> listSheets(FilePart file) {
        return listSheetsFor(file, "test");
    }

    private Mono<List<String>> listSheetsFor(FilePart file, String user) {
        return intake.run(
                        file,
                        null,
                        user,
                        "@sheets",
                        UUID.randomUUID().toString(),
                        null,
                        input -> {
                            try (InputStream stream = Files.newInputStream(input.staged().file())) {
                                List<String> names = listSheetNamesLightweight(stream);
                                input.lease().completed();
                                return names;
                            } catch (Exception e) {
                                throw new DatasetException("无法读取工作表：" + e.getMessage(), e);
                            }
                        })
                .onErrorMap(this::toStatus);
    }

    /**
     * Lightweight sheet name extraction for .xlsx files. Parses workbook.xml directly from the ZIP
     * archive without loading the full workbook model.
     */
    private static List<String> listSheetNamesLightweight(InputStream data) throws Exception {
        List<String> sheets = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(data)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("xl/workbook.xml".equals(entry.getName())) {
                    var dbf = DocumentBuilderFactory.newInstance();
                    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    dbf.setFeature(
                            "http://xml.org/sax/features/external-parameter-entities", false);
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
                                (groupId == null || groupId.isBlank()
                                                ? datasetService.listByOwner(userId)
                                                : datasetService.listForGroup(userId, groupId))
                                        .stream()
                                                .filter(
                                                        d ->
                                                                groupId == null
                                                                        || groupId.isBlank()
                                                                        || groupId.equals(
                                                                                d.getGroupId()))
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
                            DatasetService.Preview p =
                                    datasetService.previewForBrowsing(userId, id, limit);
                            return new PreviewVO(
                                    p.columns().stream()
                                            .map(DatasetController::toColumnVO)
                                            .toList(),
                                    p.rows());
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    public record DescriptionUpdate(String description) {}

    @PostMapping("/{id}/sample-refresh")
    public Mono<DatasetVO> refreshSample(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> toVO(datasetService.refreshSample(userId, id)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @PutMapping("/{id}/description")
    public Mono<DatasetVO> updateDescription(
            @PathVariable String id, @RequestBody DescriptionUpdate update, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                toVO(
                                        datasetService.updateDescription(
                                                userId, id, update.description())))
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
        return Mono.fromRunnable(
                        () -> {
                            String groupId = datasetService.get(userId, id).getGroupId();
                            datasetService.delete(userId, id);
                            baselineMdlService.publishAfterDatasetChange(userId, groupId);
                        })
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
                "datasource".equals(entity.getOrigin()) ? null : entity.getRowCount(),
                entity.getDescription(),
                entity.getSourceFileName(),
                cols.stream().map(DatasetController::toColumnVO).toList(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString(),
                entity.getOrigin(),
                entity.getExternalDataSourceId(),
                io.agentscope.dataagent.dataset.DatasetSampling.decode(
                        entity.getSampleSummaryJson()));
    }

    private static ColumnVO toColumnVO(ColumnSchema c) {
        return new ColumnVO(c.name(), c.originalName(), c.sqlType(), c.description());
    }

    private Throwable toStatus(Throwable t) {
        if (t instanceof DatasetException de) {
            return new ResponseStatusException(
                    HttpStatus.valueOf(de.status()), de.getMessage(), de);
        }
        return t;
    }
}
