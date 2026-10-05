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
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Read-only parser over a group's workspace (specs/019 M2): turns the official YAML files into
 * the structured view the modeling page, the publish chain and the {@code mdl.json} manifest
 * need. Parse failures never throw — they become error issues so a broken agent edit surfaces
 * with its file name instead of a 500.
 */
@Component
public class MdlWorkspaceReader {

    private static final Logger log = LoggerFactory.getLogger(MdlWorkspaceReader.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    /** One equality term of a relation condition: {@code <model>.<column> = <model>.<column>}. */
    private static final Pattern CONDITION_TERM =
            Pattern.compile("([^\\s.]+)\\.([^\\s.]+)\\s*=\\s*([^\\s.]+)\\.([^\\s.]+)");

    private final WrenProperties props;
    private final MdlWorkspaceService workspace;
    private final DatasetRepository datasetRepository;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public MdlWorkspaceReader(
            WrenProperties props,
            MdlWorkspaceService workspace,
            DatasetRepository datasetRepository) {
        this.props = props;
        this.workspace = workspace;
        this.datasetRepository = datasetRepository;
    }

    public record WorkspaceColumn(
            String name,
            String type,
            String description,
            String relationship,
            boolean calculated,
            String expression) {}

    /**
     * One logical model. A derived (ref_sql) model has no {@code table_reference}: {@code
     * refSql} carries the defining SQL and {@code refSqlPath} the workspace file it was read
     * from ({@code models/<name>/ref_sql.sql} for the file layout, {@code metadata.yml} for the
     * inline key). Both are {@code null} for physical models.
     */
    public record WorkspaceModel(
            String name,
            String datasetId,
            String datasetName,
            String schemaName,
            String tableName,
            String description,
            List<WorkspaceColumn> columns,
            String path,
            String refSql,
            String refSqlPath) {}

    public record WorkspaceRelation(
            String name,
            String leftModel,
            String rightModel,
            String joinType,
            String condition,
            List<String> sourceColumns,
            List<String> targetColumns) {}

    public record WorkspaceMember(
            String name, String expression, String type, String description) {}

    public record WorkspaceCube(
            String name,
            String baseModel,
            String description,
            List<WorkspaceMember> measures,
            List<WorkspaceMember> dimensions,
            List<WorkspaceMember> timeDimensions,
            String path) {}

    /** {@code path} is the workspace-relative YAML file this view was parsed from. */
    public record WorkspaceView(String name, String sql, String description, String path) {}

    public record Snapshot(
            List<MdlPublishService.MdlFile> files,
            List<MdlPublishService.MdlIssue> issues,
            List<WorkspaceModel> models,
            List<WorkspaceRelation> relations,
            List<WorkspaceCube> cubes,
            List<WorkspaceView> views) {}

    /** Lists every publishable workspace file; empty when no workspace exists. */
    public List<MdlPublishService.MdlFile> listFiles(String groupId) {
        Path root = workspace.workspaceRoot(groupId);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<MdlPublishService.MdlFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (isInternal(rel)) {
                    continue;
                }
                out.add(
                        new MdlPublishService.MdlFile(
                                rel, Files.readString(p, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            log.warn(
                    "MdlWorkspaceReader: could not list workspace files for group {}: {}",
                    groupId,
                    e.getMessage());
            return List.of();
        }
        return out;
    }

    /** Parses the whole workspace; parse failures become error issues, never exceptions. */
    public Snapshot read(String groupId) {
        List<MdlPublishService.MdlIssue> issues = new ArrayList<>();
        Path root = workspace.workspaceRoot(groupId);
        if (!Files.isDirectory(root)) {
            return new Snapshot(List.of(), issues, List.of(), List.of(), List.of(), List.of());
        }
        Map<String, String> datasetByModel = reverseMapping(groupId);
        List<WorkspaceModel> models = new ArrayList<>();
        for (Path dir : subDirs(root.resolve("models"))) {
            String dirName = dir.getFileName().toString();
            Path meta = dir.resolve("metadata.yml");
            if (!Files.isRegularFile(meta)) {
                // A skipped directory must be visible: a half-written model (e.g. metadata.yml
                // deleted while ref_sql.sql remains) used to vanish without a trace.
                issues.add(
                        issue("error", "models/" + dirName + " 缺少 metadata.yml，该模型已被跳过", "local"));
                continue;
            }
            try {
                Map<String, Object> parsed =
                        yaml.readValue(Files.readString(meta, StandardCharsets.UTF_8), MAP_TYPE);
                Path refSqlFile = dir.resolve("ref_sql.sql");
                String refSql = null;
                String refSqlPath = null;
                if (Files.isRegularFile(refSqlFile)) {
                    // File layout wins over the inline key (official loader semantics).
                    refSql = Files.readString(refSqlFile, StandardCharsets.UTF_8);
                    refSqlPath = "models/" + dirName + "/ref_sql.sql";
                } else {
                    String inline = text(parsed.get("ref_sql"), null);
                    if (inline != null) {
                        refSql = inline;
                        refSqlPath = "models/" + dirName + "/metadata.yml";
                    }
                }
                models.add(parseModel(dirName, parsed, datasetByModel, refSql, refSqlPath));
            } catch (IOException | RuntimeException e) {
                issues.add(
                        issue(
                                "error",
                                "无法解析 models/"
                                        + dir.getFileName()
                                        + "/metadata.yml："
                                        + e.getMessage(),
                                "local"));
            }
        }
        List<WorkspaceRelation> relations = new ArrayList<>();
        Path relationsFile = root.resolve("relationships.yml");
        if (Files.isRegularFile(relationsFile)) {
            try {
                Map<String, Object> parsed =
                        yaml.readValue(
                                Files.readString(relationsFile, StandardCharsets.UTF_8), MAP_TYPE);
                if (parsed.get("relationships") instanceof List<?> entries) {
                    for (Object entry : entries) {
                        if (entry instanceof Map<?, ?> rel) {
                            relations.add(parseRelation(rel));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                issues.add(issue("error", "无法解析 relationships.yml：" + e.getMessage(), "local"));
            }
        }
        List<WorkspaceCube> cubes = new ArrayList<>();
        for (Path dir : subDirs(root.resolve("cubes"))) {
            Path meta = dir.resolve("metadata.yml");
            if (!Files.isRegularFile(meta)) {
                continue;
            }
            try {
                Map<String, Object> parsed =
                        yaml.readValue(Files.readString(meta, StandardCharsets.UTF_8), MAP_TYPE);
                cubes.add(
                        new WorkspaceCube(
                                text(parsed.get("name"), dir.getFileName().toString()),
                                text(parsed.get("base_object"), ""),
                                text(parsed.get("description"), null),
                                members(parsed.get("measures")),
                                members(parsed.get("dimensions")),
                                members(parsed.get("time_dimensions")),
                                "cubes/" + dir.getFileName() + "/metadata.yml"));
            } catch (IOException | RuntimeException e) {
                issues.add(
                        issue(
                                "error",
                                "无法解析 cubes/"
                                        + dir.getFileName()
                                        + "/metadata.yml："
                                        + e.getMessage(),
                                "local"));
            }
        }
        List<WorkspaceView> views = new ArrayList<>();
        for (Path dir : subDirs(root.resolve("views"))) {
            Path meta = dir.resolve("metadata.yml");
            Path sqlFile = dir.resolve("sql.yml");
            if (!Files.isRegularFile(meta)) {
                issues.add(
                        issue(
                                "error",
                                "views/" + dir.getFileName() + " 缺少 metadata.yml，该视图已被跳过",
                                "local"));
                continue;
            }
            try {
                Map<String, Object> parsed =
                        yaml.readValue(Files.readString(meta, StandardCharsets.UTF_8), MAP_TYPE);
                String statement = "";
                if (Files.isRegularFile(sqlFile)) {
                    Map<String, Object> sql =
                            yaml.readValue(
                                    Files.readString(sqlFile, StandardCharsets.UTF_8), MAP_TYPE);
                    statement = text(sql.get("statement"), "");
                }
                views.add(
                        new WorkspaceView(
                                text(parsed.get("name"), dir.getFileName().toString()),
                                statement,
                                propertiesDescription(parsed),
                                "views/" + dir.getFileName() + "/sql.yml"));
            } catch (IOException | RuntimeException e) {
                issues.add(
                        issue(
                                "error",
                                "无法解析 views/" + dir.getFileName() + "：" + e.getMessage(),
                                "local"));
            }
        }
        return new Snapshot(listFiles(groupId), issues, models, relations, cubes, views);
    }

    /**
     * Concatenates the group's workspace business rules ({@code knowledge/rules/*.md}, sorted by
     * file name — mirrors the official {@code load_knowledge_rules}). Empty string when the
     * workspace or the directory does not exist; unreadable files are skipped with a warn log.
     * specs/019 §7: this is the query-side knowledge injection source.
     */
    public String readKnowledgeRules(String groupId) {
        Path dir = workspace.workspaceRoot(groupId).resolve("knowledge").resolve("rules");
        if (!Files.isDirectory(dir)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(MdlWorkspaceReader::isRuleFile).sorted().toList()) {
                try {
                    String text = Files.readString(p, StandardCharsets.UTF_8).strip();
                    if (!text.isEmpty()) {
                        parts.add(text);
                    }
                } catch (IOException e) {
                    log.warn(
                            "MdlWorkspaceReader: unreadable rule file {} for group {}: {}",
                            p.getFileName(),
                            groupId,
                            e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn(
                    "MdlWorkspaceReader: could not list knowledge rules for group {}: {}",
                    groupId,
                    e.getMessage());
            return "";
        }
        return String.join("\n\n", parts);
    }

    private static boolean isRuleFile(Path p) {
        return Files.isRegularFile(p)
                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md");
    }

    private WorkspaceModel parseModel(
            String dirName,
            Map<String, Object> root,
            Map<String, String> datasetByModel,
            String refSql,
            String refSqlPath) {
        String name = text(root.get("name"), dirName);
        String datasetId = datasetByModel.get(name);
        String datasetName = datasetId == null ? name : datasetName(datasetId, name);
        Map<String, Object> tableRef = asMap(root.get("table_reference"));
        String schemaName = tableRef == null ? "" : text(tableRef.get("schema"), "");
        String tableName = tableRef == null ? "" : text(tableRef.get("table"), "");
        List<WorkspaceColumn> columns = new ArrayList<>();
        if (root.get("columns") instanceof List<?> entries) {
            for (Object entry : entries) {
                Map<String, Object> col = asMap(entry);
                if (col == null) {
                    continue;
                }
                String cname = text(col.get("name"), "");
                if (cname.isEmpty()) {
                    continue;
                }
                columns.add(
                        new WorkspaceColumn(
                                cname,
                                text(col.get("type"), ""),
                                propertiesDescription(col),
                                text(col.get("relationship"), null),
                                Boolean.TRUE.equals(col.get("is_calculated")),
                                text(col.get("expression"), null)));
            }
        }
        return new WorkspaceModel(
                name,
                datasetId,
                datasetName,
                schemaName,
                tableName,
                propertiesDescription(root),
                columns,
                "models/" + dirName + "/metadata.yml",
                refSql,
                refSqlPath);
    }

    private WorkspaceRelation parseRelation(Map<?, ?> rel) {
        List<String> models = new ArrayList<>();
        if (rel.get("models") instanceof List<?> list) {
            for (Object m : list) {
                models.add(text(m, ""));
            }
        }
        String left = models.size() > 0 ? models.get(0) : "";
        String right = models.size() > 1 ? models.get(1) : "";
        String condition = text(rel.get("condition"), "");
        List<String> sourceColumns = new ArrayList<>();
        List<String> targetColumns = new ArrayList<>();
        for (String part : condition.split("\\s+AND\\s+")) {
            Matcher m = CONDITION_TERM.matcher(part.strip());
            if (!m.matches()) {
                continue;
            }
            if (m.group(1).equals(left) && m.group(3).equals(right)) {
                sourceColumns.add(m.group(2));
                targetColumns.add(m.group(4));
            } else if (m.group(1).equals(right) && m.group(3).equals(left)) {
                sourceColumns.add(m.group(4));
                targetColumns.add(m.group(2));
            }
        }
        return new WorkspaceRelation(
                text(rel.get("name"), ""),
                left,
                right,
                text(rel.get("join_type"), ""),
                condition,
                sourceColumns,
                targetColumns);
    }

    private static List<WorkspaceMember> members(Object value) {
        List<WorkspaceMember> out = new ArrayList<>();
        if (value instanceof List<?> entries) {
            for (Object entry : entries) {
                Map<String, Object> m = asMap(entry);
                if (m == null) {
                    continue;
                }
                out.add(
                        new WorkspaceMember(
                                text(m.get("name"), ""),
                                text(m.get("expression"), ""),
                                text(m.get("type"), ""),
                                text(m.get("description"), null)));
            }
        }
        return out;
    }

    /** Reverse of the platform mapping: model name -> dataset id (agent-created files miss it). */
    private Map<String, String> reverseMapping(String groupId) {
        Map<String, String> out = new HashMap<>();
        Path file = workspace.workspaceRoot(groupId).resolve(".platform").resolve("datasets.json");
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            Map<String, String> mapping =
                    json.readValue(
                            Files.readString(file, StandardCharsets.UTF_8),
                            new TypeReference<Map<String, String>>() {});
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                out.put(e.getValue(), e.getKey());
            }
        } catch (IOException e) {
            log.warn(
                    "MdlWorkspaceReader: unreadable dataset mapping for group {}: {}",
                    groupId,
                    e.getMessage());
        }
        return out;
    }

    private String datasetName(String datasetId, String fallback) {
        return datasetRepository
                .findById(datasetId)
                .map(DatasetEntity::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(fallback);
    }

    private static String propertiesDescription(Map<String, Object> owner) {
        Map<String, Object> props = asMap(owner.get("properties"));
        return props == null ? null : text(props.get("description"), null);
    }

    private static List<Path> subDirs(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isInternal(String rel) {
        return rel.equals(".platform")
                || rel.startsWith(".platform/")
                || rel.equals("target")
                || rel.startsWith("target/");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).strip();
        return s.isEmpty() ? fallback : s;
    }

    private static MdlPublishService.MdlIssue issue(
            String severity, String message, String source) {
        return new MdlPublishService.MdlIssue(severity, message, source);
    }
}
