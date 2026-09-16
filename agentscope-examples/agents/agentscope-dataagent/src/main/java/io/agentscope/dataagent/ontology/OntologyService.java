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
package io.agentscope.dataagent.ontology;

import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.RelationInferenceService;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.ontology.model.ObjectProperty;
import io.agentscope.dataagent.ontology.model.OntologyLimitation;
import io.agentscope.dataagent.ontology.model.OntologyMetric;
import io.agentscope.dataagent.ontology.model.OntologyModel;
import io.agentscope.dataagent.ontology.model.OntologyObject;
import io.agentscope.dataagent.ontology.model.OntologyRelationship;
import io.agentscope.dataagent.ontology.model.OntologyRule;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.OntologyEntity;
import io.agentscope.dataagent.web.persistence.jpa.OntologyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本体模型核心服务：上传/自动生成/存储/图谱数据转换。
 *
 * <p>本体来源决策（互斥）：
 * <ul>
 *   <li>用户上传 model.yaml → 直接解析存储，不再生成</li>
 *   <li>用户未上传 → 从表结构 + RelationInferenceService 推断自动生成</li>
 * </ul>
 */
@Service
public class OntologyService {

    private static final Logger log = LoggerFactory.getLogger(OntologyService.class);

    private final OntologyRepository repository;
    private final OntologyParser parser;
    private final DatasetService datasetService;
    private final RelationInferenceService relationInference;

    public OntologyService(
            OntologyRepository repository,
            OntologyParser parser,
            DatasetService datasetService,
            RelationInferenceService relationInference) {
        this.repository = repository;
        this.parser = parser;
        this.datasetService = datasetService;
        this.relationInference = relationInference;
    }

    /**
     * 用户上传 model.yaml：直接解析存储，不再生成。
     */
    @Transactional
    public OntologyEntity uploadModel(String ownerId, String groupId, String yamlText) {
        OntologyModel model = parser.parse(yamlText);

        // 如果该 group 已有本体，则更新
        Optional<OntologyEntity> existing = repository.findByOwnerIdAndGroupId(ownerId, groupId);
        OntologyEntity entity =
                existing.orElseGet(
                        () -> {
                            OntologyEntity e = new OntologyEntity();
                            e.setId(UUID.randomUUID().toString());
                            e.setOwnerId(ownerId);
                            e.setGroupId(groupId);
                            return e;
                        });

        entity.setName(model.getName());
        entity.setVersion(model.getVersion());
        entity.setRawYaml(yamlText);
        entity.setParsedJson(parser.toJson(model));
        entity.setOrigin("uploaded");
        entity.setUpdatedAt(Instant.now());
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }

