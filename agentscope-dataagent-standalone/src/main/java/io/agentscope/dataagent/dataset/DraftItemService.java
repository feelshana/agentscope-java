package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * "AI 建模结果" (spec092 §7): the draft's modeling changes shown as business items instead of
 * files — one per table, relationship and rule bullet — each viewable, editable and (except
 * tables) deletable. Items are always the diff between the draft and the published snapshot;
 * {@link DraftChangeLog} narrows them to one turn and supplies the basis.
 */
@Service
public class DraftItemService {
    private static final ObjectMapper READ = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper WRITE =
            new ObjectMapper(
                    YAMLFactory.builder()
                            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                            .build());
    private static final String TABLE = "__table__";
    private static final String NAME = "[\\p{L}\\p{N}_]{1,64}";
    private static final Set<String> JOIN_TYPES =
            Set.of("MANY_TO_ONE", "ONE_TO_MANY", "ONE_TO_ONE", "MANY_TO_MANY");
    private static final Set<String> SEMANTIC_TYPES =
            Set.of("IDENTIFIER", "DIMENSION", "TIME", "MEASURE");

    private final DatasetGroupService groups;
    private final MdlWorkspaceService workspace;
    private final ModelingActivity activity;

    public DraftItemService(
            DatasetGroupService groups, MdlWorkspaceService workspace, ModelingActivity activity) {
        this.groups = groups;
        this.workspace = workspace;
        this.activity = activity;
    }

    /** A description that changed; {@code name} is a column, or {@code __table__}. */
    public record Field(String name, String before, String after) {}

    public record Item(
            String id,
            String category,
            String kind,
            String title,
            String summary,
            List<Field> fields,
            String text,
            List<String> basis,
            String path,
            boolean editable,
            boolean deletable) {}

