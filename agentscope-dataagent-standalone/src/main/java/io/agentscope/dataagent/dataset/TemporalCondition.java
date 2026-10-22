package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The exact range condition for a limited fact table's time column, derived from the format the
 * platform detected at sampling ({@code properties.temporal_columns[].format}) and the project's
 * data source. Text dates without zero padding do not sort as strings, so they are converted with
 * the source's own parse function; padded text, native types and numeric date keys compare
 * directly. The model is given this condition instead of choosing a technique itself.
 */
public final class TemporalCondition {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern TOKEN = Pattern.compile("'[^']*'|[A-Za-z]+");
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 11, 1, 0, 0);

    private TemporalCondition() {}

    /**
     * One example condition for {@code [FROM, TO)} on {@code column}, or null when the format is
     * unknown or cannot be translated for this data source.
     */
    public static String of(String column, String format, String type, String dataSource) {
        if (column == null || format == null || format.isBlank()) return null;
        if (format.equals("NATIVE"))
            return column + " >= '2026-10-01' AND " + column + " < '2026-11-01'";
        String from;
        String to;
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(format, Locale.ROOT);
            from = FROM.format(f);
            to = TO.format(f);
        } catch (RuntimeException e) {
            return null;
        }
        boolean numeric =
                type != null
                        && type.toLowerCase(Locale.ROOT).matches(".*(int|number|numeric|long).*");
        if (numeric && from.chars().allMatch(Character::isDigit))
            return column + " >= " + from + " AND " + column + " < " + to;
        if (padded(format)) return column + " >= '" + from + "' AND " + column + " < '" + to + "'";
        String parsed = parse(column, format, dataSource);
        if (parsed == null) return null;
        boolean time = format.contains("H");
        String start = time ? "'2026-10-01 00:00:00'" : "'2026-10-01'";
        String end = time ? "'2026-11-01 00:00:00'" : "'2026-11-01'";
        return parsed + " >= " + start + " AND " + parsed + " < " + end;
    }

    /** Padded fields with a fixed layout sort the same as the times they hold. */
    static boolean padded(String format) {
        Matcher m = TOKEN.matcher(format);
        while (m.find()) {
            String token = m.group();
            if (token.startsWith("'")) continue;
            if (token.equals("M") || token.equals("d") || token.equals("H")) return false;
        }
        return true;
    }

    private static String parse(String column, String format, String dataSource) {
        String source = dataSource == null ? "" : dataSource.toLowerCase(Locale.ROOT);
        boolean mysql = source.contains("mysql") || source.contains("doris");
        boolean postgres = source.contains("postgres");
        if (!mysql && !postgres) return null;
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(format);
        int at = 0;
        while (m.find()) {
            out.append(format, at, m.start());
            String token = m.group();
            String mapped =
                    token.startsWith("'")
                            ? token.substring(1, token.length() - 1)
                            : mysql ? mysqlToken(token) : postgresToken(token);
            if (mapped == null) return null;
            out.append(mapped);
            at = m.end();
        }
        out.append(format.substring(at));
        return (mysql ? "STR_TO_DATE(" : "TO_TIMESTAMP(") + column + ", '" + out + "')";
    }

    private static String mysqlToken(String token) {
        return switch (token) {
            case "uuuu", "yyyy" -> "%Y";
            case "MM" -> "%m";
            case "M" -> "%c";
            case "dd" -> "%d";
            case "d" -> "%e";
            case "HH" -> "%H";
            case "H" -> "%k";
            case "mm" -> "%i";
            case "ss" -> "%s";
            default -> null;
        };
    }

    private static String postgresToken(String token) {
        return switch (token) {
            case "uuuu", "yyyy" -> "YYYY";
            case "MM" -> "MM";
            case "M" -> "FMMM";
            case "dd" -> "DD";
            case "d" -> "FMDD";
            case "HH" -> "HH24";
            case "H" -> "FMHH24";
            case "mm" -> "MI";
            case "ss" -> "SS";
            default -> null;
        };
    }

    /**
     * Context lines for the registered limited tables of a project (draft or published), one per
     * time column whose format is known; empty when nothing applies.
     */
    public static String describe(Path project, List<SqlWindowGate.Rule> rules) {
        if (project == null || rules == null || rules.isEmpty()) return "";
        String dataSource = dataSource(project);
        List<String> lines = new ArrayList<>();
        for (SqlWindowGate.Rule rule : rules) {
            JsonNode model = model(project, rule.table());
            if (model == null) continue;
            for (JsonNode temporal : model.path("properties").path("temporal_columns")) {
                String column = temporal.path("column").asText(null);
                if (column == null || !rule.timeColumns().contains(column)) continue;
                String format = temporal.path("format").asText(null);
                String type = null;
                for (JsonNode c : model.path("columns"))
                    if (column.equals(c.path("name").asText())) type = c.path("type").asText(null);
                String condition = of(column, format, type, dataSource);
                if (condition == null) continue;
                lines.add(
                        "- "
                                + rule.table()
                                + "."
                                + column
                                + (format.equals("NATIVE") ? "（日期时间类型）" : "（格式 " + format + "）")
                                + "：例如 2026-10-01 至 2026-10-31 写作 "
                                + condition);
            }
        }
        if (lines.isEmpty()) return "";
        return "\n# 时间条件写法（平台根据采样识别的列格式生成）\n"
                + String.join("\n", lines)
                + "\n时间范围条件按以上写法替换起止值，左闭右开；不对文本时间列直接做字符串比较，也不用 LIKE 匹配日期。\n";
    }

    private static String dataSource(Path project) {
        Path file = project.resolve("wren_project.yml");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            JsonNode node = YAML.readTree(file.toFile());
            return node == null ? null : node.path("data_source").asText(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static JsonNode model(Path project, String table) {
        Path models = project.resolve("models");
        if (!Files.isDirectory(models)) return null;
        try (Stream<Path> dirs = Files.list(models)) {
            for (Path dir : dirs.toList()) {
                Path file = dir.resolve("metadata.yml");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                JsonNode node = YAML.readTree(file.toFile());
                if (node != null
                        && (table.equalsIgnoreCase(node.path("name").asText())
                                || table.equalsIgnoreCase(
                                        node.path("table_reference").path("table").asText())))
                    return node;
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}
