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
package io.agentscope.dataagent.semantic.builder;

import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.semantic.model.CubeDimension;
import io.agentscope.dataagent.semantic.model.CubeMeasure;
import io.agentscope.dataagent.semantic.model.CubeTimeDimension;
import io.agentscope.dataagent.semantic.model.SemanticColumn;
import io.agentscope.dataagent.semantic.model.SemanticCube;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.model.SemanticModelTable;
import io.agentscope.dataagent.semantic.model.SemanticRelationship;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 从数据表结构自动构建 SemanticModel。
 *
 * <p>处理流程：
 * <ol>
 *   <li>每个 DatasetEntity → SemanticModelTable（列 schema 映射）</li>
 *   <li>从 DatasetRelationRepository 读取已推断的关系 → SemanticRelationship</li>
 *   <li>对每个事实表自动生成 Cube（measures + dimensions + timeDimensions）</li>
 * </ol>
 */
@Component
public class SemanticModelBuilder {

    private static final Logger log = LoggerFactory.getLogger(SemanticModelBuilder.class);

    private final DatasetService datasetService;
    private final DatasetRelationRepository relationRepository;

    public SemanticModelBuilder(
            DatasetService datasetService, DatasetRelationRepository relationRepository) {
        this.datasetService = datasetService;
        this.relationRepository = relationRepository;
    }

    /**
     * 从指定知识库的数据表自动构建 SemanticModel。
     *
     * @param ownerId 用户 ID
     * @param groupId 知识库分组 ID
     * @param tableNames 要包含的表名列表（null 或空表示全部）
     * @return 自动构建的 SemanticModel
     */
    public SemanticModel build(String ownerId, String groupId, List<String> tableNames) {
        List<DatasetEntity> datasets =
                datasetService.listByOwner(ownerId).stream()
                        .filter(d -> groupId.equals(d.getGroupId()))
                        .filter(
                                d ->
                                        tableNames == null
                                                || tableNames.isEmpty()
                                                || tableNames.contains(d.getTableName())
                                                || tableNames.contains(d.getName()))
                        .toList();

        if (datasets.isEmpty()) {
            throw new IllegalArgumentException("未找到匹配的数据表");
        }

        SemanticModel model = new SemanticModel();
        model.set$schema("https://agentscope.io/semantic-model/v1.json");
        model.setLayoutVersion(1);
        model.setDescription("自动生成 — 知识库 " + groupId);
        model.setDataSource("MYSQL");

        // 1. 每个表 → SemanticModelTable
        List<SemanticModelTable> tables = new ArrayList<>();
        for (DatasetEntity ds : datasets) {
            tables.add(buildTable(ds));
        }
        model.setModels(tables);

        // 2. 从已推断的关系构建 SemanticRelationship
        model.setRelationships(buildRelationships(groupId, tables));

        // 3. 自动生成 Cube
        model.setCubes(buildCubes(tables, model.getRelationships()));

        // 4. 规则和局限留空（可由用户后续编辑补充）
        model.setRules(new ArrayList<>());
        model.setLimitations(new ArrayList<>());

        log.info(
                "SemanticModelBuilder: 自动生成 {} 个 model, {} 个 relationship, {} 个 cube",
                tables.size(),
                model.getRelationships().size(),
                model.getCubes().size());
        return model;
    }

    private SemanticModelTable buildTable(DatasetEntity ds) {
        SemanticModelTable table = new SemanticModelTable();
        table.setName(ds.getName());
        table.setTableName(ds.getTableName());
        table.setLabel(ds.getName());
        table.setDescription(ds.getDescription());
        table.setKind(inferKind(ds));

        List<SemanticColumn> columns = new ArrayList<>();
        List<ColumnSchema> schemaCols = datasetService.readColumns(ds);
        for (ColumnSchema col : schemaCols) {
            columns.add(buildColumn(col));
        }
        table.setColumns(columns);

        // 推断主键
        table.setPrimaryKey(inferPrimaryKey(schemaCols));
        return table;
    }

    private SemanticColumn buildColumn(ColumnSchema col) {
        SemanticColumn sc = new SemanticColumn();
        sc.setName(col.name());
        sc.setType(mapSqlType(col.sqlType()));
        sc.setLabel(
                col.description() != null && !col.description().isBlank()
                        ? col.description()
                        : col.name());
        return sc;
    }

