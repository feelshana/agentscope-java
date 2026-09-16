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
package io.agentscope.dataagent.ontology.source;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.Identifiers;
import io.agentscope.dataagent.dataset.TableProvisioner;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.ontology.source.model.ColumnMapping;
import io.agentscope.dataagent.ontology.source.model.DerivedTable;
import io.agentscope.dataagent.ontology.source.model.SourceBatch;
import io.agentscope.dataagent.ontology.source.model.SourceManifest;
import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a full manifest import: parse sources.yaml, ingest batches as uploaded datasets,
 * create derived tables, and write declared relationships.
 *
 * <p>Derived SQL is rewritten so that manifest-logical table names map to the physical
 * {@code ds_<owner8>_<dataset8>_<name>} names created by {@link TableProvisioner}. The
 * rewrite is a word-boundary regex applied longest-name-first to avoid prefix swallowing.
 */
@Service
public class ManifestImportService {

    private static final Logger log = LoggerFactory.getLogger(ManifestImportService.class);

    private final SourceParser sourceParser;
    private final SourceIngestService sourceIngestService;
    private final DatasetService datasetService;
    private final TableProvisioner provisioner;
    private final DatasetRepository datasetRepository;
    private final DatasetRelationRepository relationRepository;
    private final InMemoryDataSourceRegistry registry;

    public ManifestImportService(
            SourceParser sourceParser,
            SourceIngestService sourceIngestService,
            DatasetService datasetService,
            TableProvisioner provisioner,
            DatasetRepository datasetRepository,
            DatasetRelationRepository relationRepository,
            InMemoryDataSourceRegistry registry) {
        this.sourceParser = sourceParser;
        this.sourceIngestService = sourceIngestService;
        this.datasetService = datasetService;
        this.provisioner = provisioner;
        this.datasetRepository = datasetRepository;
        this.relationRepository = relationRepository;
        this.registry = registry;
    }

    public record ImportSummary(
            int batchesImported,
            int batchesSkipped,
            int derivedCreated,
            int relationshipsWritten,
            List<String> warnings) {}

    /**
     * Import a manifest with prefixed table names (backward-compatible).
     */
    public ImportSummary importManifest(
            String ownerId, String groupId, String jsonText, Map<String, InputStream> files) {
        return importManifest(ownerId, groupId, jsonText, files, false);
    }

    /**
     * Import a manifest and its associated data files into a knowledge base.
     *
     * <p>When {@code useExactNames} is true, physical table names use the manifest's logical
     * names directly (sanitized, no prefix) — the user wants exact control over table/column names.
     *
     * <p><b>No @Transactional:</b> This method runs long MySQL DDL/DML operations (create tables,
     * bulk insert). Holding an H2 metadata transaction for the entire duration would cause the H2
     * connection pool to timeout and close, leading to "database has been closed" errors. Each
     * JPA save() call auto-commits individually; MySQL-side cleanup on failure is handled by the
     * manual try-catch rollback logic below.
     *
     * @param ownerId       current user
     * @param groupId       target knowledge base
     * @param jsonText      content of sources.json
     * @param files         map from file name to input stream (one per batch's {@code file} field)
     * @param useExactNames if true, use manifest names as-is; if false, use prefixed names
     */
    public ImportSummary importManifest(
            String ownerId,
            String groupId,
            String jsonText,
            Map<String, InputStream> files,
            boolean useExactNames) {

        SourceManifest manifest = sourceParser.parse(jsonText);
        // logical name → (datasetId, physicalTableName)
        Map<String, TableRef> tableMap = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        // Seed map from datasets already in the group (so derived SQL can reference them)
        for (DatasetEntity existing :
                datasetRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)) {
            if (groupId.equals(existing.getGroupId())) {
                tableMap.put(
                        existing.getName(),
                        new TableRef(existing.getId(), existing.getTableName()));
            }
        }

