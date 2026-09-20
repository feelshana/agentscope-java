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
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermRepository;
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

    private final DatasetRepository repository;
    private final DatasetGroupRepository groupRepository;
    private final DatasetKnowledgeRepository knowledgeRepository;
    private final SemanticTermRepository semanticTerms;
    private final ExternalDataSourceRepository externalSources;
    private final DataSourceIntrospector introspector;
    private final RelationInferenceService relationInference;
    private final DatasetRelationRepository relationRepository;
    private final InMemoryDataSourceRegistry registry;
    private final TableProvisioner provisioner;
    private final List<FileParser> parsers;
    private final DatasetStoreProperties props;
    private final ObjectMapper mapper;
    private final DatasetImportService importService;

    public DatasetService(
            DatasetRepository repository,
            DatasetGroupRepository groupRepository,
            DatasetKnowledgeRepository knowledgeRepository,
            SemanticTermRepository semanticTerms,
            ExternalDataSourceRepository externalSources,
            DataSourceIntrospector introspector,
            RelationInferenceService relationInference,
            DatasetRelationRepository relationRepository,
            InMemoryDataSourceRegistry registry,
            TableProvisioner provisioner,
            List<FileParser> parsers,
            DatasetStoreProperties props,
            ObjectMapper mapper,
            DatasetImportService importService) {
        this.repository = repository;
        this.groupRepository = groupRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.semanticTerms = semanticTerms;
        this.externalSources = externalSources;
        this.introspector = introspector;
        this.relationInference = relationInference;
        this.relationRepository = relationRepository;
        this.registry = registry;
        this.provisioner = provisioner;
        this.parsers = parsers;
        this.props = props;
        this.mapper = mapper;
        this.importService = importService;
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
        relationInference.reinferGroup(group.getId());
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
        boolean sample = sampling && ds.isSampling();
        List<DatasetEntity> created = new ArrayList<>();
        for (String table : tables) {
            repository
                    .findByOwnerIdAndGroupIdAndName(ownerId, groupId, table)
                    .ifPresent(
                            e -> {
                                throw new DatasetException(
                                        "Dataset name already exists: " + table, 409);
                            });
            List<ColumnSchema> schemaCols = new ArrayList<>();
            for (DataSourceIntrospector.ColumnInfo ci :
                    introspector.listColumns(ds, schema, table)) {
                String description = ci.description();
                if (sample) {
                    String sampleVals = sampleValues(ds, schema, table, ci.name());
                    if (sampleVals != null) {
                        description =
                                (description == null || description.isBlank()
                                                ? ""
                                                : description + " ")
                                        + "示例: "
                                        + sampleVals;
                    }
                }
                schemaCols.add(
                        new ColumnSchema(ci.name(), ci.name(), ci.type(), true, description));
            }
            DatasetEntity e = new DatasetEntity();
            e.setId(UUID.randomUUID().toString());
            e.setOwnerId(ownerId);
            e.setGroupId(groupId);
            e.setName(table);
            e.setOrigin("datasource");
            e.setExternalDataSourceId(ds.getId());
            e.setSchemaName(schema);
            e.setTableName(table);
            e.setDescription("外部数据源 " + ds.getName() + " 的表 " + schema + "." + table);
            e.setColumnSchemaJson(writeJson(schemaCols));
            e.setRowCount(introspector.countRows(ds, schema, table));
            e.setSourceFileName(ds.getName() + "/" + schema + "." + table);
            e.setCreatedAt(Instant.now());
            e.setUpdatedAt(Instant.now());
            repository.save(e);
            registry.add(toDataSource(e));
            created.add(e);
        }
        relationInference.reinferGroup(groupId);
        log.info(
                "DatasetService: associated {} table(s) from datasource {} into group {} for owner"
                        + " {}",
                created.size(),
                dataSourceId,
                groupId,
                ownerId);
        return created;
    }

    /** Distinct values for a column when cardinality is low (<=20); null otherwise. */
    private String sampleValues(
            ExternalDataSourceEntity ds, String schema, String table, String column) {
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
            return String.join(", ", vals);
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
            return List.of();
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
        return entity;
    }

    private String writeJson(List<ColumnSchema> columns) {
        try {
            return mapper.writeValueAsString(columns);
        } catch (Exception e) {
            return "[]";
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
        // Store column schema JSON for prepare_data_context to use
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
            return q + e.getSchemaName() + q + "." + q + e.getTableName() + q;
        }
        return "`" + e.getTableName() + "`";
    }

    @Override
    public String semanticTermsText() {
        StringBuilder sb = new StringBuilder();
        for (SemanticTermEntity t : semanticTerms.findAllByOrderByCreatedAtDesc()) {
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
            if (t.getScope() != null && !"global".equals(t.getScope())) {
                sb.append("［范围：").append(t.getScope()).append("］");
            }
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
        relationInference.reinferGroup(groupId);
        log.info("DatasetService: saved relationship knowledge for group {}", groupId);
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
}
