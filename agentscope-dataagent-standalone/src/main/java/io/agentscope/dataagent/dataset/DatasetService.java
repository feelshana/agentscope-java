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
package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.dataset.parser.FileParser;
import io.agentscope.dataagent.dataset.parser.SchemaGenerationService;
import io.agentscope.dataagent.tools.data.DataSource;
import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticBusinessRuleRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewRepository;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the dataset lifecycle: parse an uploaded file, materialise it as a table in its own
 * MySQL schema, persist metadata, and expose it to agents as a {@link DataSource}. Also rebuilds
 * the in-memory registry from persisted metadata on startup so datasets survive restarts.
 */
@Service
public class DatasetService implements DatasetContextProvider {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_]+$");

    private final DatasetRepository repository;
    private final DatasetGroupRepository groupRepository;
    private final DatasetKnowledgeRepository knowledgeRepository;
    private final SemanticTermRepository semanticTerms;
    private final SemanticViewRepository semanticViews;
    private final SemanticBusinessRuleRepository semanticBusinessRules;
    private final ExternalDataSourceRepository externalSources;
    private final DataSourceIntrospector introspector;
    private final DatasetRelationRepository relationRepository;
    private final InMemoryDataSourceRegistry registry;
    private final TableProvisioner provisioner;
    private final List<FileParser> parsers;
    private final DatasetStoreProperties props;
    private final ObjectMapper mapper;
    private final DatasetImportService importService;
    private final SchemaGenerationService schemaGeneration;
    private final MdlWorkspaceReader workspaceReader;

    public DatasetService(
            DatasetRepository repository,
            DatasetGroupRepository groupRepository,
            DatasetKnowledgeRepository knowledgeRepository,
            SemanticTermRepository semanticTerms,
            SemanticViewRepository semanticViews,
            SemanticBusinessRuleRepository semanticBusinessRules,
            ExternalDataSourceRepository externalSources,
            DataSourceIntrospector introspector,
            DatasetRelationRepository relationRepository,
            InMemoryDataSourceRegistry registry,
            TableProvisioner provisioner,
            List<FileParser> parsers,
            DatasetStoreProperties props,
            ObjectMapper mapper,
            DatasetImportService importService,
            SchemaGenerationService schemaGeneration,
            MdlWorkspaceReader workspaceReader) {
        this.repository = repository;
        this.groupRepository = groupRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.semanticTerms = semanticTerms;
        this.semanticViews = semanticViews;
        this.semanticBusinessRules = semanticBusinessRules;
        this.externalSources = externalSources;
        this.introspector = introspector;
        this.relationRepository = relationRepository;
        this.registry = registry;
        this.provisioner = provisioner;
        this.parsers = parsers;
        this.props = props;
        this.mapper = mapper;
        this.importService = importService;
        this.schemaGeneration = schemaGeneration;
        this.workspaceReader = workspaceReader;
    }

    @PostConstruct
    void rebuildRegistry() {
        List<DatasetEntity> all = repository.findAll();
        all.forEach(e -> registry.add(toDataSource(e)));
        log.info(
                "DatasetService: re-registered {} persisted dataset(s) into the registry",
                all.size());
    }

    @Transactional
    public DatasetEntity ingest(
            String ownerId,
            String groupId,
            String name,
            String description,
            InputStream data,
            String fileName) {
        if (name == null || name.isBlank()) {
            throw new DatasetException("Dataset name must not be blank");
        }
        DatasetGroupEntity group =
                groupRepository
                        .findById(groupId == null ? "" : groupId)
                        .filter(g -> g.getOwnerId().equals(ownerId))
                        .orElseThrow(
                                () ->
                                        new DatasetException(
                                                "Knowledge base not found: " + groupId, 404));
        validateUploadSourceCombination(group.getId());
        repository
                .findByOwnerIdAndGroupIdAndName(ownerId, group.getId(), name.trim())
                .ifPresent(
                        e -> {
                            throw new DatasetException("Dataset name already exists: " + name, 409);
                        });
        // Validate file type
        String lower = fileName != null ? fileName.toLowerCase() : "";
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls") && !lower.endsWith(".csv")) {
            throw new DatasetException(
                    "Unsupported file type: " + fileName + " (expected .xlsx / .xls / .csv)");
        }

        String id = UUID.randomUUID().toString();

        // Use EasyExcel streaming import with AI schema generation
        DatasetImportService.ImportResult importResult;
        try {
            importResult = importService.importFile(ownerId, id, name, data, fileName);
        } catch (RuntimeException e) {
            throw new DatasetException("Failed to import " + fileName + ": " + e.getMessage(), e);
        }

        if (importResult.totalRows() == 0) {
            throw new DatasetException("No data rows found in " + fileName);
        }

        String tableName = importResult.tableName();

        // Build description: prefer user input, then AI-generated table description
        String finalDesc;
        if (description != null && !description.isBlank()) {
            finalDesc = description.trim();
        } else if (importResult.tableDescription() != null
                && !importResult.tableDescription().isBlank()) {
            finalDesc = importResult.tableDescription();
        } else {
            finalDesc = name.trim();
        }

        DatasetEntity entity = new DatasetEntity();
        entity.setId(id);
        entity.setOwnerId(ownerId);
        entity.setGroupId(group.getId());
        entity.setName(name.trim());
        entity.setSchemaName(provisioner.databaseName());
        entity.setTableName(tableName);
        entity.setDescription(finalDesc);
        entity.setColumnSchemaJson(writeJson(importResult.columns()));
        entity.setRowCount(importResult.totalRows());
        entity.setSourceFileName(fileName);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        registry.add(toDataSource(entity));
        markMdlDirty(group.getId());
        log.info(
                "DatasetService: ingested dataset '{}' ({} rows) for owner {} as {}.{}",
                entity.getName(),
                entity.getRowCount(),
                ownerId,
                entity.getSchemaName(),
                tableName);
        return entity;
    }

    @Transactional
    public void delete(String ownerId, String datasetId) {
        DatasetEntity entity = get(ownerId, datasetId);
        deleteEntity(entity);
        log.info("DatasetService: deleted dataset {} for owner {}", datasetId, ownerId);
    }

    /** Removes a dataset; drops the physical table only for uploaded (copied) datasets. */
    @Transactional
    public void deleteEntity(DatasetEntity entity) {
        if (!"datasource".equals(entity.getOrigin())) {
            provisioner.dropTable(entity.getTableName());
        }
        registry.remove(entity.getId());
        repository.delete(entity);
        markMdlDirty(entity.getGroupId());
    }

    /**
     * Associates existing tables of a user-configured external database into a knowledge base as
     * read-only datasets (no data copy). Column metadata comes from JDBC introspection; when the
     * source has sampling enabled, low-cardinality columns get sample values merged into their
     * description to help the agent.
     */
    @Transactional
    public List<DatasetEntity> associateTables(
            String ownerId,
            String groupId,
            String dataSourceId,
            String schema,
            List<String> tables,
            boolean sampling) {
        ExternalDataSourceEntity ds =
                externalSources
                        .findById(dataSourceId)
                        .filter(d -> d.getOwnerId().equals(ownerId))
                        .orElseThrow(
                                () ->
                                        new DatasetException(
                                                "Data source not found: " + dataSourceId, 404));
        groupRepository
                .findById(groupId)
                .filter(g -> g.getOwnerId().equals(ownerId))
                .orElseThrow(
                        () -> new DatasetException("Knowledge base not found: " + groupId, 404));
        validateExternalSourceCombination(groupId, ds);
        List<AssociatedTableMetadata> prepared =
                prepareAssociatedTables(
                        ownerId, groupId, ds, schema, tables, sampling && ds.isSampling());
        List<DatasetEntity> created = new ArrayList<>();
        List<DataSource> registrations = new ArrayList<>();
        for (AssociatedTableMetadata metadata : prepared) {
            DatasetEntity e = new DatasetEntity();
            e.setId(UUID.randomUUID().toString());
            e.setOwnerId(ownerId);
            e.setGroupId(groupId);
            e.setName(metadata.table());
            e.setOrigin("datasource");
            e.setExternalDataSourceId(ds.getId());
            e.setSchemaName(schema);
            e.setTableName(metadata.table());
            e.setDescription(metadata.description());
            e.setColumnSchemaJson(writeJson(metadata.columns()));
            e.setRowCount(metadata.rowCount());
            e.setSourceFileName(ds.getName() + "/" + schema + "." + metadata.table());
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            created.add(e);
            registrations.add(toDataSource(e));
        }
        created.forEach(repository::save);
        registrations.forEach(registry::add);
        markMdlDirty(groupId);
        log.info(
                "DatasetService: associated {} table(s) from datasource {} into group {} for owner"
                        + " {}",
                created.size(),
                dataSourceId,
                groupId,
                ownerId);
        return created;
    }

    private List<AssociatedTableMetadata> prepareAssociatedTables(
            String ownerId,
            String groupId,
            ExternalDataSourceEntity source,
            String schema,
            List<String> tables,
            boolean sampling) {
        if (tables == null || tables.isEmpty()) {
            throw new DatasetException("请至少选择一张外部数据表");
        }
        try {
            validateIdentifier(schema, "schema");
        } catch (IllegalArgumentException e) {
            throw new DatasetException(e.getMessage());
        }

        Set<String> uniqueTables = new HashSet<>();
        for (String table : tables) {
            try {
                validateIdentifier(table, "table");
            } catch (IllegalArgumentException e) {
                throw new DatasetException(e.getMessage());
            }
            if (!uniqueTables.add(table)) {
                throw new DatasetException("不能重复关联同一张外部数据表: " + table, 409);
            }
            repository
                    .findByOwnerIdAndGroupIdAndName(ownerId, groupId, table)
                    .ifPresent(
                            existing -> {
                                throw new DatasetException(
                                        "Dataset name already exists: " + table, 409);
                            });
        }

        List<AssociatedTableMetadata> prepared = new ArrayList<>();
        for (String table : tables) {
            List<DataSourceIntrospector.ColumnInfo> columns =
                    introspector.listColumns(source, schema, table);
            if (columns.isEmpty()) {
                throw new DatasetException("外部数据表不存在或没有可读取字段: " + schema + "." + table);
            }
            List<String> columnNames = new ArrayList<>();
            List<String> columnTypes = new ArrayList<>();
            Map<String, String> jdbcComments = new LinkedHashMap<>();
            Map<String, List<String>> columnSamples = new LinkedHashMap<>();
            for (DataSourceIntrospector.ColumnInfo column : columns) {
                columnNames.add(column.name());
                columnTypes.add(column.type());
                if (column.description() != null && !column.description().isBlank()) {
                    jdbcComments.put(column.name(), column.description());
                }
                if (sampling) {
                    List<String> samples = sampleValues(source, schema, table, column.name());
                    if (samples != null) {
                        columnSamples.put(column.name(), samples);
                    }
                }
            }
            String tableComment = introspector.getTableComment(source, schema, table);
            SchemaGenerationService.SchemaResult schemaResult =
                    schemaGeneration.generateSchema(
                            columnNames, columnSamples, jdbcComments, tableComment);
            if (schemaResult.columns().size() != columnNames.size()) {
                throw new DatasetException("外部数据表字段描述生成不完整: " + schema + "." + table);
            }
            List<ColumnSchema> schemaColumns = new ArrayList<>();
            for (int i = 0; i < columnNames.size(); i++) {
                schemaColumns.add(
                        new ColumnSchema(
                                columnNames.get(i),
                                columnNames.get(i),
                                columnTypes.get(i),
                                true,
                                schemaResult.columns().get(i).description()));
            }
            String tableDescription = schemaResult.tableDescription();
            if (tableDescription == null || tableDescription.isBlank()) {
                tableDescription =
                        tableComment != null && !tableComment.isBlank() ? tableComment : table;
            }
            prepared.add(
                    new AssociatedTableMetadata(
                            table,
                            List.copyOf(schemaColumns),
                            tableDescription,
                            introspector.countRows(source, schema, table)));
        }
        return List.copyOf(prepared);
    }

    private record AssociatedTableMetadata(
            String table, List<ColumnSchema> columns, String description, long rowCount) {}

    private void validateUploadSourceCombination(String groupId) {
        boolean hasExternal =
                repository.findByGroupId(groupId).stream()
                        .anyMatch(dataset -> "datasource".equals(dataset.getOrigin()));
        if (hasExternal) {
            throw new DatasetException("知识库已关联外部数据源表，不能再上传本地数据集，请使用独立知识库");
        }
    }

    private void validateExternalSourceCombination(
            String groupId, ExternalDataSourceEntity source) {
        if (!"mysql".equalsIgnoreCase(source.getKind())) {
            throw new DatasetException("当前仅支持将 MySQL 外部数据源表关联到 Wren 知识库");
        }
        for (DatasetEntity existing : repository.findByGroupId(groupId)) {
            if (!"datasource".equals(existing.getOrigin())) {
                throw new DatasetException("知识库已包含上传数据集，不能再关联外部数据源表，请使用独立知识库");
            }
            if (!source.getId().equals(existing.getExternalDataSourceId())) {
                throw new DatasetException("一个知识库只能关联同一个外部数据源的表");
            }
        }
    }

    /** Distinct values for a column when cardinality is low (<=20); max 3 returned. null if high cardinality. */
    private List<String> sampleValues(
            ExternalDataSourceEntity ds, String schema, String table, String column) {
        validateIdentifier(schema, "schema");
        validateIdentifier(table, "table");
        validateIdentifier(column, "column");
        String q = "postgresql".equalsIgnoreCase(ds.getKind()) ? "\"" : "`";
        String sql =
                "SELECT DISTINCT "
                        + q
                        + column
                        + q
                        + " FROM "
                        + q
                        + schema
                        + q
                        + "."
                        + q
                        + table
                        + q
                        + " LIMIT 21";
        try (java.sql.Connection c =
                        java.sql.DriverManager.getConnection(
                                ds.getJdbcUrl(), ds.getUsername(), ds.getPassword());
                java.sql.Statement st = c.createStatement();
                java.sql.ResultSet rs = st.executeQuery(sql)) {
            List<String> vals = new ArrayList<>();
            while (rs.next()) {
                vals.add(rs.getString(1));
            }
            if (vals.size() > 20) {
                return null;
            }
            return vals.subList(0, Math.min(vals.size(), 3));
        } catch (Exception e) {
            return null;
        }
    }

    public List<DatasetEntity> listByOwner(String ownerId) {
        return repository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    public DatasetEntity get(String ownerId, String datasetId) {
        return repository
                .findById(datasetId)
                .filter(e -> e.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new DatasetException("Dataset not found: " + datasetId, 404));
    }

    /**
     * Human/agent-readable summary of the selected datasets, injected into the chat context so the
     * model knows which physical tables back the user's selection.
     */
    public String buildScopeContext(String ownerId, List<String> datasetIds) {
        if (datasetIds == null || datasetIds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : datasetIds) {
            repository
                    .findById(id)
                    .filter(e -> e.getOwnerId().equals(ownerId))
                    .ifPresent(
                            e -> {
                                sb.append("- ")
                                        .append(e.getName())
                                        .append(" (table: ")
                                        .append(e.getTableName())
                                        .append(", dataSourceId: ")
                                        .append(e.getId())
                                        .append("): ");
                                for (ColumnSchema c : readColumns(e)) {
                                    sb.append(c.name())
                                            .append('(')
                                            .append(c.originalName())
                                            .append(' ')
                                            .append(c.sqlType())
                                            .append(") ");
                                }
                                if (e.getDescription() != null && !e.getDescription().isBlank()) {
                                    sb.append("— ").append(e.getDescription());
                                }
                                sb.append('\n');
                            });
        }
        return sb.toString();
    }

    public List<ColumnSchema> readColumns(DatasetEntity entity) {
        try {
            return mapper.readValue(
                    entity.getColumnSchemaJson(), new TypeReference<List<ColumnSchema>>() {});
        } catch (Exception e) {
            throw new DatasetException(
                    "Failed to parse column schema JSON for dataset "
                            + entity.getId()
                            + ": "
                            + e.getMessage(),
                    e);
        }
    }

    public record Preview(List<ColumnSchema> columns, List<List<String>> rows) {}

    public record ColumnDescUpdate(String name, String description) {}

    /** Read-only sample of the dataset's physical table for the detail page. */
    public Preview preview(String ownerId, String datasetId, int limit) {
        DatasetEntity entity = get(ownerId, datasetId);
        int n = limit <= 0 ? 20 : Math.min(limit, 100);
        List<List<String>> rows = new ArrayList<>();
        String sql = "SELECT * FROM " + qualifiedTable(entity) + " LIMIT " + n;
        try (java.sql.Connection c = openFor(entity);
                java.sql.Statement st = c.createStatement();
                java.sql.ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>(cols);
                for (int i = 1; i <= cols; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        } catch (java.sql.SQLException e) {
            throw new DatasetException(
                    "Failed to preview " + entity.getTableName() + ": " + e.getMessage(), e);
        }
        return new Preview(readColumns(entity), rows);
    }

    /**
     * Total / distinct-non-null value counts of one physical column ({@code [total, distinct]}),
     * used by the semantic-modeling joinType probe: a unique end is the "one" side of the join.
     * The column must exist on the dataset's schema — anything else is rejected before it can
     * reach the SQL text.
     */
    public long[] columnProfile(String ownerId, String datasetId, String column) {
        DatasetEntity entity = get(ownerId, datasetId);
        boolean known = readColumns(entity).stream().anyMatch(c -> c.name().equals(column));
        if (!known) {
            throw new DatasetException(
                    "Unknown column " + column + " on dataset " + datasetId, 400);
        }
        String sql =
                "SELECT COUNT(*), COUNT(DISTINCT `" + column + "`) FROM " + qualifiedTable(entity);
        try (java.sql.Connection c = openFor(entity);
                java.sql.Statement st = c.createStatement();
                java.sql.ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return new long[] {rs.getLong(1), rs.getLong(2)};
            }
            return new long[] {0, 0};
        } catch (java.sql.SQLException e) {
            throw new DatasetException(
                    "Failed to profile "
                            + entity.getTableName()
                            + "."
                            + column
                            + ": "
                            + e.getMessage(),
                    e);
        }
    }

    /** Updates column business descriptions and re-registers the source so the agent sees them. */
    @Transactional
    public DatasetEntity updateColumnDescriptions(
            String ownerId, String datasetId, List<ColumnDescUpdate> updates) {
        DatasetEntity entity = get(ownerId, datasetId);
        List<ColumnSchema> cols = new ArrayList<>(readColumns(entity));
        for (ColumnDescUpdate u : updates) {
            for (int i = 0; i < cols.size(); i++) {
                ColumnSchema c = cols.get(i);
                if (c.name().equals(u.name())) {
                    cols.set(
                            i,
                            new ColumnSchema(
                                    c.name(),
                                    c.originalName(),
                                    c.sqlType(),
                                    c.nullable(),
                                    u.description()));
                }
            }
        }
        entity.setColumnSchemaJson(writeJson(cols));
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        registry.add(toDataSource(entity));
        markMdlDirty(entity.getGroupId());
        return entity;
    }

    private String writeJson(List<ColumnSchema> columns) {
        try {
            return mapper.writeValueAsString(columns);
        } catch (Exception e) {
            throw new DatasetException("Failed to serialize column schema: " + e.getMessage(), e);
        }
    }

    private DataSource toDataSource(DatasetEntity e) {
        Map<String, String> properties = new LinkedHashMap<>();
        ExternalDataSourceEntity external = resolveExternal(e);
        if (external != null) {
            properties.put("jdbcUrl", withSchema(external.getJdbcUrl(), e.getSchemaName()));
            properties.put("username", external.getUsername());
            properties.put("password", external.getPassword());
            properties.put("externalDataSourceId", external.getId());
        } else {
            properties.put("jdbcUrl", props.url());
            properties.put("username", props.username());
            properties.put("password", props.password());
        }
        properties.put("ownerId", e.getOwnerId());
        properties.put("tableName", e.getTableName());
        properties.put("datasetId", e.getId());
        if (e.getGroupId() != null) {
            properties.put("groupId", e.getGroupId());
        }
        // Keep schema metadata available for baseline MDL assembly and modeling views.
        if (e.getColumnSchemaJson() != null && !e.getColumnSchemaJson().isBlank()) {
            properties.put("columnSchemaJson", e.getColumnSchemaJson());
        }
        // Description only contains AI-generated table description (for SystemPrompt)
        String desc =
                (e.getDescription() == null || e.getDescription().isBlank())
                        ? "User-uploaded dataset '" + e.getName() + "'"
                        : e.getDescription();
        return new DataSource(
                e.getId(), e.getName(), desc, "jdbc", null, List.of("user-dataset"), properties);
    }

    private ExternalDataSourceEntity resolveExternal(DatasetEntity e) {
        if (!"datasource".equals(e.getOrigin()) || e.getExternalDataSourceId() == null) {
            return null;
        }
        return externalSources.findById(e.getExternalDataSourceId()).orElse(null);
    }

    /** Replaces the database segment of a jdbc url with the given schema. */
    private static String withSchema(String jdbcUrl, String schema) {
        int qi = jdbcUrl.indexOf('?');
        String head = qi >= 0 ? jdbcUrl.substring(0, qi) : jdbcUrl;
        String params = qi >= 0 ? jdbcUrl.substring(qi) : "";
        int schemeEnd = head.indexOf("//");
        int slash = schemeEnd >= 0 ? head.indexOf('/', schemeEnd + 2) : -1;
        String authority = slash >= 0 ? head.substring(0, slash) : head;
        return authority + "/" + schema + params;
    }

    private java.sql.Connection openFor(DatasetEntity e) throws java.sql.SQLException {
        ExternalDataSourceEntity external = resolveExternal(e);
        java.sql.Connection c;
        if (external != null) {
            c =
                    java.sql.DriverManager.getConnection(
                            external.getJdbcUrl(), external.getUsername(), external.getPassword());
        } else {
            c =
                    java.sql.DriverManager.getConnection(
                            props.url(), props.username(), props.password());
        }
        c.setReadOnly(true);
        return c;
    }

    private String qualifiedTable(DatasetEntity e) {
        if ("datasource".equals(e.getOrigin())) {
            ExternalDataSourceEntity external = resolveExternal(e);
            String q =
                    external != null && "postgresql".equalsIgnoreCase(external.getKind())
                            ? "\""
                            : "`";
            validateIdentifier(e.getSchemaName(), "schema");
            validateIdentifier(e.getTableName(), "table");
            return q + e.getSchemaName() + q + "." + q + e.getTableName() + q;
        }
        validateIdentifier(e.getTableName(), "table");
        return "`" + e.getTableName() + "`";
    }

    private static void validateIdentifier(String name, String label) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("非法" + label + "标识符: " + name);
        }
    }

    @Override
    public String semanticTermsText(java.util.List<String> onlyGroups) {
        // specs/026: terms bind to one knowledge base. Without an explicit group boundary we
        // render nothing, so another tenant's terms can never leak into the prompt.
        if (onlyGroups == null || onlyGroups.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String gid : new java.util.LinkedHashSet<>(onlyGroups)) {
            if (gid == null || gid.isBlank()) {
                continue;
            }
            for (SemanticTermEntity t : semanticTerms.findByGroupIdOrderByCreatedAtDesc(gid)) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t.getTerm());
                if (t.getExplanation() != null && !t.getExplanation().isBlank()) {
                    sb.append("：").append(t.getExplanation());
                }
                if (t.getSynonyms() != null && !t.getSynonyms().isBlank()) {
                    sb.append("（同义词：").append(t.getSynonyms().replace("\n", ",")).append("）");
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String semanticViewsText(java.util.List<String> onlyGroups) {
        // specs/019 §7: views live as workspace files now. Without an explicit group boundary we
        // render nothing, so another tenant's workspace can never leak into the prompt.
        if (onlyGroups == null || onlyGroups.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String gid : new java.util.LinkedHashSet<>(onlyGroups)) {
            if (gid == null || gid.isBlank()) {
                continue;
            }
            for (MdlWorkspaceReader.WorkspaceView v : workspaceReader.read(gid).views()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(v.name());
                if (v.description() != null && !v.description().isBlank()) {
                    sb.append("：").append(v.description().replace("\n", " "));
                }
                // specs/020: the statement itself is the best schema hint (upstream _describe_view
                // parity) — it shows the output columns so the model never needs describe on a
                // view.
                String statement = flattenStatement(v.sql());
                if (!statement.isEmpty()) {
                    sb.append("\n  SQL: ").append(statement);
                }
            }
        }
        return sb.toString();
    }

    /** Prompt statement line cap per view (upstream _view_record keeps a 200-char prefix). */
    private static final int MAX_VIEW_STATEMENT_CHARS = 400;

    /**
     * Folds a view statement onto one prompt-safe line and caps its length (specs/020): newlines
     * would break the one-entry-per-line catalog layout, and over-long statements would crowd
     * out the rest of [KNOWLEDGE_BASE_OVERVIEW].
     */
    static String flattenStatement(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_VIEW_STATEMENT_CHARS
                ? flat
                : flat.substring(0, MAX_VIEW_STATEMENT_CHARS) + "…";
    }

    @Override
    public String semanticBusinessRulesText(String ownerId, java.util.List<String> onlyGroups) {
        if (ownerId == null || ownerId.isBlank()) {
            return "";
        }
        // specs/019 §7: business rules live as knowledge/rules/*.md inside each workspace; every
        // candidate group is owner-checked before its rule files are read.
        List<DatasetGroupEntity> groups =
                onlyGroups == null || onlyGroups.isEmpty()
                        ? groupRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
                        : onlyGroups.stream()
                                .filter(gid -> gid != null && !gid.isBlank())
                                .map(groupRepository::findById)
                                .flatMap(java.util.Optional::stream)
                                .filter(g -> g.getOwnerId().equals(ownerId))
                                .toList();
        StringBuilder sb = new StringBuilder();
        for (DatasetGroupEntity g : groups) {
            String text = workspaceReader.readKnowledgeRules(g.getId());
            if (text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("【").append(g.getName()).append("】\n").append(text);
        }
        return sb.toString();
    }

    @Override
    public String relationsFor(String ownerId, String table) {
        return relationsFor(ownerId, table, null);
    }

    @Override
    public String relationsFor(String ownerId, String table, java.util.List<String> onlyGroups) {
        if (table == null || table.isBlank()) {
            return "";
        }
        java.util.Set<String> want =
                onlyGroups == null || onlyGroups.isEmpty()
                        ? null
                        : new java.util.HashSet<>(onlyGroups);
        List<DatasetEntity> mine = repository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        Map<String, DatasetEntity> byId = new HashMap<>();
        Set<String> matchIds = new HashSet<>();
        Set<String> groupIds = new HashSet<>();
        for (DatasetEntity d : mine) {
            if (want != null && (d.getGroupId() == null || !want.contains(d.getGroupId()))) {
                continue;
            }
            byId.put(d.getId(), d);
            if (d.getGroupId() != null) {
                groupIds.add(d.getGroupId());
            }
            if (d.getName().equalsIgnoreCase(table)
                    || d.getTableName().equalsIgnoreCase(table)
                    || d.getId().equals(table)) {
                matchIds.add(d.getId());
            }
        }
        if (matchIds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String gid : groupIds) {
            if (want != null && !want.contains(gid)) {
                continue;
            }
            for (DatasetRelationEntity r : relationRepository.findByGroupId(gid)) {
                boolean touches =
                        matchIds.contains(r.getSourceDatasetId())
                                || matchIds.contains(r.getTargetDatasetId());
                if (!touches) {
                    continue;
                }
                DatasetEntity a = byId.get(r.getSourceDatasetId());
                DatasetEntity b = byId.get(r.getTargetDatasetId());
                if (a == null || b == null) {
                    continue;
                }
                sb.append(a.getTableName());
                if (r.getSourceColumn() != null) {
                    sb.append('.').append(r.getSourceColumn());
                }
                sb.append(" ↔ ").append(b.getTableName());
                if (r.getTargetColumn() != null) {
                    sb.append('.').append(r.getTargetColumn());
                }
                sb.append("  (")
                        .append(r.getRelationType())
                        .append(", conf=")
                        .append(String.format("%.1f", r.getConfidence()))
                        .append(")\n");
                if (r.getSourceColumn() != null && r.getTargetColumn() != null) {
                    sb.append("  建议 JOIN: JOIN ")
                            .append(b.getTableName())
                            .append(" ON ")
                            .append(a.getTableName())
                            .append('.')
                            .append(r.getSourceColumn())
                            .append(" = ")
                            .append(b.getTableName())
                            .append('.')
                            .append(r.getTargetColumn())
                            .append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Persisted structured relation edges for a knowledge base (used by the graph tab). */
    public List<DatasetRelationEntity> relationsForGroup(String groupId) {
        return relationRepository.findByGroupId(groupId);
    }

    @Transactional
    public void saveKnowledge(String groupId, String content) {
        DatasetKnowledgeEntity entity =
                knowledgeRepository
                        .findById(groupId)
                        .map(
                                e -> {
                                    e.setContent(content);
                                    e.setUpdatedAt(Instant.now());
                                    return e;
                                })
                        .orElseGet(() -> new DatasetKnowledgeEntity(groupId, content));
        knowledgeRepository.save(entity);
        markMdlDirty(groupId);
        log.info("DatasetService: saved relationship knowledge for group {}", groupId);
    }

    /**
     * Flags the owning KB as DIRTY when a change affects the published MDL (specs/010 M2): the
     * previous snapshot keeps serving until the user republishes. NONE / DIRTY groups, orphan
     * datasets without a group and blank ids are no-ops.
     */
    void markMdlDirty(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        groupRepository
                .findById(groupId)
                .filter(g -> "PUBLISHED".equals(g.getMdlState()))
                .ifPresent(
                        g -> {
                            g.setMdlState("DIRTY");
                            g.setUpdatedAt(Instant.now());
                            groupRepository.save(g);
                        });
    }

    public String knowledgeText(String groupId) {
        return knowledgeRepository
                .findById(groupId)
                .map(DatasetKnowledgeEntity::getContent)
                .orElse("");
    }

    /** ISO timestamp of the knowledge doc's last update, or null when none uploaded. */
    public String knowledgeUpdatedAt(String groupId) {
        return knowledgeRepository
                .findById(groupId)
                .map(DatasetKnowledgeEntity::getUpdatedAt)
                .map(Object::toString)
                .orElse(null);
    }

    @Override
    public String relationshipsText(String ownerId) {
        return relationshipsText(ownerId, null);
    }

    @Override
    public String relationshipsText(String ownerId, java.util.List<String> onlyGroups) {
        if (ownerId == null) {
            return "";
        }
        java.util.Set<String> want =
                onlyGroups == null || onlyGroups.isEmpty()
                        ? null
                        : new java.util.HashSet<>(onlyGroups);
        StringBuilder sb = new StringBuilder();
        for (DatasetGroupEntity g : groupRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)) {
            if (want != null && !want.contains(g.getId())) {
                continue;
            }
            String content = knowledgeText(g.getId());
            if (content != null && !content.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("### ").append(g.getName()).append('\n');
                // Append ~100-char summary so the LLM can quickly gauge scope
                String desc = g.getDescription();
                String summary =
                        (desc != null && !desc.isBlank())
                                ? desc
                                : content.length() <= 100
                                        ? content.strip()
                                        : content.substring(0, 100).strip() + "…";
                sb.append("摘要：").append(summary).append('\n');
                sb.append(content);
            }
        }
        return sb.toString();
    }

    /**
     * Keyword evidence retrieval ({@link KnowledgeEvidence}): chunks each visible knowledge
     * document, scores every chunk against the query terms and renders the top {@code limit}
     * passages with a【知识库「name」› section】citation prefix. Documents short enough for
     * {@link KnowledgeEvidence#isShortDoc} are injected whole regardless of term overlap (a
     * compact KPI note beats a keyword miss). {@code null} when the owner has no visible document
     * or no long document yields a matching chunk, so {@code retrieve_evidence} keeps its stable
     * "not found" wording.
     */
    @Override
    public String evidenceFor(String ownerId, List<String> onlyGroups, String query, int limit) {
        if (ownerId == null || query == null || query.isBlank() || limit <= 0) {
            return null;
        }
        Set<String> terms = KnowledgeEvidence.terms(query);
        Set<String> want =
                onlyGroups == null || onlyGroups.isEmpty() ? null : new HashSet<>(onlyGroups);
        record Scored(String group, String title, String text, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (DatasetGroupEntity g : groupRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)) {
            if (want != null && !want.contains(g.getId())) {
                continue;
            }
            String content = knowledgeText(g.getId());
            if (content == null || content.isBlank()) {
                continue;
            }
            boolean wholeDoc = KnowledgeEvidence.isShortDoc(content);
            for (KnowledgeEvidence.Chunk c : KnowledgeEvidence.chunks(content)) {
                int score = KnowledgeEvidence.score(c, terms);
                if (score > 0 || wholeDoc) {
                    scored.add(new Scored(g.getName(), c.title(), c.text(), score));
                }
            }
        }
        if (scored.isEmpty()) {
            return null;
        }
        scored.sort(java.util.Comparator.comparingInt((Scored s) -> s.score()).reversed());
        StringBuilder sb = new StringBuilder();
        int take = Math.min(limit, scored.size());
        for (int i = 0; i < take; i++) {
            Scored s = scored.get(i);
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("【知识库「").append(s.group()).append("」");
            if (s.title() != null && !s.title().isBlank()) {
                sb.append(" › ").append(s.title());
            }
            sb.append("】\n").append(s.text().strip());
        }
        return sb.toString();
    }
}