        // ---------- 1. batches ----------
        int imported = 0;
        int skipped = 0;
        List<DatasetEntity> createdEntities = new ArrayList<>();
        List<String> createdPhysicalTables = new ArrayList<>();
        for (SourceBatch batch : manifest.getBatches()) {
            if (batch.getTable() == null || batch.getTable().isBlank()) {
                warnings.add("批次 " + batch.getId() + ": 缺少 table 字段，跳过");
                continue;
            }
            // Idempotency: if a dataset with this name already exists in the group, skip
            Optional<DatasetEntity> existing =
                    datasetRepository.findByOwnerIdAndGroupIdAndName(
                            ownerId, groupId, batch.getTable());
            if (existing.isPresent()) {
                DatasetEntity e = existing.get();
                tableMap.put(batch.getTable(), new TableRef(e.getId(), e.getTableName()));
                skipped++;
                log.info(
                        "ManifestImportService: 批次 {} 已存在 (dataset {}), 跳过",
                        batch.getId(),
                        e.getId());
                continue;
            }

            InputStream stream = batch.getFile() != null ? files.get(batch.getFile()) : null;
            if (stream == null) {
                warnings.add("批次 " + batch.getId() + ": 文件 '" + batch.getFile() + "' 未提供，跳过");
                continue;
            }

            // Use SourceIngestService to honour manifest column definitions (name, type, comment)
            String datasetId = UUID.randomUUID().toString();
            String physicalTable =
                    useExactNames
                            ? Identifiers.exactName(batch.getTable(), "data")
                            : TableProvisioner.buildTableName(ownerId, datasetId, batch.getTable());

            // Build a temp batch with the physical table name for SourceIngestService
            SourceBatch physicalBatch = new SourceBatch();
            physicalBatch.setId(batch.getId());
            physicalBatch.setFile(batch.getFile());
            physicalBatch.setTable(physicalTable);
            physicalBatch.setColumns(batch.getColumns());
            physicalBatch.setConstants(batch.getConstants());
            SourceManifest tempManifest = new SourceManifest();
            tempManifest.setBatches(List.of(physicalBatch));

            try {
                sourceIngestService.createTables(tempManifest);
                sourceIngestService.ingestBatch(physicalBatch, stream);
            } catch (RuntimeException e) {
                // Cleanup: drop the partially created table
                try {
                    provisioner.dropTable(physicalTable);
                } catch (Exception ignored) {
                }
                warnings.add("批次 " + batch.getId() + ": " + e.getMessage() + "，跳过");
                continue;
            }
            createdPhysicalTables.add(physicalTable);

            // Build column schema JSON from manifest column definitions
            String columnSchemaJson = buildColumnSchemaJson(batch.getColumns());
            long rowCount = provisioner.countRows(physicalTable);

            String desc = batch.getTable();
            DatasetEntity entity = new DatasetEntity();
            entity.setId(datasetId);
            entity.setOwnerId(ownerId);
            entity.setGroupId(groupId);
            entity.setName(batch.getTable());
            entity.setSchemaName(provisioner.databaseName());
            entity.setTableName(physicalTable);
            entity.setDescription(desc);
            entity.setColumnSchemaJson(columnSchemaJson);
            entity.setRowCount(rowCount);
            entity.setSourceFileName(batch.getFile());
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());

