package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Adds missing sampling hints to source YAML without replacing confirmed decisions. */
public final class ModelTemporalMetadata {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ModelTemporalMetadata() {}

    public static boolean enrich(Path file, String summaryJson) {
        try {
            JsonNode parsed = YAML.readTree(file.toFile());
            if (!(parsed instanceof ObjectNode root)) return false;
            boolean hadProperties = root.has("properties");
            ObjectNode props =
                    root.path("properties") instanceof ObjectNode p
                            ? p
                            : root.putObject("properties");
            ObjectNode additions = YAML.createObjectNode();
            boolean changed = false;
            if (!props.has("table_role")) {
                props.put("table_role", "UNKNOWN");
                additions.put("table_role", "UNKNOWN");
                changed = true;
            }
            if (!props.has("table_role_confirmed")) {
                props.put("table_role_confirmed", false);
                additions.put("table_role_confirmed", false);
                changed = true;
            }
            if (!props.has("temporal_columns")) {
                ArrayNode temporal = props.putArray("temporal_columns");
                additions.set("temporal_columns", temporal);
                var summary = DatasetSampling.decode(summaryJson);
                for (JsonNode column : root.path("columns")) {
                    String name = column.path("name").asText();
                    String format = new TemporalSamples().format(column.path("type").asText());
                    if (format == null && summary != null)
                        format =
                                summary.columns().stream()
                                        .filter(c -> name.equals(c.name()))
                                        .map(DatasetSampling.ColumnSample::dateFormat)
                                        .filter(java.util.Objects::nonNull)
                                        .findFirst()
                                        .orElse(null);
                    if (format != null) {
                        var item = temporal.addObject();
                        item.put("column", name);
                        item.put("format", format);
                        item.put("source", format.equals("NATIVE") ? "schema" : "sample");
                    }
                }
                changed = true;
            }
            if (changed) {
                String original = Files.readString(file);
                String newline = original.contains("\r\n") ? "\r\n" : "\n";
                String block = YAML.writeValueAsString(additions).replaceFirst("^---\\s*\\R", "");
                block =
                        block.lines()
                                        .map(line -> "  " + line)
                                        .collect(java.util.stream.Collectors.joining(newline))
                                + newline;
                var header =
                        java.util.regex.Pattern.compile(
                                        "(?m)^properties:[ \\t]*(?:#[^\\r\\n]*)?\\r?\\n")
                                .matcher(original);
                if (header.find()) {
                    Files.writeString(
                            file,
                            original.substring(0, header.end())
                                    + block
                                    + original.substring(header.end()));
                } else if (!hadProperties) {
                    Files.writeString(
                            file,
                            original
                                    + (original.endsWith("\n") ? "" : newline)
                                    + "properties:"
                                    + newline
                                    + block);
                } else {
                    // Leave non-block YAML properties intact; absent hints remain conservative.
                    return false;
                }
            }
            return changed;
        } catch (Exception e) {
            throw new DatasetException("写入采样日期标注失败", e);
        }
    }
}