        repository.save(entity);
        log.info(
                "OntologyService: 上传本体 '{}' (owner={}, group={})",
                model.getName(),
                ownerId,
                groupId);
        return entity;
    }

    /**
     * 自动生成 model.yaml：根据表结构 + 业务文档（如有）生成。
     */
    @Transactional
    public OntologyEntity autoGenerateModel(
            String ownerId, String groupId, List<String> tableNames, String businessDoc) {
        OntologyModel model = new OntologyModel();
        model.setVersion("auto-v1");
        model.setName("自动生成-" + groupId);
        model.setBase_uri("https://example.org/auto/");

        // 1. 从数据集列 schema 生成 objects
        Map<String, OntologyObject> objects = new LinkedHashMap<>();
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

        for (DatasetEntity ds : datasets) {
            OntologyObject obj = new OntologyObject();
            obj.setLabel(ds.getName());
            obj.setTable(ds.getTableName());
            obj.setKind(inferKind(ds));
            obj.setIdentity(inferIdentity(ds));
            obj.setDescription(ds.getDescription());

            Map<String, ObjectProperty> props = new LinkedHashMap<>();
            for (ColumnSchema col : datasetService.readColumns(ds)) {
                ObjectProperty prop = new ObjectProperty();
                prop.setColumn(col.name());
                prop.setType(mapSqlTypeToOntologyType(col.sqlType()));
                prop.setLabel(
                        col.description() != null && !col.description().isBlank()
                                ? col.description()
                                : col.name());
                props.put(col.name(), prop);
            }
            obj.setProperties(props);
            objects.put(ds.getName(), obj);
        }
        model.setObjects(objects);

        // 2. 从 RelationInferenceService 推断 relationships
        Map<String, OntologyRelationship> relationships =
                inferRelationships(ownerId, groupId, objects);
        model.setRelationships(relationships);

        // 3. metrics/rules/limitations 留空（若有业务文档可后续由 LLM 补充）
        model.setMetrics(new LinkedHashMap<>());
        model.setRules(new LinkedHashMap<>());
        model.setLimitations(new ArrayList<>());

        String yamlText = parser.toYaml(model);
        return saveGenerated(ownerId, groupId, model, yamlText);
    }

    private OntologyEntity saveGenerated(
            String ownerId, String groupId, OntologyModel model, String yamlText) {
        Optional<OntologyEntity> existing = repository.findByOwnerIdAndGroupId(ownerId, groupId);
        OntologyEntity entity =
                existing.orElseGet(
                        () -> {
                            OntologyEntity e = new OntologyEntity();
                            e.setId(UUID.randomUUID().toString());
                            e.setOwnerId(ownerId);
                            e.setGroupId(groupId);
                            return e;
                        });

        entity.setName(model.getName());
        entity.setVersion(model.getVersion());
        entity.setRawYaml(yamlText);
        entity.setParsedJson(parser.toJson(model));
        entity.setOrigin("auto-generated");
        entity.setUpdatedAt(Instant.now());
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }

        repository.save(entity);
        log.info(
                "OntologyService: 自动生成本体 '{}' (owner={}, group={})",
                model.getName(),
                ownerId,
                groupId);
        return entity;
    }

    /**
     * 获取指定 group 的本体模型。
     */
    public Optional<OntologyModel> getOntology(String ownerId, String groupId) {
        return repository
                .findByOwnerIdAndGroupId(ownerId, groupId)
                .map(e -> parser.fromJson(e.getParsedJson()));
    }

    /** 本体模型及其来源（uploaded / auto-generated）。 */
    public record OntologyWithOrigin(OntologyModel model, String origin) {}

    /**
     * 获取指定 group 的本体模型及其来源，供前端区分用户上传与自动生成。
     */
    public Optional<OntologyWithOrigin> getOntologyWithOrigin(String ownerId, String groupId) {
        return repository
                .findByOwnerIdAndGroupId(ownerId, groupId)
                .map(
                        e ->
                                new OntologyWithOrigin(
                                        parser.fromJson(e.getParsedJson()), e.getOrigin()));
    }

    /**
     * 该 group 是否存在用户上传的本体（origin=uploaded）。数据集变化触发的自动生成不应覆盖它。
     */
    public boolean hasUploadedOntology(String ownerId, String groupId) {
        return repository
                .findByOwnerIdAndGroupId(ownerId, groupId)
                .map(e -> "uploaded".equals(e.getOrigin()))
                .orElse(false);
    }

    /**
     * 获取前端图谱数据（nodes + edges）。
     */
    public Map<String, Object> getOntologyGraph(String ownerId, String groupId) {
        Optional<OntologyModel> opt = getOntology(ownerId, groupId);
        if (opt.isEmpty()) {
            return Map.of("nodes", List.of(), "edges", List.of());
        }
        OntologyModel model = opt.get();
        return buildGraphData(model, null);
    }

    /**
     * 获取涉及指定对象的子图。
     */
    public Map<String, Object> getSubGraph(
            String ownerId, String groupId, List<String> objectNames) {
        Optional<OntologyModel> opt = getOntology(ownerId, groupId);
        if (opt.isEmpty()) {
            return Map.of("nodes", List.of(), "edges", List.of());
        }
        return buildGraphData(opt.get(), objectNames);
    }

    /**
     * 格式化的本体目录文本（供 Agent 工具返回）。
     */
    public String formatOntologyCatalog(String ownerId, String groupId) {
        Optional<OntologyModel> opt = getOntology(ownerId, groupId);
        if (opt.isEmpty()) {
            return "none: 尚未配置本体模型。请先上传 model.yaml 或自动生成。";
        }
        OntologyModel model = opt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("## 本体：")
                .append(model.getName() != null ? model.getName() : "未命名")
                .append("\n\n");

        // 业务对象
        if (!model.getObjects().isEmpty()) {
            sb.append("### 业务对象\n");
            for (Map.Entry<String, OntologyObject> e : model.getObjects().entrySet()) {
                OntologyObject obj = e.getValue();
                sb.append("- ")
                        .append(obj.getLabel() != null ? obj.getLabel() : e.getKey())
                        .append("（")
                        .append(e.getKey())
                        .append("）")
                        .append("→ ")
                        .append(obj.getTable())
                        .append(" 表 [")
                        .append(obj.getKind() != null ? obj.getKind() : "unknown")
                        .append("]");
                if (obj.getDescription() != null) {
                    sb.append(" — ").append(obj.getDescription());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 关系
        if (!model.getRelationships().isEmpty()) {
            sb.append("### 关系\n");
            for (Map.Entry<String, OntologyRelationship> e : model.getRelationships().entrySet()) {
                OntologyRelationship rel = e.getValue();
                String joinDesc =
                        rel.getJoin() != null
                                ? rel.getJoin().stream()
                                        .map(
                                                j ->
                                                        j.getOrDefault("left", "?")
                                                                + "="
                                                                + j.getOrDefault("right", "?"))
                                        .collect(Collectors.joining(", "))
                                : "?";
                sb.append("- ")
                        .append(rel.getFrom())
                        .append(" --[")
                        .append(joinDesc)
                        .append("]--> ")
                        .append(rel.getTo());
                if (rel.getLabel() != null) {
                    sb.append("（").append(rel.getLabel()).append("）");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 指标
        if (!model.getMetrics().isEmpty()) {
            sb.append("### 指标\n");
            for (Map.Entry<String, OntologyMetric> e : model.getMetrics().entrySet()) {
                OntologyMetric m = e.getValue();
                sb.append("- ")
                        .append(m.getLabel() != null ? m.getLabel() : e.getKey())
                        .append(" = ")
                        .append(m.getAggregate())
                        .append("(")
                        .append(m.getColumn() != null ? m.getColumn() : "*")
                        .append(")");
                if (m.getUnit() != null) {
                    sb.append("，单位：").append(m.getUnit());
                }
                if (m.getDescription() != null) {
                    sb.append(" — ").append(m.getDescription());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 规则
        if (!model.getRules().isEmpty()) {
            sb.append("### 规则\n");
            for (Map.Entry<String, OntologyRule> e : model.getRules().entrySet()) {
                sb.append("- ")
                        .append(e.getKey())
                        .append(": ")
                        .append(e.getValue().getStatement())
                        .append("\n");
            }
            sb.append("\n");
        }

        // 数据局限
        if (!model.getLimitations().isEmpty()) {
            sb.append("### 数据局限\n");
            for (OntologyLimitation lim : model.getLimitations()) {
                sb.append("- ").append(lim.getId()).append(": ").append(lim.getText()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().stripTrailing();
    }

    /**
     * 删除本体。
     */
    @Transactional
    public void delete(String ownerId, String ontologyId) {
        repository
                .findById(ontologyId)
                .filter(e -> e.getOwnerId().equals(ownerId))
                .ifPresent(repository::delete);
    }

    /**
     * Serialize an OntologyModel to YAML text (delegates to the parser).
     * Used by the download endpoint to export the current ontology.
     */
    public String toYaml(OntologyModel model) {
        return parser.toYaml(model);
    }

    // ---------- 内部辅助方法 ----------

    private String inferKind(DatasetEntity ds) {
        String desc = ds.getDescription();
        if (desc != null && (desc.contains("事件") || desc.contains("观察") || desc.contains("快照"))) {
            return "fact";
        }
        return "dimension";
    }

    private String inferIdentity(DatasetEntity ds) {
        List<ColumnSchema> cols = datasetService.readColumns(ds);
        for (ColumnSchema c : cols) {
            String name = c.name().toLowerCase();
            if (name.endsWith("_id") || name.equals("id")) {
                return c.name();
            }
        }
        return cols.isEmpty() ? "id" : cols.get(0).name();
    }

    private String mapSqlTypeToOntologyType(String sqlType) {
        if (sqlType == null) return "string";
        String upper = sqlType.toUpperCase();
        if (upper.contains("INT")) return "integer";
        if (upper.contains("DOUBLE") || upper.contains("FLOAT") || upper.contains("DECIMAL"))
            return "double";
        if (upper.contains("DATE") || upper.contains("TIME")) return "timestamp";
        if (upper.contains("BOOL") || upper.contains("TINYINT")) return "boolean";
        return "string";
    }

    private Map<String, OntologyRelationship> inferRelationships(
            String ownerId, String groupId, Map<String, OntologyObject> objects) {
        Map<String, OntologyRelationship> relationships = new LinkedHashMap<>();
        // 通过同名列推断简单关系
        List<Map.Entry<String, OntologyObject>> objList = new ArrayList<>(objects.entrySet());
        for (int i = 0; i < objList.size(); i++) {
            for (int j = i + 1; j < objList.size(); j++) {
                String nameA = objList.get(i).getKey();
                String nameB = objList.get(j).getKey();
                OntologyObject objA = objList.get(i).getValue();
                OntologyObject objB = objList.get(j).getValue();

                Set<String> colsA = objA.getProperties().keySet();
                Set<String> colsB = objB.getProperties().keySet();

                for (String colA : colsA) {
                    if (colsB.contains(colA) && !colA.equals("id")) {
                        String relId = nameA + "_to_" + nameB + "_via_" + colA;
                        OntologyRelationship rel = new OntologyRelationship();
                        rel.setFrom(nameA);
                        rel.setTo(nameB);
                        rel.setJoin(List.of(Map.of("left", colA, "right", colA)));
                        rel.setCardinality("many_to_many");
                        rel.setLabel("通过 " + colA + " 关联");
                        relationships.put(relId, rel);
                        break; // 每对对象只取第一个关联列
                    }
                }
            }
        }
        return relationships;
    }

    /**
     * 从 OntologyModel 构建前端图谱数据。
     *
     * @param model        本体模型
     * @param highlightObjs 需高亮的对象名列表（null 表示不高亮）
     */
    private Map<String, Object> buildGraphData(OntologyModel model, List<String> highlightObjs) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> highlight = highlightObjs != null ? Set.copyOf(highlightObjs) : Set.of();

        // 对象节点
        for (Map.Entry<String, OntologyObject> entry : model.getObjects().entrySet()) {
            String name = entry.getKey();
            OntologyObject obj = entry.getValue();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", name);
            node.put("label", obj.getLabel() != null ? obj.getLabel() : name);
            node.put("kind", obj.getKind() != null ? obj.getKind() : "dimension");
            node.put("table", obj.getTable());
            node.put("highlight", highlight.contains(name));

            List<Map<String, String>> props = new ArrayList<>();
            for (Map.Entry<String, ObjectProperty> pe : obj.getProperties().entrySet()) {
                ObjectProperty prop = pe.getValue();
                props.add(
                        Map.of(
                                "name", pe.getKey(),
                                "label", prop.getLabel() != null ? prop.getLabel() : pe.getKey(),
                                "type", prop.getType() != null ? prop.getType() : "string"));
            }
            node.put("properties", props);
            nodes.add(node);
        }

        // 关系边
        for (Map.Entry<String, OntologyRelationship> entry : model.getRelationships().entrySet()) {
            OntologyRelationship rel = entry.getValue();
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", entry.getKey());
            edge.put("source", rel.getFrom());
            edge.put("target", rel.getTo());
            edge.put("label", rel.getLabel() != null ? rel.getLabel() : entry.getKey());
            edge.put(
                    "cardinality",
                    rel.getCardinality() != null ? rel.getCardinality() : "many_to_many");
            if (rel.getJoin() != null) {
                edge.put(
                        "joinColumns",
                        rel.getJoin().stream()
                                .map(
                                        j ->
                                                j.getOrDefault("left", "?")
                                                        + "="
                                                        + j.getOrDefault("right", "?"))
                                .collect(Collectors.joining(", ")));
            }
            edges.add(edge);
        }

        // 指标节点（小标签，附属于对应对象）
        for (Map.Entry<String, OntologyMetric> entry : model.getMetrics().entrySet()) {
            OntologyMetric m = entry.getValue();
            String metricNodeId = "metric:" + entry.getKey();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", metricNodeId);
            node.put("label", m.getLabel() != null ? m.getLabel() : entry.getKey());
            node.put("kind", "metric");
            node.put("unit", m.getUnit());
            node.put("highlight", false);
            nodes.add(node);

            // 指标归属边
            if (m.getObject() != null) {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("id", "metric_edge:" + entry.getKey());
                edge.put("source", metricNodeId);
                edge.put("target", m.getObject());
                edge.put("label", "属于");
                edge.put("cardinality", "belongs_to");
                edges.add(edge);
            }
        }

        return Map.of(
                "nodes",
                nodes,
                "edges",
                edges,
                "objectCount",
                model.getObjects().size(),
                "relationshipCount",
                model.getRelationships().size(),
                "metricCount",
                model.getMetrics().size());
    }
}