            datasetRepository.save(entity);
            registry.add(datasetService.toDataSourcePublic(entity));
            tableMap.put(batch.getTable(), new TableRef(entity.getId(), physicalTable));
            createdEntities.add(entity);
            imported++;
        }

        // ---------- 2. derived ----------
        // When uploading files one-at-a-time, not all batch tables exist yet.
        // Extract table references from each derived SQL and skip if any dependency
        // is missing; the derived table will be created on a subsequent upload.
        int derivedCreated = 0;
        List<DatasetEntity> derivedEntities = new ArrayList<>();
        try {
            for (DerivedTable dt : manifest.getDerived()) {
                if (dt.getTable() == null || dt.getTable().isBlank()) {
                    warnings.add("派生表缺少 table 字段，跳过");
                    continue;
                }
                // Idempotency: if already exists in this group, skip
                Optional<DatasetEntity> existingDerived =
                        datasetRepository.findByOwnerIdAndGroupIdAndName(
                                ownerId, groupId, dt.getTable());
                if (existingDerived.isPresent()) {
                    DatasetEntity e = existingDerived.get();
                    tableMap.put(dt.getTable(), new TableRef(e.getId(), e.getTableName()));
                    log.info(
                            "ManifestImportService: 派生表 {} 已存在 (dataset {}), 跳过",
                            dt.getTable(),
                            e.getId());
                    continue;
                }

                // Dependency check: extract referenced table names from the SQL and
                // verify they all exist in tableMap. Skip if any dependency is missing.
                Set<String> refs = extractTableNames(dt.getSql());
                refs.remove(dt.getTable()); // exclude self-reference
                Set<String> missing = new HashSet<>();
                for (String ref : refs) {
                    if (!tableMap.containsKey(ref)) {
                        missing.add(ref);
                    }
                }
                if (!missing.isEmpty()) {
                    warnings.add(
                            "派生表 " + dt.getTable() + ": 依赖表 " + missing + " 尚未就绪，跳过（后续上传时自动创建）");
                    log.info(
                            "ManifestImportService: 派生表 {} 依赖表 {} 尚未就绪，跳过", dt.getTable(), missing);
                    continue;
                }

                String rewrittenSql =
                        useExactNames ? dt.getSql() : rewriteTableNames(dt.getSql(), tableMap);
                String datasetId = UUID.randomUUID().toString();
                String physicalTable =
                        useExactNames
                                ? Identifiers.exactName(dt.getTable(), "data")
                                : TableProvisioner.buildTableName(
                                        ownerId, datasetId, dt.getTable());

                try {
                    provisioner.createDerivedTable(physicalTable, rewrittenSql);
                } catch (RuntimeException e) {
                    provisioner.dropTable(physicalTable);
                    throw new DatasetException(
                            "派生表 " + dt.getTable() + " 创建失败: " + e.getMessage(), e);
                }

                DatasetEntity entity = new DatasetEntity();
                entity.setId(datasetId);
                entity.setOwnerId(ownerId);
                entity.setGroupId(groupId);
                entity.setName(dt.getTable());
                entity.setSchemaName(provisioner.databaseName());
                entity.setTableName(physicalTable);
                entity.setDescription(
                        dt.getComment() != null && !dt.getComment().isBlank()
                                ? dt.getComment()
                                : "派生表 " + dt.getTable());
                entity.setColumnSchemaJson("[]"); // populated by probe below
                entity.setRowCount(provisioner.countRows(physicalTable));
                entity.setSourceFileName("manifest-derived:" + dt.getTable());
                entity.setOrigin("derived");
                entity.setSourceSQL(dt.getSql());
                entity.setCreatedAt(Instant.now());
                entity.setUpdatedAt(Instant.now());

                // Backfill column schema from probe
                try {
                    var cols = provisioner.probeColumns(rewrittenSql);
                    entity.setColumnSchemaJson(
                            new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(cols));
                } catch (Exception ignored) {
                    // leave empty; describe_table will re-probe via JDBC
                }

                datasetRepository.save(entity);
                registry.add(datasetService.toDataSourcePublic(entity));
                tableMap.put(dt.getTable(), new TableRef(entity.getId(), physicalTable));
                derivedEntities.add(entity);
                derivedCreated++;
            }
        } catch (RuntimeException e) {
            // Rollback created derived tables
            for (DatasetEntity d : derivedEntities) {
                try {
                    provisioner.dropTable(d.getTableName());
                    registry.remove(d.getId());
                    datasetRepository.delete(d);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
            // Rollback batch tables created in this import
            for (DatasetEntity b : createdEntities) {
                try {
                    provisioner.dropTable(b.getTableName());
                    registry.remove(b.getId());
                    datasetRepository.delete(b);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
            throw e;
        }

        // ---------- 3. relationships ----------
        int relsWritten = 0;
        for (SourceManifest.ManifestRelationship mr : manifest.getRelationships()) {
            TableRef src = tableMap.get(mr.getSource());
            TableRef tgt = tableMap.get(mr.getTarget());
            if (src == null || tgt == null) {
                warnings.add("关系 " + mr.getSource() + " → " + mr.getTarget() + ": 源或目标表不存在，跳过");
                continue;
            }
            DatasetRelationEntity rel = new DatasetRelationEntity();
            rel.setId(UUID.randomUUID().toString());
            rel.setGroupId(groupId);
            rel.setSourceDatasetId(src.datasetId());
            rel.setSourceColumn(mr.getSourceColumn());
            rel.setTargetDatasetId(tgt.datasetId());
            rel.setTargetColumn(mr.getTargetColumn());
            rel.setRelationType("MANIFEST");
            rel.setDescription(mr.getLabel());
            rel.setConfidence(1.0);
            rel.setOrigin("manifest");
            rel.setCreatedAt(Instant.now());
            relationRepository.save(rel);
            relsWritten++;
        }

        log.info(
                "ManifestImportService: import complete — {} batches imported, {} skipped,"
                        + " {} derived, {} relationships (group={}, owner={})",
                imported,
                skipped,
                derivedCreated,
                relsWritten,
                groupId,
                ownerId);
        return new ImportSummary(imported, skipped, derivedCreated, relsWritten, warnings);
    }

    /**
     * Rewrite manifest-logical table names in a SQL statement to their physical {@code ds_...}
     * names. Iterates longest-first to prevent prefix swallowing (e.g. {@code user_identity} must
     * be replaced before {@code user}). Matches bare names and backtick-wrapped names.
     */
    static String rewriteTableNames(String sql, Map<String, TableRef> tableMap) {
        if (sql == null || tableMap.isEmpty()) {
            return sql;
        }
        List<String> names = new ArrayList<>(tableMap.keySet());
        // Longest name first to avoid partial replacement of prefixed names
        names.sort(Comparator.comparingInt(String::length).reversed());

        String result = sql;
        for (String logical : names) {
            String physical = tableMap.get(logical).physicalTable();
            // Word-boundary-aware replacement: handles bare and backtick-wrapped names.
            // Pattern: not preceded by [A-Za-z0-9_$], not followed by [A-Za-z0-9_$].
            // Also strips any surrounding backticks in the match and replaces with the
            // physical name (unwrapped; SQL identifiers don't need them here).
            String quoted = Pattern.quote(logical);
            String pattern = "(?<![A-Za-z0-9_$])`?" + quoted + "`?(?![A-Za-z0-9_$])";
            result = result.replaceAll(pattern, Matcher.quoteReplacement(physical));
        }
        return result;
    }

    /**
     * Extract likely table names from a SQL statement for dependency checking.
     * Matches identifiers after FROM, JOIN, INTO, UPDATE, and TABLE keywords.
     * Excludes SQL keywords, subquery aliases, and function names.
     */
    static Set<String> extractTableNames(String sql) {
        Set<String> tables = new HashSet<>();
        if (sql == null) {
            return tables;
        }
        // Match identifiers that follow FROM, JOIN, INTO, UPDATE, or TABLE keywords.
        // Handles backtick-quoted and plain identifiers.
        Pattern p = Pattern.compile("(?i)(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+`?([a-zA-Z_]\\w*)`?");
        Matcher m = p.matcher(sql);
        while (m.find()) {
            String name = m.group(1);
            // Exclude common SQL pseudo-references
            if (!name.equalsIgnoreCase("_probe")
                    && !name.equalsIgnoreCase("_src")
                    && !name.equalsIgnoreCase("DUAL")) {
                tables.add(name);
            }
        }
        return tables;
    }

    record TableRef(String datasetId, String physicalTable) {}

    /**
     * Build a ColumnSchema JSON array from manifest column definitions,
     * compatible with DatasetEntity.columnSchemaJson format.
     */
    private String buildColumnSchemaJson(List<ColumnMapping> columns) {
        if (columns == null || columns.isEmpty()) return "[]";
        List<ColumnSchema> schemas =
                columns.stream()
                        .map(
                                cm ->
                                        new ColumnSchema(
                                                cm.getName(),
                                                cm.getComment() != null
                                                        ? cm.getComment()
                                                        : cm.getName(),
                                                cm.toMysqlType(),
                                                true,
                                                cm.getComment()))
                        .toList();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(schemas);
        } catch (Exception e) {
            log.warn("buildColumnSchemaJson failed: {}", e.getMessage());
            return "[]";
        }
    }
}
