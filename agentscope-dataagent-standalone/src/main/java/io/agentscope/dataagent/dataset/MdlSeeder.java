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
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Converges the group's workspace with the current datasets (specs/019 M1, ADR 0033 D2): new
 * datasets get a seed model file, existing ones get missing physical columns appended, deleted
 * ones are removed with dangling-reference reports. Agent edits are never overwritten — only
 * append-only additions happen; conflicts are reported as errors for a human/agent fix.
 *
 * <p>The dataset-to-model mapping lives in the platform-owned {@code .platform/datasets.json}.
 * Column appends use a line-oriented text surgery that preserves the agent's formatting and
 * comments (see {@link #appendColumns}).
 */
@Service
public class MdlSeeder {

    private static final Logger log = LoggerFactory.getLogger(MdlSeeder.class);

    /** Top-level {@code columns:} key (optionally with a trailing comment). */
    private static final Pattern COLUMNS_KEY = Pattern.compile("^columns:\\s*(#.*)?$");

    /** Indent of one column entry inside the columns block. */
    private static final Pattern ENTRY_INDENT = Pattern.compile("^(\\s*)-\\s");

    private final DatasetRepository datasetRepository;
    private final MdlWorkspaceService workspace;
    private final WrenTypeNormalizer typeNormalizer;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public MdlSeeder(
            DatasetRepository datasetRepository,
            MdlWorkspaceService workspace,
            WrenTypeNormalizer typeNormalizer) {
        this.datasetRepository = datasetRepository;
        this.workspace = workspace;
        this.typeNormalizer = typeNormalizer;
    }

    /** Outcome of one reconcile run; errors block publishing, warnings only surface. */
    public record SeedResult(List<MdlPublishService.MdlIssue> issues, boolean changed) {

        public boolean hasErrors() {
            return issues().stream().anyMatch(i -> "error".equals(i.severity()));
        }

        public String errorSummary() {
            return issues().stream()
                    .filter(i -> "error".equals(i.severity()))
                    .map(MdlPublishService.MdlIssue::message)
                    .findFirst()
                    .orElse("播种失败");
        }
    }

    /**
     * Converges the workspace with the current datasets under the group's workspace lock.
     * Callers inside the publish chain already hold the publish lock — the nesting direction
     * publish → workspace is the only one allowed.
     */
    public SeedResult reconcile(String groupId) {
        return workspace.withWorkspaceLock(groupId, () -> doReconcile(groupId));
    }

    private SeedResult doReconcile(String groupId) {
        workspace.ensureWorkspace(groupId);
        List<MdlPublishService.MdlIssue> issues = new ArrayList<>();
        boolean changed = false;
        List<DatasetEntity> datasets =
                datasetRepository.findByGroupId(groupId).stream()
                        .sorted(
                                Comparator.comparing(
                                                DatasetEntity::getCreatedAt,
                                                Comparator.nullsLast(Comparator.naturalOrder()))
                                        .thenComparing(DatasetEntity::getId))
                        .toList();
        Map<String, String> mapping = readMapping(groupId);
        boolean mappingChanged = false;

        // Physical columns of every dataset, plus the raw-type set for normalization.
        Set<String> rawTypes = new HashSet<>();
        Map<String, List<ColumnSchema>> physical = new LinkedHashMap<>();
        for (DatasetEntity d : datasets) {
            List<ColumnSchema> cols;
            try {
                cols =
                        json.readValue(
                                d.getColumnSchemaJson(),
                                new TypeReference<List<ColumnSchema>>() {});
            } catch (IOException e) {
                issues.add(
                        issue(
                                "error",
                                "数据集「" + d.getName() + "」的列结构无法解析：" + e.getMessage(),
                                "local"));
                continue;
            }
            physical.put(d.getId(), cols);
            for (ColumnSchema c : cols) {
                if (c.sqlType() != null && !c.sqlType().isBlank()) {
                    rawTypes.add(c.sqlType());
                }
            }
        }
        Map<String, String> typeByRaw;
        try {
            typeByRaw = typeNormalizer.normalize(rawTypes);
        } catch (RuntimeException e) {
            issues.add(issue("error", "列类型归一化失败：" + e.getMessage(), "local"));
            return new SeedResult(issues, changed);
        }

        Set<String> live = new HashSet<>();
        Set<String> usedDirs = new LinkedHashSet<>(listModelDirs(groupId));
        for (DatasetEntity d : datasets) {
            List<ColumnSchema> cols = physical.get(d.getId());
            if (cols == null) {
                continue; // already reported above
            }
            live.add(d.getId());
            String modelDirName = mapping.get(d.getId());
            Path modelFile =
                    modelDirName == null
                            ? null
                            : workspace
                                    .workspaceRoot(groupId)
                                    .resolve("models")
                                    .resolve(modelDirName)
                                    .resolve("metadata.yml");
            if (modelDirName == null || !Files.isRegularFile(modelFile)) {
                String dir =
                        uniqueDir(usedDirs, MdlPublishService.sanitizeIdentifier(d.getName(), 60));
                writeSeedModel(groupId, dir, d, cols, typeByRaw);
                mapping.put(d.getId(), dir);
                mappingChanged = true;
                changed = true;
                continue;
            }
            SeedResult existing =
                    reconcileExistingModel(groupId, modelDirName, modelFile, d, cols, typeByRaw);
            issues.addAll(existing.issues());
            changed |= existing.changed();
        }

        // Removed datasets: delete their model file, drop the mapping entry, report danglers.
        for (String datasetId : List.copyOf(mapping.keySet())) {
            if (live.contains(datasetId)) {
                continue;
            }
            String dir = mapping.remove(datasetId);
            Path modelFile = workspace.workspaceRoot(groupId).resolve("models").resolve(dir);
            try {
                deleteTree(modelFile);
            } catch (IOException e) {
                issues.add(
                        issue("error", "删除已移除数据集的模型文件失败：" + dir + "：" + e.getMessage(), "local"));
                continue;
            }
            mappingChanged = true;
            changed = true;
            issues.addAll(scanDanglingReferences(groupId, dir));
        }

        if (mappingChanged) {
            writeMapping(groupId, mapping);
        }
        return new SeedResult(issues, changed);
    }

    /**
     * Reconciles one existing model file with its dataset's physical columns: appends missing
     * columns (text surgery), reports type conflicts and removed physical columns as errors. The
     * file is only written when nothing is broken — conflicts need a human/agent decision.
     */
    private SeedResult reconcileExistingModel(
            String groupId,
            String modelDirName,
            Path modelFile,
            DatasetEntity dataset,
            List<ColumnSchema> physicalCols,
            Map<String, String> typeByRaw) {
        List<MdlPublishService.MdlIssue> issues = new ArrayList<>();
        String content;
        try {
            content = Files.readString(modelFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            issues.add(
                    issue(
                            "error",
                            "无法读取模型文件 models/" + modelDirName + "/metadata.yml：" + e.getMessage(),
                            "local"));
            return new SeedResult(issues, false);
        }
        Map<String, Object> root;
        try {
            root = yaml.readValue(content, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            issues.add(
                    issue(
                            "error",
                            "模型文件 models/"
                                    + modelDirName
                                    + "/metadata.yml 不是合法 YAML，请人工修复："
                                    + e.getMessage(),
                            "local"));
            return new SeedResult(issues, false);
        }
        Map<String, String> physicalTypes = new LinkedHashMap<>();
        for (ColumnSchema c : physicalCols) {
            if (c.name() == null || c.name().isBlank()) {
                continue;
            }
            String type = typeByRaw.get(c.sqlType());
            if (type == null) {
                issues.add(
                        issue(
                                "error",
                                "数据集「"
                                        + dataset.getName()
                                        + "」的列「"
                                        + c.name()
                                        + "」的类型「"
                                        + c.sqlType()
                                        + "」无法归一化",
                                "local"));
                continue;
            }
            physicalTypes.put(c.name(), type);
        }
        Set<String> declared = new HashSet<>();
        Set<String> physicalDeclared = new HashSet<>();
        if (root.get("columns") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                String name = text(map.get("name"));
                if (name.isEmpty()) {
                    continue;
                }
                declared.add(name);
                boolean calculated = Boolean.TRUE.equals(map.get("is_calculated"));
                boolean relationship = map.get("relationship") != null;
                if (calculated || relationship) {
                    continue;
                }
                physicalDeclared.add(name);
                String expected = physicalTypes.get(name);
                String actual = text(map.get("type"));
                if (expected != null && !actual.isEmpty() && !actual.equals(expected)) {
                    issues.add(
                            issue(
                                    "error",
                                    "模型「"
                                            + dataset.getName()
                                            + "」的列「"
                                            + name
                                            + "」类型为 "
                                            + actual
                                            + "，与物理类型 "
                                            + expected
                                            + " 冲突，请人工修复",
                                    "local"));
                }
            }
        }
        for (String removed : physicalDeclared) {
            if (!physicalTypes.containsKey(removed)) {
                issues.add(
                        issue(
                                "error",
                                "模型「" + dataset.getName() + "」的列「" + removed + "」已从数据集中移除，请人工删除该列",
                                "local"));
            }
        }
        boolean broken = issues.stream().anyMatch(i -> "error".equals(i.severity()));
        List<String> missing = new ArrayList<>();
        for (String name : physicalTypes.keySet()) {
            if (!declared.contains(name)) {
                missing.add(name);
            }
        }
        if (missing.isEmpty() || broken) {
            return new SeedResult(issues, false);
        }
        try {
            String updated = appendColumns(content, missing, physicalCols, typeByRaw);
            Files.writeString(modelFile, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            issues.add(
                    issue(
                            "error",
                            "追加缺失列到 models/" + modelDirName + "/metadata.yml 失败：" + e.getMessage(),
                            "local"));
            return new SeedResult(issues, false);
        }
        return new SeedResult(issues, true);
    }

    /**
     * Appends the missing physical columns to the model file's {@code columns:} block with a
     * text-surgery insert that preserves the agent's formatting and comments. The entry indent
     * mimics the first existing entry; a flow-style {@code columns: []} is rewritten as a block;
     * a missing block is appended at the end of the file.
     */
    private String appendColumns(
            String content,
            List<String> missing,
            List<ColumnSchema> physicalCols,
            Map<String, String> typeByRaw) {
        Map<String, ColumnSchema> byName = new LinkedHashMap<>();
        for (ColumnSchema c : physicalCols) {
            if (c.name() != null && !c.name().isBlank()) {
                byName.put(c.name(), c);
            }
        }
        List<String> lines = new ArrayList<>(content.lines().toList());
        int columnsLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (COLUMNS_KEY.matcher(lines.get(i)).matches()) {
                columnsLine = i;
                break;
            }
        }
        if (columnsLine < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("columns:");
            lines.addAll(renderedEntries(missing, byName, typeByRaw, "  "));
            return String.join("\n", lines) + "\n";
        }
        if (lines.get(columnsLine).contains("[]")) {
            List<String> out = new ArrayList<>(lines.subList(0, columnsLine));
            out.add("columns:");
            out.addAll(renderedEntries(missing, byName, typeByRaw, "  "));
            out.addAll(lines.subList(columnsLine + 1, lines.size()));
            return String.join("\n", out) + "\n";
        }
        String entryIndent = null;
        int lastNonBlank = columnsLine;
        for (int i = columnsLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            if (!line.startsWith(" ")) {
                break;
            }
            if (entryIndent == null) {
                Matcher m = ENTRY_INDENT.matcher(line);
                if (m.matches()) {
                    entryIndent = m.group(1);
                }
            }
            lastNonBlank = i;
        }
        String indent = entryIndent == null ? "  " : entryIndent;
        lines.addAll(lastNonBlank + 1, renderedEntries(missing, byName, typeByRaw, indent));
        return String.join("\n", lines) + "\n";
    }

    private static List<String> renderedEntries(
            List<String> missing,
            Map<String, ColumnSchema> byName,
            Map<String, String> typeByRaw,
            String indent) {
        List<String> out = new ArrayList<>();
        for (String name : missing) {
            ColumnSchema c = byName.get(name);
            out.add(indent + "- name: " + MdlPublishService.yamlScalar(name));
            out.add(indent + "  type: " + MdlPublishService.yamlScalar(typeByRaw.get(c.sqlType())));
            if (c.description() != null && !c.description().isBlank()) {
                out.add(indent + "  properties:");
                out.add(
                        indent
                                + "    description: "
                                + MdlPublishService.yamlScalar(c.description()));
            }
        }
        return out;
    }

    /** Renders one seed model file for a dataset (the golden probe-verified shape). */
    private void writeSeedModel(
            String groupId,
            String dirName,
            DatasetEntity dataset,
            List<ColumnSchema> cols,
            Map<String, String> typeByRaw) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(MdlPublishService.yamlScalar(dirName)).append('\n');
        sb.append("table_reference:\n");
        sb.append("  catalog: ''\n");
        sb.append("  schema: ")
                .append(MdlPublishService.yamlScalar(dataset.getSchemaName()))
                .append('\n');
        sb.append("  table: ")
                .append(MdlPublishService.yamlScalar(dataset.getTableName()))
                .append('\n');
        sb.append("columns:\n");
        Set<String> taken = new HashSet<>();
        for (ColumnSchema c : cols) {
            if (c.name() == null || c.name().isBlank() || !taken.add(c.name())) {
                continue;
            }
            String type = typeByRaw.get(c.sqlType());
            if (type == null) {
                continue;
            }
            sb.append("- name: ").append(MdlPublishService.yamlScalar(c.name())).append('\n');
            sb.append("  type: ").append(MdlPublishService.yamlScalar(type)).append('\n');
            if (c.description() != null && !c.description().isBlank()) {
                sb.append("  properties:\n");
                sb.append("    description: ")
                        .append(MdlPublishService.yamlScalar(c.description()))
                        .append('\n');
            }
        }
        if (dataset.getDescription() != null && !dataset.getDescription().isBlank()) {
            sb.append("properties:\n");
            sb.append("  description: ")
                    .append(MdlPublishService.yamlScalar(dataset.getDescription()))
                    .append('\n');
        }
        try {
            Path file =
                    workspace
                            .workspaceRoot(groupId)
                            .resolve("models")
                            .resolve(dirName)
                            .resolve("metadata.yml");
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("写入播种模型文件失败：" + e.getMessage(), e);
        }
    }

    /**
     * Reports workspace references to a removed model: relation members in {@code
     * relationships.yml} and cubes whose {@code base_object} is the removed model. These need a
     * human/agent decision, so they are errors, not silent rewrites.
     */
    private List<MdlPublishService.MdlIssue> scanDanglingReferences(
            String groupId, String removedDir) {
        List<MdlPublishService.MdlIssue> issues = new ArrayList<>();
        Path ws = workspace.workspaceRoot(groupId);
        try {
            Path relations = ws.resolve("relationships.yml");
            if (Files.isRegularFile(relations)) {
                Map<String, Object> root =
                        yaml.readValue(
                                Files.readString(relations, StandardCharsets.UTF_8),
                                new TypeReference<Map<String, Object>>() {});
                if (root.get("relationships") instanceof List<?> entries) {
                    for (Object entry : entries) {
                        if (entry instanceof Map<?, ?> rel
                                && rel.get("models") instanceof List<?> models
                                && models.stream().anyMatch(m -> removedDir.equals(m))) {
                            issues.add(
                                    issue(
                                            "error",
                                            "关系「"
                                                    + rel.get("name")
                                                    + "」引用了已移除的模型「"
                                                    + removedDir
                                                    + "」，请人工修复",
                                            "local"));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            issues.add(
                    issue("error", "解析 relationships.yml 失败，无法检查悬空引用：" + e.getMessage(), "local"));
        }
        Path cubes = ws.resolve("cubes");
        if (!Files.isDirectory(cubes)) {
            return issues;
        }
        try (Stream<Path> list = Files.list(cubes)) {
            for (Path cubeDir : list.filter(Files::isDirectory).sorted().toList()) {
                Path meta = cubeDir.resolve("metadata.yml");
                if (!Files.isRegularFile(meta)) {
                    continue;
                }
                Map<String, Object> root;
                try {
                    root =
                            yaml.readValue(
                                    Files.readString(meta, StandardCharsets.UTF_8),
                                    new TypeReference<Map<String, Object>>() {});
                } catch (IOException e) {
                    issues.add(
                            issue(
                                    "error",
                                    "解析 cubes/"
                                            + cubeDir.getFileName()
                                            + "/metadata.yml 失败，无法检查悬空引用："
                                            + e.getMessage(),
                                    "local"));
                    continue;
                }
                if (removedDir.equals(text(root.get("base_object")))) {
                    issues.add(
                            issue(
                                    "error",
                                    "Cube「"
                                            + text(root.get("name"))
                                            + "」的基准模型「"
                                            + removedDir
                                            + "」已移除，请人工处理",
                                    "local"));
                }
            }
        } catch (IOException e) {
            log.warn("MdlSeeder: could not scan cubes for group {}: {}", groupId, e.getMessage());
        }
        return issues;
    }

    private Map<String, String> readMapping(String groupId) {
        Path file = workspace.workspaceRoot(groupId).resolve(".platform").resolve("datasets.json");
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(
                    Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            throw new DatasetException("读取数据集映射失败：" + e.getMessage(), e);
        }
    }

    private void writeMapping(String groupId, Map<String, String> mapping) {
        try {
            Path file =
                    workspace.workspaceRoot(groupId).resolve(".platform").resolve("datasets.json");
            Files.createDirectories(file.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), mapping);
        } catch (IOException e) {
            throw new DatasetException("写入数据集映射失败：" + e.getMessage(), e);
        }
    }

    private Set<String> listModelDirs(String groupId) {
        Path models = workspace.workspaceRoot(groupId).resolve("models");
        if (!Files.isDirectory(models)) {
            return new LinkedHashSet<>();
        }
        try (Stream<Path> list = Files.list(models)) {
            return list.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException e) {
            throw new DatasetException("列出现有模型失败：" + e.getMessage(), e);
        }
    }

    private static String uniqueDir(Set<String> used, String candidate) {
        String name = candidate;
        int n = 2;
        while (!used.add(name)) {
            name = candidate + "_" + n++;
        }
        return name;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static MdlPublishService.MdlIssue issue(
            String severity, String message, String source) {
        return new MdlPublishService.MdlIssue(severity, message, source);
    }
}
