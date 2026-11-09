package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.nio.file.Files;
import java.util.List;
import org.springframework.stereotype.Service;

/** Read projections deliberately exclude drafts, credentials and artifact identifiers. */
@Service
public class KnowledgeBaseBrowseService {
    private final KnowledgeBaseAccessService access;
    private final DatasetRepository datasets;
    private final DatasetService service;
    private final WrenProperties wren;
    private final ObjectMapper json;

    public KnowledgeBaseBrowseService(
            KnowledgeBaseAccessService access,
            DatasetRepository datasets,
            DatasetService service,
            WrenProperties wren,
            ObjectMapper json) {
        this.access = access;
        this.datasets = datasets;
        this.service = service;
        this.wren = wren;
        this.json = json;
    }

    public record Table(
            String id,
            String name,
            String description,
            Long rowCount,
            List<io.agentscope.dataagent.dataset.parser.ColumnSchema> columns) {}

    public record Knowledge(String path, String content) {}

    public record Browse(
            String id,
            String name,
            String description,
            int publishedVersion,
            boolean publicQuery,
            List<Table> datasets,
            List<java.util.Map<String, Object>> models,
            List<java.util.Map<String, Object>> relationships,
            List<Knowledge> knowledge) {}

    /** Published model counts for the modeling list; {@code unrelated} models have no relation. */
    public record Summary(String groupId, int models, int relations, int unrelated) {}

    /** One request for every knowledge base the actor can see, never another owner's. */
    public List<Summary> summaries(String actor) throws java.io.IOException {
        var result = new java.util.ArrayList<Summary>();
        for (var group : access.visible(actor, null)) {
            Summary summary = publishedSummary(group);
            if (summary != null) result.add(summary);
        }
        var allowed =
                access.visible(actor, null).stream()
                        .map(io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity::getId)
                        .collect(java.util.stream.Collectors.toSet());
        return result.stream().filter(s -> allowed.contains(s.groupId())).toList();
    }

    Summary publishedSummary(DatasetGroupEntity group) throws java.io.IOException {
        var file = wren.groupRoot(group.getId()).resolve("published/target/mdl.json");
        if (group.getMdlVersion() <= 0 || !Files.isRegularFile(file)) return null;
        JsonNode mdl = json.readTree(Files.readString(file));
        var related = new java.util.HashSet<String>();
        for (JsonNode relation : mdl.path("relationships"))
            for (JsonNode model : relation.path("models")) related.add(model.asText());
        int unrelated = 0;
        for (JsonNode model : mdl.path("models"))
            if (!related.contains(model.path("name").asText())) unrelated++;
        return new Summary(
                group.getId(),
                mdl.path("models").size(),
                mdl.path("relationships").size(),
                unrelated);
    }

    public Browse browse(String actor, String groupId) throws java.io.IOException {
        var group = access.requireQuery(actor, groupId);
        var tables =
                datasets.findByGroupId(groupId).stream()
                        .map(
                                d ->
                                        new Table(
                                                d.getId(),
                                                d.getName(),
                                                d.getDescription(),
                                                d.getRowCount(),
                                                service.readColumns(d)))
                        .toList();
        JsonNode mdl = json.createObjectNode();
        var file = wren.groupRoot(groupId).resolve("published/target/mdl.json");
        if (group.getMdlVersion() > 0 && Files.isRegularFile(file))
            mdl = json.readTree(Files.readString(file));
        var knowledge = new java.util.ArrayList<Knowledge>();
        var published = wren.groupRoot(groupId).resolve("published");
        int remaining = 500_000;
        if (group.getMdlVersion() > 0 && Files.isDirectory(published)) {
            var realRoot = published.toRealPath();
            for (String section :
                    List.of("rules", "glossary", "metrics", "caveats", "questions", "sql")) {
                var directory = published.resolve("knowledge").resolve(section);
                if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) continue;
                try (var paths = Files.walk(directory)) {
                    for (var path :
                            paths.filter(p -> Files.isRegularFile(p) && !Files.isSymbolicLink(p))
                                    .sorted()
                                    .toList()) {
                        if (knowledge.size() >= 100 || remaining <= 0) break;
                        if (!path.toRealPath().startsWith(realRoot) || Files.size(path) > 20_000)
                            continue;
                        String content = Files.readString(path);
                        remaining -= content.length();
                        knowledge.add(
                                new Knowledge(
                                        published.relativize(path).toString().replace('\\', '/'),
                                        content));
                    }
                }
            }
        }
        access.requireQuery(actor, groupId);
        return new Browse(
                groupId,
                group.getName(),
                group.getDescription(),
                group.getMdlVersion(),
                group.isPublicQuery(),
                tables,
                plain(projectModels(mdl.path("models"))),
                plain(projectRelationships(mdl.path("relationships"))),
                knowledge);
    }

    private List<java.util.Map<String, Object>> plain(JsonNode node) {
        return json.convertValue(
                node,
                new com.fasterxml.jackson.core.type.TypeReference<
                        List<java.util.Map<String, Object>>>() {});
    }

    private JsonNode projectModels(JsonNode values) {
        var result = json.createArrayNode();
        if (!values.isArray()) return result;
        for (JsonNode model : values) {
            var item = json.createObjectNode();
            for (String field : List.of("name", "primaryKey"))
                if (model.has(field)) item.set(field, model.get(field));
            described(model, item);
            if (model.path("properties").hasNonNull("tableRole"))
                item.set("tableRole", model.path("properties").get("tableRole"));
            item.put("derived", model.hasNonNull("refSql"));
            var columns = json.createArrayNode();
            for (JsonNode column : model.path("columns")) {
                var projected = json.createObjectNode();
                for (String field :
                        List.of("name", "type", "expression", "isCalculated", "isPrimaryKey"))
                    if (column.has(field)) projected.set(field, column.get(field));
                described(column, projected);
                JsonNode properties = column.path("properties");
                for (String field : List.of("displayName", "semanticType")) {
                    if (properties.path(field).isTextual())
                        projected.set(field, properties.get(field));
                }
                if (properties.path("synonyms").isArray()) {
                    var synonyms = projected.putArray("synonyms");
                    for (JsonNode synonym : properties.path("synonyms"))
                        if (synonym.isTextual()) synonyms.add(synonym.textValue());
                }
                columns.add(projected);
            }
            item.set("columns", columns);
            result.add(item);
        }
        return result;
    }

    private JsonNode projectRelationships(JsonNode values) {
        var result = project(values, List.of("name", "models", "joinType", "condition"));
        for (int i = 0; i < result.size(); i++)
            described(
                    values.get(i), (com.fasterxml.jackson.databind.node.ObjectNode) result.get(i));
        return result;
    }

    /** Descriptions live under properties. The browse view explicitly projects public fields. */
    private static void described(
            JsonNode source, com.fasterxml.jackson.databind.node.ObjectNode target) {
        if (source.path("properties").hasNonNull("description"))
            target.set("description", source.path("properties").get("description"));
        else if (source.hasNonNull("description"))
            target.set("description", source.get("description"));
    }

    private com.fasterxml.jackson.databind.node.ArrayNode project(
            JsonNode values, List<String> fields) {
        var result = json.createArrayNode();
        if (!values.isArray()) return result;
        for (JsonNode value : values) {
            var item = json.createObjectNode();
            for (String field : fields) if (value.has(field)) item.set(field, value.get(field));
            result.add(item);
        }
        return result;
    }
}