    /**
     * 从 DatasetRelationRepository 读取已推断的关系。
     */
    private List<SemanticRelationship> buildRelationships(
            String groupId, List<SemanticModelTable> tables) {
        List<SemanticRelationship> relationships = new ArrayList<>();

        // 构建 datasetId → tableName 映射
        Map<String, String> idToName = new java.util.LinkedHashMap<>();
        for (SemanticModelTable t : tables) {
            idToName.put(t.getTableName(), t.getName());
        }

        List<DatasetRelationEntity> relations = relationRepository.findByGroupId(groupId);
        for (DatasetRelationEntity rel : relations) {
            String sourceName = resolveDatasetName(rel.getSourceDatasetId(), idToName);
            String targetName = resolveDatasetName(rel.getTargetDatasetId(), idToName);
            if (sourceName == null || targetName == null) {
                continue;
            }
            SemanticRelationship sr = new SemanticRelationship();
            String relName = sourceName + "_" + targetName;
            sr.setName(relName);
            sr.setModels(List.of(sourceName, targetName));
            sr.setJoinType(mapRelationTypeToJoinType(rel.getRelationType()));
            sr.setCondition(buildJoinCondition(rel, sourceName, targetName));
            sr.setLabel(relName);
            relationships.add(sr);
        }
        return relationships;
    }

