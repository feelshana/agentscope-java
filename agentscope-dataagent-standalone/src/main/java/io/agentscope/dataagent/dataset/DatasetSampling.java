package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Bounded physical-row sampling; observed values never establish a complete domain. */
public final class DatasetSampling {
    public static final int ROW_LIMIT = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private DatasetSampling() {}

    public record ColumnSample(
            String name,
            List<String> values,
            int nullCount,
            int observedDistinct,
            boolean valuesTruncated,
            boolean skipped,
            String dateFormat) {
        public ColumnSample(
                String name,
                List<String> values,
                int nullCount,
                int observedDistinct,
                boolean valuesTruncated,
                boolean skipped) {
            this(name, values, nullCount, observedDistinct, valuesTruncated, skipped, null);
        }
    }

    public record Summary(
            int version,
            String status,
            String sampledAt,
            int sampledRows,
            int rowLimit,
            String method,
            List<ColumnSample> columns,
            String tableRole,
            boolean tableRoleConfirmed) {
        public Summary(
                int version,
                String status,
                String sampledAt,
                int sampledRows,
                int rowLimit,
                String method,
                List<ColumnSample> columns) {
            this(
                    version,
                    status,
                    sampledAt,
                    sampledRows,
                    rowLimit,
                    method,
                    columns,
                    "UNKNOWN",
                    false);
        }
    }

    public static String role(Summary summary) {
        String role =
                summary == null || summary.tableRole() == null ? "UNKNOWN" : summary.tableRole();
        return java.util.Set.of("FACT", "DIMENSION").contains(role) ? role : "UNKNOWN";
    }

    public static Summary withRole(Summary summary, String role, boolean confirmed) {
        if (summary == null) return null;
        String normalized =
                java.util.Set.of("FACT", "DIMENSION").contains(java.util.Objects.toString(role, ""))
                        ? role
                        : "UNKNOWN";
        return new Summary(
                summary.version(),
                summary.status(),
                summary.sampledAt(),
                summary.sampledRows(),
                summary.rowLimit(),
                summary.method(),
                summary.columns(),
                normalized,
                confirmed);
    }

    public static Summary empty(String status) {
        return new Summary(
                1,
                status,
                Instant.now().toString(),
                0,
                ROW_LIMIT,
                "数据库返回的前至多1000行，非随机采样，非完整枚举",
                List.of());
    }

    public static Summary read(
            DataSourceIntrospector introspector,
            ExternalDataSourceEntity source,
            String schema,
            String table,
            List<DataSourceIntrospector.ColumnInfo> columns,
            boolean enabled) {
        if (!enabled) return empty("DISABLED");
        if (!schema.matches("[a-zA-Z0-9_]+") || !table.matches("[a-zA-Z0-9_]+"))
            throw new DatasetException("非法表标识符");
        // Binary values are never transferred as sample payloads.
        String projection =
                columns.stream()
                        .map(
                                c -> {
                                    if (!c.name().matches("[a-zA-Z0-9_]+"))
                                        throw new DatasetException("非法列标识符");
                                    return binary(c.type())
                                            ? "NULL AS `" + c.name() + "`"
                                            : "LEFT(CAST(`"
                                                    + c.name()
                                                    + "` AS CHAR), 257) AS `"
                                                    + c.name()
                                                    + "`";
                                })
                        .collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT " + projection + " FROM `" + schema + "`.`" + table + "` LIMIT 1000";
        try (Connection connection = introspector.open(source);
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.setMaxRows(ROW_LIMIT);
            try (ResultSet rows = statement.executeQuery(sql)) {
                return collect(rows, columns);
            }
        } catch (java.sql.SQLException e) {
            return empty("FAILED");
        }
    }

    static Summary collect(ResultSet rows, List<DataSourceIntrospector.ColumnInfo> columns)
            throws java.sql.SQLException {
        List<LinkedHashSet<String>> values = new ArrayList<>();
        int[] nulls = new int[columns.size()];
        boolean[] truncated = new boolean[columns.size()];
        TemporalSamples[] dates = new TemporalSamples[columns.size()];
        for (int i = 0; i < dates.length; i++) dates[i] = new TemporalSamples();
        for (int i = 0; i < columns.size(); i++) values.add(new LinkedHashSet<>());
        int count = 0;
        while (count < ROW_LIMIT && rows.next()) {
            count++;
            for (int i = 0; i < columns.size(); i++) {
                if (binary(columns.get(i).type())) continue;
                String value = rows.getString(i + 1);
                dates[i].add(value);
                if (value == null) {
                    nulls[i]++;
                    continue;
                }
                if (value.length() > 256) {
                    value = value.substring(0, 256);
                    truncated[i] = true;
                }
                values.get(i).add(value);
            }
        }
        List<ColumnSample> result = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            result.add(
                    new ColumnSample(
                            columns.get(i).name(),
                            values.get(i).stream().limit(20).toList(),
                            nulls[i],
                            values.get(i).size(),
                            truncated[i] || values.get(i).size() > 20,
                            binary(columns.get(i).type()),
                            dates[i].format(columns.get(i).type())));
        }
        return new Summary(
                1,
                "SUCCESS",
                Instant.now().toString(),
                count,
                ROW_LIMIT,
                "前至多1000行；不同值按截断后的文本比较，仅为样本观察值",
                result);
    }

    private static boolean binary(String type) {
        return type != null
                && type.toLowerCase(java.util.Locale.ROOT)
                        .matches(".*(binary|blob|bytea|geometry).*");
    }

    public static Map<String, List<String>> examples(Summary summary) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (ColumnSample column : summary.columns())
            if (!column.skipped()) result.put(column.name(), column.values());
        return result;
    }

    public static String encode(Summary summary) {
        try {
            return JSON.writeValueAsString(summary);
        } catch (Exception e) {
            throw new DatasetException("保存采样摘要失败", e);
        }
    }

    public static Summary decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return JSON.readValue(value, Summary.class);
        } catch (Exception e) {
            return empty("INVALID");
        }
    }
}
