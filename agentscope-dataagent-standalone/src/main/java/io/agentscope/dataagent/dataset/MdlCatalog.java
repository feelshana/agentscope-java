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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Read-only catalog over the {@code mdl.json} manifest a successful publish writes next to the
 * snapshot ({@code <mdlRoot>/<groupId>/mdl.json}, specs/010 M2/M3, ADR 0018 D3/D8).
 *
 * <p>The prompt middleware uses the manifest as the lightweight logical-model directory, while
 * {@code wren_describe_model} reads it for scoped field, relationship and Cube details. The same
 * published snapshot is loaded by the Wren runtime, so a readable manifest identifies the version
 * that can answer queries. Unreadable or malformed manifests degrade to {@link Optional#empty()}
 * and make the semantic channel unavailable; there is no physical-query fallback.
 */
@Component
public class MdlCatalog {

    private static final Logger log = LoggerFactory.getLogger(MdlCatalog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WrenProperties props;

    public MdlCatalog(WrenProperties props) {
        this.props = props;
    }

    /**
     * Loads the manifest of a group's last successful publish; empty when the group has never
     * been published (no {@code mdl.json}) or the file cannot be parsed.
     */
    public Optional<GroupMdl> load(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return Optional.empty();
        }
        try {
            Path manifest = props.groupRoot(groupId).resolve("mdl.json");
            if (!Files.isRegularFile(manifest)) {
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
            return Optional.of(parse(root));
        } catch (IOException | RuntimeException e) {
            log.warn("MdlCatalog: unreadable mdl.json for group {}: {}", groupId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Reads only published, human-confirmed examples; draft content and old result values are excluded. */
    public List<String> confirmedExamples(String groupId) {
        Path root = props.groupRoot(groupId).resolve("published/knowledge/sql");
        if (!Files.isDirectory(root)) return List.of();
        try (var files = Files.list(root)) {
            List<String> out = new ArrayList<>();
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".md"))
                            .filter(p -> !Files.isSymbolicLink(p))
                            .sorted()
                            .limit(50)
                            .toList()) {
                if (Files.size(file) > 65536) continue;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if ((text.contains("\nverified: true\n") && text.contains("\nvalidation_id: "))
                        || !SqlExampleRecall.published(List.of(text)).isEmpty()) out.add(text);
            }
            return out;
        } catch (IOException e) {
            throw new DatasetException("读取已发布问题示例失败", e);
        }
    }

    /** Reads the published source metadata, never draft properties or sample row values. */
    public java.util.Map<String, Object> dateGateContext(String groupId) {
        var manifest = load(groupId).orElseThrow(() -> new DatasetException("已发布模型目录不可用", 409));
        java.util.List<Object> models = new java.util.ArrayList<>();
        var yaml =
                new com.fasterxml.jackson.databind.ObjectMapper(
                        new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        Path root = props.groupRoot(groupId).resolve("published");
        java.util.Map<String, JsonNode> properties = new java.util.HashMap<>();
        Path modelRoot = root.resolve("models");
        if (java.nio.file.Files.isDirectory(modelRoot)) {
            try (var paths = java.nio.file.Files.walk(modelRoot)) {
                for (Path path :
                        paths.filter(p -> p.getFileName().toString().equals("metadata.yml"))
                                .toList()) {
                    if (java.nio.file.Files.isSymbolicLink(path)
                            || !path.toRealPath().startsWith(root.toRealPath())) continue;
                    JsonNode doc = yaml.readTree(path.toFile());
                    properties.put(doc.path("name").asText(), doc.path("properties"));
                }
            } catch (Exception e) {
                throw new DatasetException("无法读取已发布日期元数据", 409);
            }
        }
        for (Model m : manifest.models()) {
            JsonNode p = properties.getOrDefault(m.name(), yaml.createObjectNode());
            String role =
                    p.path("table_role_confirmed").asBoolean(false)
                            ? p.path("table_role").asText("UNKNOWN")
                            : "UNKNOWN";
            models.add(
                    java.util.Map.of(
                            "name",
                            m.name(),
                            "role",
                            role,
                            "temporalColumns",
                            p.path("temporal_columns"),
                            "analysisDateColumns",
                            p.path("analysis_date_columns"),
                            "columns",
                            m.columns().stream()
                                    .map(
                                            c ->
                                                    java.util.Map.of(
                                                            "name",
                                                            c.name(),
                                                            "type",
                                                            c.type() == null ? "" : c.type()))
                                    .toList()));
        }
        StringBuilder rules = new StringBuilder();
        Path rulesRoot = root.resolve("knowledge/rules");
        if (java.nio.file.Files.isDirectory(rulesRoot)) {
            try (var paths = java.nio.file.Files.walk(rulesRoot)) {
                for (Path path :
                        paths.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
                    if (!path.toRealPath().startsWith(root.toRealPath()) || rules.length() >= 6000)
                        continue;
                    String text = java.nio.file.Files.readString(path);
                    rules.append(text, 0, Math.min(text.length(), 6000 - rules.length()));
                }
            } catch (Exception e) {
                throw new DatasetException("无法读取已发布规则", 409);
            }
        }
        return java.util.Map.of(
                "group",
                groupId,
                "version",
                manifest.version(),
                "models",
                models,
                "rules",
                rules.toString());
    }

    private static GroupMdl parse(JsonNode root) {
        List<Model> models = new ArrayList<>();
        for (JsonNode m : root.path("models")) {
            List<Column> columns = new ArrayList<>();
            for (JsonNode c : m.path("columns")) {
                columns.add(
                        new Column(
                                text(c, "name"),
                                text(c, "type"),
                                text(c, "description"),
                                c.path("calculated").asBoolean(false),
                                text(c, "expression")));
            }
            models.add(
                    new Model(
                            text(m, "name"),
                            text(m, "datasetId"),
                            text(m, "description"),
                            columns,
                            m.path("derived").asBoolean(false)));
        }
        List<Relation> relations = new ArrayList<>();
        for (JsonNode r : root.path("relations")) {
            relations.add(
                    new Relation(
                            text(r, "leftModel"),
                            text(r, "rightModel"),
                            text(r, "joinType"),
                            text(r, "condition")));
        }
        List<Cube> cubes = new ArrayList<>();
        for (JsonNode c : root.path("cubes")) {
            cubes.add(
                    new Cube(
                            text(c, "name"),
                            text(c, "baseModel"),
                            text(c, "description"),
                            members(c.path("measures")),
                            members(c.path("dimensions")),
                            members(c.path("timeDimensions"))));
        }
        List<View> views = new ArrayList<>();
        for (JsonNode v : root.path("views")) {
            views.add(new View(text(v, "name"), text(v, "sql"), text(v, "description")));
        }
        return new GroupMdl(
                text(root, "groupId"),
                text(root, "groupName"),
                root.path("version").asInt(0),
                models,
                relations,
                cubes,
                views);
    }

    private static List<Member> members(JsonNode array) {
        List<Member> out = new ArrayList<>();
        for (JsonNode m : array) {
            out.add(
                    new Member(
                            text(m, "name"),
                            text(m, "expression"),
                            text(m, "type"),
                            text(m, "description")));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /**
     * Content of one publish: logical models (columns included), modeled relations, Cubes and
     * Views, i.e. everything needed to describe and enforce the wren query channel.
     */
    public record GroupMdl(
            String groupId,
            String groupName,
            int version,
            List<Model> models,
            List<Relation> relations,
            List<Cube> cubes,
            List<View> views) {

        /** Backward-compatible constructor for fixtures and callers without published Views. */
        public GroupMdl(
                String groupId,
                String groupName,
                int version,
                List<Model> models,
                List<Relation> relations,
                List<Cube> cubes) {
            this(groupId, groupName, version, models, relations, cubes, List.of());
        }

        /** Dataset ids covered by this successfully published manifest. */
        public Set<String> coveredDatasetIds() {
            Set<String> ids = new HashSet<>();
            for (Model m : models) {
                if (m.datasetId() != null && !m.datasetId().isBlank()) {
                    ids.add(m.datasetId());
                }
            }
            return ids;
        }
    }

    /**
     * One logical model of a published manifest. {@code derived} marks a ref_sql model whose
     * SQL the engine inlines at plan time (specs/034, ADR 0042).
     */
    public record Model(
            String name,
            String datasetId,
            String description,
            List<Column> columns,
            boolean derived) {

        /** Backward-compatible constructor for fixtures and pre-specs/034 manifests. */
        public Model(String name, String datasetId, String description, List<Column> columns) {
            this(name, datasetId, description, columns, false);
        }
    }

    public record Column(
            String name, String type, String description, boolean calculated, String expression) {}

    public record Relation(
            String leftModel, String rightModel, String joinType, String condition) {}

    public record Cube(
            String name,
            String baseModel,
            String description,
            List<Member> measures,
            List<Member> dimensions,
            List<Member> timeDimensions) {}

    public record View(String name, String sql, String description) {}

    public record Member(String name, String expression, String type, String description) {}
}