    /** 从 datasetId 解析出逻辑名，先查 idToName 映射（按 tableName 匹配），再查 DatasetService。 */
    private String resolveDatasetName(String datasetId, Map<String, String> idToName) {
        if (datasetId == null) return null;
        if (idToName.containsKey(datasetId)) return idToName.get(datasetId);
        // 尝试按 datasetId 查 DatasetEntity 的 name
        try {
            List<DatasetEntity> all = datasetService.listByOwner(null);
            for (DatasetEntity ds : all) {
                if (datasetId.equals(ds.getId())) return ds.getName();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    /**
     * 为每个事实表自动生成基础 Cube。
     */
    private List<SemanticCube> buildCubes(
            List<SemanticModelTable> tables, List<SemanticRelationship> relationships) {
        List<SemanticCube> cubes = new ArrayList<>();

        // 收集关系键列名（排除作为度量）
        Set<String> relationKeyColumns = new HashSet<>();
        for (SemanticRelationship rel : relationships) {
            if (rel.getCondition() != null) {
                // 从 "a.col = b.col" 提取列名
                String[] parts = rel.getCondition().split("=");
                for (String part : parts) {
                    String trimmed = part.trim();
                    int dotIdx = trimmed.indexOf('.');
                    if (dotIdx > 0) {
                        relationKeyColumns.add(trimmed.substring(dotIdx + 1));
                    }
                }
            }
        }

        for (SemanticModelTable table : tables) {
            if (!"fact".equals(table.getKind())) {
                continue;
            }
            SemanticCube cube = buildCubeForTable(table, relationKeyColumns);
            if (cube != null) {
                cubes.add(cube);
            }
        }
        return cubes;
    }

    private SemanticCube buildCubeForTable(
            SemanticModelTable table, Set<String> relationKeyColumns) {
        SemanticCube cube = new SemanticCube();
        cube.setName(table.getName() + "_metrics");
        cube.setBaseObject(table.getName());
        cube.setLabel(table.getLabel() + " 指标");
        cube.setDescription("自动生成 — " + table.getName() + " 的聚合指标");

        List<CubeMeasure> measures = new ArrayList<>();
        List<CubeDimension> dimensions = new ArrayList<>();
        List<CubeTimeDimension> timeDimensions = new ArrayList<>();

        for (SemanticColumn col : table.getColumns()) {
            if (isExcludedFromMeasure(col.getName(), table.getPrimaryKey(), relationKeyColumns)) {
                continue;
            }

            if (isNumericType(col.getType())) {
                // 数值列 → SUM measure
                CubeMeasure sumMeasure = new CubeMeasure();
                sumMeasure.setName(table.getName() + "_" + col.getName() + "_sum");
                sumMeasure.setExpression("SUM(" + col.getName() + ")");
                sumMeasure.setType("DOUBLE");
                sumMeasure.setDescription(col.getLabel() + " 合计");
                measures.add(sumMeasure);
            } else if (isTimeType(col.getType())) {
                // 时间列 → timeDimension
                CubeTimeDimension td = new CubeTimeDimension();
                td.setName(col.getName());
                td.setExpression(col.getName());
                td.setType(col.getType());
                td.setDescription(col.getLabel());
                timeDimensions.add(td);
            } else {
                // 字符串列 → dimension
                CubeDimension dim = new CubeDimension();
                dim.setName(col.getName());
                dim.setExpression(col.getName());
                dim.setType(col.getType());
                dim.setDescription(col.getLabel());
                dimensions.add(dim);
            }
        }

        // 通用 COUNT(*) measure
        CubeMeasure countMeasure = new CubeMeasure();
        countMeasure.setName(table.getName() + "_row_count");
        countMeasure.setExpression("COUNT(*)");
        countMeasure.setType("BIGINT");
        countMeasure.setDescription(table.getLabel() + " 行数（仅供排查）");
        measures.add(countMeasure);

        if (measures.isEmpty()) {
            return null;
        }

        cube.setMeasures(measures);
        cube.setDimensions(dimensions);
        cube.setTimeDimensions(timeDimensions);
        return cube;
    }

    // ---- 辅助方法 ----

    private String inferKind(DatasetEntity ds) {
        String name = ds.getName() != null ? ds.getName().toLowerCase() : "";
        if (name.contains("dim")
                || name.contains("user")
                || name.contains("object")
                || name.contains("config")
                || name.contains("master")) {
            return "dimension";
        }
        return "fact";
    }

    private String inferPrimaryKey(List<ColumnSchema> cols) {
        for (ColumnSchema col : cols) {
            String lower = col.name().toLowerCase();
            if (lower.endsWith("_id") || lower.equals("id")) {
                return col.name();
            }
        }
        return cols.isEmpty() ? null : cols.get(0).name();
    }

    private boolean isExcludedFromMeasure(
            String colName, String primaryKey, Set<String> relationKeyColumns) {
        if (colName.equals(primaryKey)) {
            return true;
        }
        if (relationKeyColumns.contains(colName)) {
            return true;
        }
        String lower = colName.toLowerCase();
        return lower.endsWith("_id") || lower.equals("id");
    }

    private boolean isNumericType(String type) {
        if (type == null) {
            return false;
        }
        String upper = type.toUpperCase();
        return upper.contains("INT")
                || upper.contains("DOUBLE")
                || upper.contains("DECIMAL")
                || upper.contains("FLOAT")
                || upper.contains("NUMERIC")
                || upper.contains("BIGINT");
    }

    private boolean isTimeType(String type) {
        if (type == null) {
            return false;
        }
        String upper = type.toUpperCase();
        return upper.contains("DATE") || upper.contains("TIME") || upper.contains("TIMESTAMP");
    }

    private String mapSqlType(String sqlType) {
        if (sqlType == null) {
            return "VARCHAR";
        }
        String upper = sqlType.toUpperCase();
        if (upper.contains("INT")) return "INTEGER";
        if (upper.contains("DOUBLE") || upper.contains("FLOAT")) return "DOUBLE";
        if (upper.contains("DECIMAL") || upper.contains("NUMERIC")) return "DECIMAL";
        if (upper.contains("BIGINT")) return "BIGINT";
        if (upper.contains("BOOL")) return "BOOLEAN";
        if (upper.contains("TIMESTAMP") || upper.contains("DATETIME")) return "TIMESTAMP";
        if (upper.contains("DATE")) return "DATE";
        return "VARCHAR";
    }

    private String mapRelationTypeToJoinType(String relationType) {
        if (relationType == null) return "MANY_TO_ONE";
        return switch (relationType.toUpperCase()) {
            case "SAME_COLUMN", "DOC", "LLM" -> "MANY_TO_ONE";
            case "SUFFIX" -> "MANY_TO_ONE";
            case "ONE_TO_MANY" -> "ONE_TO_MANY";
            case "MANY_TO_MANY" -> "MANY_TO_MANY";
            case "ONE_TO_ONE" -> "ONE_TO_ONE";
            default -> "MANY_TO_ONE";
        };
    }

    private String buildJoinCondition(
            DatasetRelationEntity rel, String sourceName, String targetName) {
        if (rel.getSourceColumn() != null && rel.getTargetColumn() != null) {
            return sourceName
                    + "."
                    + rel.getSourceColumn()
                    + " = "
                    + targetName
                    + "."
                    + rel.getTargetColumn();
        }
        return sourceName + ".id = " + targetName + ".id";
    }
}
