package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            for (String section : List.of("rules", "glossary", "metrics", "caveats")) {
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
                plain(
                        project(
                                mdl.path("relationships"),
                                List.of("name", "models", "joinType", "condition"))),
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
            for (String field : List.of("name", "description", "primaryKey"))
                if (model.has(field)) item.set(field, model.get(field));
            item.set(
                    "columns",
                    project(
                            model.path("columns"),
                            List.of("name", "type", "description", "expression", "isCalculated")));
            result.add(item);
        }
        return result;
    }

    private JsonNode project(JsonNode values, List<String> fields) {
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
