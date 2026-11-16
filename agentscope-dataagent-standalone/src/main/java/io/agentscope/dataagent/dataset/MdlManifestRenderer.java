package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure platform-manifest projection, independent of persistence and publication IO. */
final class MdlManifestRenderer {
    record Header(
            String groupId,
            String groupName,
            int version,
            Instant publishedAt,
            String dataSource) {}

    private final ObjectMapper mapper;

    MdlManifestRenderer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode render(Header header, MdlWorkspaceReader.Snapshot snapshot) {
        ObjectNode root = mapper.createObjectNode();
        root.put("groupId", header.groupId());
        root.put("groupName", header.groupName());
        root.put("version", header.version());
        root.put("publishedAt", header.publishedAt().toString());
        root.put("dataSource", header.dataSource());
        Map<String, String> datasetByModel = new HashMap<>();
        for (MdlWorkspaceReader.WorkspaceModel m : snapshot.models()) {
            if (m.datasetId() != null) {
                datasetByModel.put(m.name(), m.datasetId());
            }
        }
        ArrayNode models = root.putArray("models");
        for (MdlWorkspaceReader.WorkspaceModel m : snapshot.models()) {
            ObjectNode node = models.addObject();
            node.put("name", m.name());
            node.put("datasetId", m.datasetId() == null ? "" : m.datasetId());
            node.put("datasetName", m.datasetName());
            if (m.refSql() != null) {
                node.put("derived", true);
            }
            if (m.description() != null) {
                node.put("description", m.description());
            }
            ArrayNode columns = node.putArray("columns");
            for (MdlWorkspaceReader.WorkspaceColumn c : m.columns()) {
                ObjectNode col = columns.addObject();
                col.put("name", c.name());
                col.put("type", c.type());
                if (c.relationship() != null) {
                    col.put("relationship", c.relationship());
                }
                if (c.calculated()) {
                    col.put("calculated", true);
                    col.put("expression", c.expression());
                }
                if (c.description() != null) {
                    col.put("description", c.description());
                }
            }
        }
        ArrayNode relations = root.putArray("relations");
        for (MdlWorkspaceReader.WorkspaceRelation r : snapshot.relations()) {
            ObjectNode node = relations.addObject();
            node.put("name", r.name());
            node.put("leftModel", r.leftModel());
            node.put("rightModel", r.rightModel());
            node.put("joinType", r.joinType());
            node.put("condition", r.condition());
            node.put("sourceDatasetId", datasetByModel.getOrDefault(r.leftModel(), ""));
            node.put("targetDatasetId", datasetByModel.getOrDefault(r.rightModel(), ""));
            node.put("sourceColumn", r.sourceColumns().isEmpty() ? "" : r.sourceColumns().get(0));
            node.put("targetColumn", r.targetColumns().isEmpty() ? "" : r.targetColumns().get(0));
            node.set("sourceColumns", mapper.valueToTree(r.sourceColumns()));
            node.set("targetColumns", mapper.valueToTree(r.targetColumns()));
        }
        ArrayNode cubes = root.putArray("cubes");
        for (MdlWorkspaceReader.WorkspaceCube c : snapshot.cubes()) {
            ObjectNode node = cubes.addObject();
            node.put("name", c.name());
            node.put("baseModel", c.baseModel());
            if (c.description() != null) {
                node.put("description", c.description());
            }
            appendMetaMembers(node.putArray("measures"), c.measures());
            appendMetaMembers(node.putArray("dimensions"), c.dimensions());
            appendMetaMembers(node.putArray("timeDimensions"), c.timeDimensions());
        }
        ArrayNode views = root.putArray("views");
        for (MdlWorkspaceReader.WorkspaceView v : snapshot.views()) {
            ObjectNode node = views.addObject();
            node.put("name", v.name());
            node.put("sql", v.sql());
            if (v.description() != null) {
                node.put("description", v.description());
            }
        }
        return root;
    }

    private static void appendMetaMembers(
            ArrayNode array, List<MdlWorkspaceReader.WorkspaceMember> members) {
        for (MdlWorkspaceReader.WorkspaceMember m : members) {
            ObjectNode node = array.addObject();
            node.put("name", m.name());
            node.put("expression", m.expression());
            node.put("type", m.type());
            if (m.description() != null) {
                node.put("description", m.description());
            }
        }
    }
}