    /** {@code since} (ISO instant) limits items to files written from then on, e.g. one turn. */
    public List<Item> items(String userId, String groupId, String since) {
        groups.getGroup(userId, groupId);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path root = workspace.workspaceRoot(groupId);
                    Path published = root.getParent().resolve("published");
                    Map<String, List<String>> basis = new LinkedHashMap<>();
                    java.time.Instant from = since == null ? null : instant(since);
                    for (DraftChangeLog.Entry e : DraftChangeLog.read(root))
                        if (from == null || !instant(e.at()).isBefore(from))
                            basis.computeIfAbsent(e.path(), k -> new ArrayList<>()).add(e.source());
                    Set<String> paths =
                            since == null ? candidates(root, published) : basis.keySet();
                    List<Item> out = new ArrayList<>();
                    for (String path : paths) {
                        String before = read(published.resolve(path));
                        String after = read(root.resolve(path));
                        if (Objects.equals(before, after)) continue;
                        List<String> sources =
                                basis.getOrDefault(path, List.of()).stream().distinct().toList();
                        if (path.matches("models/[^/]+/metadata\\.yml"))
                            out.addAll(modelItems(path, before, after, sources));
                        else if (path.equals("relationships.yml"))
                            out.addAll(relationItems(before, after, sources));
                        else if (path.matches(
                                "knowledge/(rules|caveats|glossary|metrics)/[^/]+\\.md"))
                            out.addAll(bulletItems(path, before, after, sources));
                    }
                    return out;
                });
    }

    /** Edits a table's description and/or column descriptions; tables are never deleted here. */
    public List<Item> updateModel(
            String userId,
            String groupId,
            String model,
            String tableDescription,
            Map<String, String> columns) {
        if (model == null || !model.matches("[\\p{L}\\p{N}_]{1,64}"))
            throw new DatasetException("模型名不合法", 400);
        String path = "models/" + model + "/metadata.yml";
        return mutate(
                userId,
                groupId,
                path,
                content -> {
                    if (content == null) throw new DatasetException("模型不存在", 404);
                    ObjectNode node = (ObjectNode) READ.readTree(content);
                    if (tableDescription != null)
                        properties(node).put("description", tableDescription.trim());
                    if (columns != null)
                        for (JsonNode column : node.path("columns")) {
                            String name = column.path("name").asText();
                            if (columns.containsKey(name))
                                properties((ObjectNode) column)
                                        .put("description", columns.get(name).trim());
                        }
                    return WRITE.writeValueAsString(node);
                });
    }

    /** Edits or deletes one relationship, identified by its name. */
    public List<Item> updateRelationship(
            String userId,
            String groupId,
            String name,
            String joinType,
            String condition,
            boolean delete) {
        return updateRelationship(userId, groupId, name, joinType, condition, null, delete);
    }

    /** spec117: also edits the relationship's description; null leaves a value unchanged. */
    public List<Item> updateRelationship(
            String userId,
            String groupId,
            String name,
            String joinType,
            String condition,
            String description,
            boolean delete) {
        if (joinType != null && !JOIN_TYPES.contains(joinType.trim()))
            throw new DatasetException("不支持的关系类型", 400);
        return mutate(
                userId,
                groupId,
                "relationships.yml",
                content -> {
                    ObjectNode root =
                            content == null
                                    ? WRITE.createObjectNode()
                                    : (ObjectNode) READ.readTree(content);
                    JsonNode list = root.path("relationships");
                    if (!list.isArray()) throw new DatasetException("关系不存在", 404);
                    ArrayNode kept = WRITE.createArrayNode();
                    boolean found = false;
                    for (JsonNode relation : list) {
                        if (!name.equals(relation.path("name").asText())) {
                            kept.add(relation);
                            continue;
                        }
                        found = true;
                        if (delete) continue;
                        ObjectNode edited = ((ObjectNode) relation).deepCopy();
                        if (joinType != null) edited.put("join_type", joinType.trim());
                        if (condition != null) edited.put("condition", condition.trim());
                        if (description != null) setOrRemove(edited, "description", description);
                        kept.add(edited);
                    }
                    if (!found) throw new DatasetException("关系已变化，请刷新后再操作", 409);
                    root.set("relationships", kept);
                    return WRITE.writeValueAsString(root);
                });
    }

    /**
     * spec117: adds a relationship between two model columns; the name is derived from the two
     * models and made unique. {@code gate} checks the new file before it is written.
     */
    public List<Item> addRelationship(
            String userId,
            String groupId,
            String left,
            String leftColumn,
            String right,
            String rightColumn,
            String joinType,
            String description,
            Gate gate) {
        for (String part : new String[] {left, leftColumn, right, rightColumn})
            if (part == null || !part.matches(NAME)) throw new DatasetException("请选择两端的模型和字段", 400);
        if (left.equals(right)) throw new DatasetException("关系两端不能是同一个模型", 400);
        String type = joinType == null ? "MANY_TO_ONE" : joinType.trim();
        if (!JOIN_TYPES.contains(type)) throw new DatasetException("不支持的关系类型", 400);
        return mutate(
                userId,
                groupId,
                "relationships.yml",
                content -> {
                    requireColumn(groupId, left, leftColumn);
                    requireColumn(groupId, right, rightColumn);
                    ObjectNode root =
                            content == null || content.isBlank()
                                    ? WRITE.createObjectNode()
                                    : (ObjectNode) READ.readTree(content);
                    JsonNode existing = root.path("relationships");
                    ArrayNode list =
                            existing.isArray()
                                    ? (ArrayNode) existing
                                    : root.putArray("relationships");
                    Set<String> names = new java.util.HashSet<>();
                    for (JsonNode r : list) names.add(r.path("name").asText());
                    String name = left + "_" + right;
                    for (int n = 2; names.contains(name); n++) name = left + "_" + right + "_" + n;
                    ObjectNode relation = list.addObject();
                    relation.put("name", name);
                    relation.putArray("models").add(left).add(right);
                    relation.put("join_type", type);
                    relation.put(
                            "condition",
                            left + "." + leftColumn + " = " + right + "." + rightColumn);
                    if (description != null && !description.isBlank())
                        relation.putObject("properties").put("description", description.trim());
                    return WRITE.writeValueAsString(root);
                },
                gate);
    }

    /**
     * spec117: one column's page edit. A null value leaves that property unchanged; an empty one
     * removes it. Only calculated columns may be created or deleted, and only they carry an
     * expression.
     */
    public record ColumnChange(
            String column,
            String type,
            String description,
            String displayName,
            String semanticType,
            List<String> synonyms,
            String expression,
            boolean create,
            boolean delete) {}

    /** Checks a file's next content before it is written; returns the error text or null. */
    @FunctionalInterface
    public interface Gate {
        String check(String path, String content);
    }

    public List<Item> updateColumn(
            String userId, String groupId, String model, ColumnChange change, Gate gate) {
        if (model == null || !model.matches(NAME)) throw new DatasetException("模型名不合法", 400);
        if (change == null || change.column() == null || !change.column().matches(NAME))
            throw new DatasetException("字段名只能包含中文、字母、数字和下划线", 400);
        if (change.semanticType() != null
                && !change.semanticType().isBlank()
                && !SEMANTIC_TYPES.contains(change.semanticType()))
            throw new DatasetException("不支持的语义角色", 400);
        return mutate(
                userId,
                groupId,
                "models/" + model + "/metadata.yml",
                content -> {
                    if (content == null) throw new DatasetException("模型不存在", 404);
                    ObjectNode node = (ObjectNode) READ.readTree(content);
                    JsonNode existing = node.path("columns");
                    ArrayNode columns =
                            existing.isArray() ? (ArrayNode) existing : node.putArray("columns");
                    int index = -1;
                    for (int i = 0; i < columns.size(); i++)
                        if (change.column().equals(columns.get(i).path("name").asText())) index = i;
                    if (change.create()) {
                        if (index >= 0) throw new DatasetException("已有同名字段", 409);
                        if (change.expression() == null || change.expression().isBlank())
                            throw new DatasetException("请填写衍生字段的表达式", 400);
                        ObjectNode column = columns.addObject();
                        column.put("name", change.column());
                        column.put("type", blankOr(change.type(), "VARCHAR"));
                        column.put("is_calculated", true);
                        column.put("expression", change.expression().trim());
                        applyProperties(column, change);
                        return WRITE.writeValueAsString(node);
                    }
                    if (index < 0) throw new DatasetException("字段已变化，请刷新后再操作", 409);
                    ObjectNode column = (ObjectNode) columns.get(index);
                    boolean calculated = column.path("is_calculated").asBoolean(false);
                    if (change.delete()) {
                        if (!calculated)
                            throw new DatasetException("只能删除衍生字段；普通字段请在字段整理中取消纳入", 400);
                        columns.remove(index);
                        return WRITE.writeValueAsString(node);
                    }
                    if (change.expression() != null) {
                        if (!calculated) throw new DatasetException("普通字段没有表达式", 400);
                        if (change.expression().isBlank())
                            throw new DatasetException("请填写衍生字段的表达式", 400);
                        column.put("expression", change.expression().trim());
                    }
                    if (calculated && change.type() != null && !change.type().isBlank())
                        column.put("type", change.type().trim());
                    applyProperties(column, change);
                    return WRITE.writeValueAsString(node);
                },
                gate);
    }

    private static void applyProperties(ObjectNode column, ColumnChange change) {
        if (change.description() != null) {
            setOrRemove(column, "description", change.description());
            if (change.description().isBlank()) setOrRemove(column, "description_source", "");
            else
                setOrRemove(
                        column,
                        "description_source",
                        io.agentscope.dataagent.dataset.parser.ColumnSchema.SOURCE_USER);
        }
        if (change.displayName() != null) setOrRemove(column, "displayName", change.displayName());
        if (change.semanticType() != null)
            setOrRemove(column, "semanticType", change.semanticType());
        if (change.synonyms() != null) {
            List<String> kept =
                    change.synonyms().stream()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .distinct()
                            .toList();
            ObjectNode props = propertiesNode(column);
            if (kept.isEmpty()) props.remove("synonyms");
            else {
                ArrayNode list = props.putArray("synonyms");
                kept.forEach(list::add);
            }
            if (props.isEmpty()) column.remove("properties");
        }
    }

    /** Sets {@code properties.key}, or removes it (and an empty properties block) when blank. */
    private static void setOrRemove(ObjectNode owner, String key, String value) {
        ObjectNode props = propertiesNode(owner);
        if (value == null || value.isBlank()) props.remove(key);
        else props.put(key, value.trim());
        if (props.isEmpty()) owner.remove("properties");
    }

    private static ObjectNode propertiesNode(ObjectNode owner) {
        JsonNode existing = owner.get("properties");
        return existing instanceof ObjectNode o ? o : owner.putObject("properties");
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** A relationship end must be a column of a draft model (by name). */
    private void requireColumn(String groupId, String model, String column) throws IOException {
        String text =
                read(workspace.workspaceRoot(groupId).resolve("models/" + model + "/metadata.yml"));
        if (text == null) throw new DatasetException("模型不存在：" + model, 404);
        for (JsonNode c : READ.readTree(text).path("columns"))
            if (column.equals(c.path("name").asText())) return;
        throw new DatasetException("模型 " + model + " 没有字段 " + column, 400);
    }

    /**
     * spec117: moves one bullet of a knowledge file under another {@code ## heading} of the same
     * file (removed from its place, appended to the section, which is created when missing).
     */
    public List<Item> moveBullet(
            String userId, String groupId, String path, String original, String heading) {
        if (path == null || !path.matches("knowledge/(rules|caveats|glossary|metrics)/[^/]+\\.md"))
            throw new DatasetException("只能修改业务语义或注意事项的条目", 400);
        if (heading == null || heading.isBlank() || heading.contains("\n"))
            throw new DatasetException("请选择要移到的小节", 400);
        return mutate(
                userId,
                groupId,
                path,
                content -> {
                    if (content == null) throw new DatasetException("文件不存在", 404);
                    List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
                    for (int i = 0; i < lines.size(); i++) {
                        if (!isBullet(lines.get(i))) continue;
                        int end = i + 1;
                        while (end < lines.size() && isContinuation(lines.get(end))) end++;
                        String text = bulletText(lines.subList(i, end));
                        if (!text.equals(original)) continue;
                        lines.subList(i, end).clear();
                        return appendTo(
                                String.join("\n", lines), heading.trim(), bulletLines(text));
                    }
                    throw new DatasetException("条目已变化，请刷新后再操作", 409);
                },
                null);
    }

    /** Replaces or deletes one Markdown bullet whose current text equals {@code original}. */
    public List<Item> updateBullet(
            String userId, String groupId, String path, String original, String text) {
        if (path == null || !path.matches("knowledge/(rules|caveats|glossary|metrics)/[^/]+\\.md"))
            throw new DatasetException("只能修改业务语义或注意事项的条目", 400);
        return mutate(
                userId,
                groupId,
                path,
                content -> {
                    if (content == null) throw new DatasetException("文件不存在", 404);
                    List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
                    for (int i = 0; i < lines.size(); i++) {
                        if (!isBullet(lines.get(i))) continue;
                        int end = i + 1;
                        while (end < lines.size() && isContinuation(lines.get(end))) end++;
                        if (!bulletText(lines.subList(i, end)).equals(original)) continue;
                        List<String> replacement = new ArrayList<>();
                        if (text != null && !text.isBlank()) {
                            String[] parts = text.trim().split("\n");
                            replacement.add("- " + parts[0]);
                            for (int k = 1; k < parts.length; k++)
                                replacement.add("  " + parts[k].trim());
                        }
                        lines.subList(i, end).clear();
                        lines.addAll(i, replacement);
                        return String.join("\n", lines);
                    }
                    throw new DatasetException("条目已变化，请刷新后再操作", 409);
                });
    }

    /**
     * Appends one bullet under {@code ## heading} of a Markdown knowledge file, creating the file
     * or the section when missing (spec100 business terms).
     */
    public void appendBullet(
            String userId, String groupId, String path, String heading, String text) {
        if (path == null || !path.matches("knowledge/(rules|caveats|glossary|metrics)/[^/]+\\.md"))
            throw new DatasetException("只能修改业务语义或注意事项的条目", 400);
        if (text == null || text.isBlank()) throw new DatasetException("条目内容不能为空", 400);
        if (heading == null || heading.isBlank() || heading.contains("\n"))
            throw new DatasetException("请选择小节", 400);
        List<String> bullet = bulletLines(text);
        mutate(userId, groupId, path, content -> appendTo(content, heading.trim(), bullet));
    }

    private static List<String> bulletLines(String text) {
        List<String> bullet = new ArrayList<>();
        String[] parts = text.trim().split("\n");
        bullet.add("- " + parts[0]);
        for (int k = 1; k < parts.length; k++) bullet.add("  " + parts[k].trim());
        return bullet;
    }

    /** Appends the bullet lines at the end of {@code ## heading}, creating it when missing. */
    private static String appendTo(String content, String heading, List<String> bullet) {
        String title = "## " + heading;
        if (content == null || content.isBlank())
            return title + "\n\n" + String.join("\n", bullet) + "\n";
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).strip().equals(title)) start = i;
        if (start < 0) {
            String body = content.endsWith("\n") ? content : content + "\n";
            return body + "\n" + title + "\n\n" + String.join("\n", bullet) + "\n";
        }
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++)
            if (lines.get(i).startsWith("#")) {
                end = i;
                break;
            }
        int at = end;
        while (at > start + 1 && lines.get(at - 1).isBlank()) at--;
        if (at == start + 1) lines.add(at++, "");
        lines.addAll(at, bullet);
        return String.join("\n", lines);
    }

    private interface Change {
        String apply(String content) throws IOException;
    }

    private List<Item> mutate(String userId, String groupId, String path, Change change) {
        return mutate(userId, groupId, path, change, null);
    }

    private List<Item> mutate(
            String userId, String groupId, String path, Change change, Gate gate) {
        groups.getGroup(userId, groupId);
        activity.requireIdle(groupId);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    // Re-check after waiting for the lock: access may have been revoked meanwhile.
                    groups.getGroup(userId, groupId);
                    Path file = workspace.resolveWritable(groupId, path);
                    try {
                        String next = change.apply(read(file));
                        String invalid =
                                path.endsWith(".yml")
                                        ? DraftReviewService.yamlError(path, next)
                                        : null;
                        if (invalid != null) throw new DatasetException(invalid, 400);
                        String rejected = gate == null ? null : gate.check(path, next);
                        if (rejected != null)
                            throw new DatasetException("Wren 校验未通过：" + rejected, 400);
                        Files.createDirectories(file.getParent());
                        Files.writeString(file, next, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new DatasetException("保存修改失败：" + e.getMessage(), e);
                    }
                    DraftChangeLog.record(
                            workspace.workspaceRoot(groupId),
                            path,
                            ModelingTurnPurpose.EDIT,
                            userId);
                    return null;
                });
        groups.markMdlDirty(userId, groupId);
        return items(userId, groupId, null);
    }

    private static List<Item> modelItems(
            String path, String before, String after, List<String> basis) {
        String model = path.split("/")[1];
        Map<String, String> old = descriptions(before);
        Map<String, String> now = descriptions(after);
        if (after == null)
            return List.of(
                    new Item(
                            "MODEL:" + model,
                            "MODEL",
                            "REMOVED",
                            model,
                            "模型已移除",
                            List.of(),
                            null,
                            basis,
                            path,
                            false,
                            false));
        List<Field> fields = new ArrayList<>();
        Map<String, String> oldSignature = signatures(before);
        Map<String, String> nowSignature = signatures(after);
        for (var entry : now.entrySet())
            if (!Objects.equals(old.get(entry.getKey()), entry.getValue())
                    || !Objects.equals(
                            oldSignature.get(entry.getKey()), nowSignature.get(entry.getKey())))
                fields.add(new Field(entry.getKey(), old.get(entry.getKey()), entry.getValue()));
        long columns = fields.stream().filter(f -> !TABLE.equals(f.name())).count();
        String summary =
                before == null
                        ? "新增模型，" + (now.size() - (now.containsKey(TABLE) ? 1 : 0)) + " 个字段"
                        : (fields.stream().anyMatch(f -> TABLE.equals(f.name())) ? "表说明、" : "")
                                + "补充或修改 "
                                + columns
                                + " 个字段";
        return List.of(
                new Item(
                        "MODEL:" + model,
                        "MODEL",
                        before == null ? "ADDED" : "MODIFIED",
                        model,
                        summary,
                        fields,
                        now.get(TABLE),
                        basis,
                        path,
                        true,
                        false));
    }

    /** Column name to its {@link #columnSummary}; detects changes beyond the description. */
    private static Map<String, String> signatures(String yaml) {
        Map<String, String> out = new LinkedHashMap<>();
        if (yaml == null) return out;
        try {
            JsonNode node = READ.readTree(yaml);
            if (node == null) return out;
            for (JsonNode column : node.path("columns"))
                out.put(column.path("name").asText(), columnSummary(column));
        } catch (IOException e) {
            // Unparseable YAML shows no field detail; the draft check reports the error.
        }
        return out;
    }

    private static Map<String, String> descriptions(String yaml) {
        Map<String, String> out = new LinkedHashMap<>();
        if (yaml == null) return out;
        try {
            JsonNode node = READ.readTree(yaml);
            if (node == null) return out;
            String table = node.path("properties").path("description").asText(null);
            if (table != null) out.put(TABLE, table.trim());
            for (JsonNode column : node.path("columns"))
                out.put(
                        column.path("name").asText(),
                        column.path("properties").path("description").asText("").trim());
        } catch (IOException e) {
            // Unparseable YAML shows no field detail; the draft check reports the error.
        }
        return out;
    }

    /**
     * The description plus what spec117 lets the page maintain, so a role, synonym or expression
     * change shows up as a field change too.
     */
    private static String columnSummary(JsonNode column) {
        JsonNode props = column.path("properties");
        StringBuilder text = new StringBuilder(props.path("description").asText("").trim());
        List<String> extras = new ArrayList<>();
        String role = props.path("semanticType").asText("");
        if (!role.isBlank()) extras.add("角色：" + ROLE_LABELS.getOrDefault(role, role));
        List<String> synonyms = new ArrayList<>();
        props.path("synonyms").forEach(s -> synonyms.add(s.asText()));
        if (!synonyms.isEmpty()) extras.add("同义词：" + String.join("、", synonyms));
        if (column.path("is_calculated").asBoolean(false))
            extras.add("表达式：" + column.path("expression").asText(""));
        if (!extras.isEmpty())
            text.append(text.isEmpty() ? "" : "（")
                    .append(String.join("；", extras))
                    .append(text.isEmpty() ? "" : "）");
        return text.toString();
    }

    private static final Map<String, String> ROLE_LABELS =
            Map.of("IDENTIFIER", "标识", "DIMENSION", "维度", "TIME", "时间", "MEASURE", "度量");

    private static List<Item> relationItems(String before, String after, List<String> basis) {
        Map<String, JsonNode> old = relations(before);
        Map<String, JsonNode> now = relations(after);
        List<Item> out = new ArrayList<>();
        for (var entry : now.entrySet()) {
            JsonNode previous = old.get(entry.getKey());
            if (entry.getValue().equals(previous)) continue;
            JsonNode r = entry.getValue();
            out.add(
                    new Item(
                            "REL:" + entry.getKey(),
                            "RELATIONSHIP",
                            previous == null ? "ADDED" : "MODIFIED",
                            r.path("condition").asText(entry.getKey()),
                            joinLabel(r.path("join_type").asText("")),
                            List.of(),
                            entry.getKey(),
                            basis,
                            "relationships.yml",
                            true,
                            true));
        }
        for (var entry : old.entrySet())
            if (!now.containsKey(entry.getKey()))
                out.add(
                        new Item(
                                "REL:" + entry.getKey(),
                                "RELATIONSHIP",
                                "REMOVED",
                                entry.getValue().path("condition").asText(entry.getKey()),
                                "已删除",
                                List.of(),
                                entry.getKey(),
                                basis,
                                "relationships.yml",
                                false,
                                false));
        return out;
    }

    private static Map<String, JsonNode> relations(String yaml) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (yaml == null) return out;
        try {
            JsonNode node = READ.readTree(yaml);
            if (node != null)
                for (JsonNode r : node.path("relationships"))
                    out.put(r.path("name").asText(r.path("condition").asText()), r);
        } catch (IOException e) {
            // Shown without items; the draft check reports the error.
        }
        return out;
    }

    private static String joinLabel(String type) {
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "MANY_TO_ONE" -> "多对一";
            case "ONE_TO_MANY" -> "一对多";
            case "ONE_TO_ONE" -> "一对一";
            case "MANY_TO_MANY" -> "多对多";
            default -> type;
        };
    }

    private static List<Item> bulletItems(
            String path, String before, String after, List<String> basis) {
        String category = path.startsWith("knowledge/caveats/") ? "NOTE" : "SEMANTIC";
        Set<String> old = bullets(before);
        Set<String> now = bullets(after);
        List<Item> out = new ArrayList<>();
        for (String bullet : now)
            if (!old.contains(bullet))
                out.add(
                        new Item(
                                category + ":" + path + "#" + bullet.hashCode(),
                                category,
                                "ADDED",
                                firstLine(bullet),
                                null,
                                List.of(),
                                bullet,
                                basis,
                                path,
                                true,
                                true));
        for (String bullet : old)
            if (!now.contains(bullet))
                out.add(
                        new Item(
                                category + ":" + path + "#-" + bullet.hashCode(),
                                category,
                                "REMOVED",
                                firstLine(bullet),
                                "已删除",
                                List.of(),
                                bullet,
                                basis,
                                path,
                                false,
                                false));
        if (out.isEmpty() && after != null)
            // Prose without bullets: one item for the whole text, edited as a file.
            out.add(
                    new Item(
                            category + ":" + path,
                            category,
                            before == null ? "ADDED" : "MODIFIED",
                            path.substring(path.lastIndexOf('/') + 1),
                            "整段内容",
                            List.of(),
                            after,
                            basis,
                            path,
                            true,
                            false));
        return out;
    }

    private static Set<String> bullets(String markdown) {
        Set<String> out = new LinkedHashSet<>();
        if (markdown == null) return out;
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!isBullet(lines[i])) continue;
            int end = i + 1;
            while (end < lines.length && isContinuation(lines[end])) end++;
            out.add(bulletText(List.of(lines).subList(i, end)));
        }
        return out;
    }

    static boolean isBullet(String line) {
        return line.startsWith("- ") || line.startsWith("* ");
    }

    static boolean isContinuation(String line) {
        return line.startsWith("  ") && !line.isBlank();
    }

    static String bulletText(List<String> lines) {
        StringBuilder text = new StringBuilder(lines.get(0).substring(2).trim());
        for (int i = 1; i < lines.size(); i++) text.append('\n').append(lines.get(i).trim());
        return text.toString();
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    private static Properties properties(ObjectNode node) {
        return new Properties(node);
    }

    /** Small helper so {@code properties.description} edits read naturally. */
    private record Properties(ObjectNode owner) {
        void put(String key, String value) {
            JsonNode existing = owner.get("properties");
            ObjectNode props = existing instanceof ObjectNode o ? o : owner.putObject("properties");
            props.put(key, value);
        }
    }

    /** Files that can carry modeling items, in either the draft or the snapshot. */
    private static Set<String> candidates(Path root, Path published) {
        Set<String> out = new LinkedHashSet<>();
        for (Path base : List.of(root, published)) {
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .map(p -> base.relativize(p).toString().replace('\\', '/'))
                        .filter(
                                p ->
                                        p.matches("models/[^/]+/metadata\\.yml")
                                                || p.equals("relationships.yml")
                                                || p.matches(
                                                        "knowledge/(rules|caveats|glossary|metrics)/[^/]+\\.md"))
                        .sorted()
                        .forEach(out::add);
            } catch (IOException e) {
                throw new DatasetException("读取草稿失败：" + e.getMessage(), e);
            }
        }
        return out;
    }

    private static java.time.Instant instant(String value) {
        try {
            return java.time.Instant.parse(value);
        } catch (RuntimeException e) {
            throw new DatasetException("时间格式不正确：" + value, 400);
        }
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : null;
        } catch (IOException e) {
            throw new DatasetException("读取文件失败：" + e.getMessage(), e);
        }
    }
}
