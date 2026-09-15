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
package io.agentscope.dataagent.ontology.source;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import io.agentscope.dataagent.dataset.DatasetStoreProperties;
import io.agentscope.dataagent.ontology.source.model.ColumnMapping;
import io.agentscope.dataagent.ontology.source.model.DerivedTable;
import io.agentscope.dataagent.ontology.source.model.SourceBatch;
import io.agentscope.dataagent.ontology.source.model.SourceManifest;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 按 {@link SourceManifest} 的定义在 MySQL 8.0 中执行建表和数据灌入。
 *
 * <p>核心流程：parseSourceJson → createTables → ingestBatches → executeDerived。
 */
@Service
public class SourceIngestService {

    private static final Logger log = LoggerFactory.getLogger(SourceIngestService.class);
    private static final int BATCH_SIZE = 1000;

    private final SourceParser sourceParser;
    private final DatasetStoreProperties storeProps;

    public SourceIngestService(SourceParser sourceParser, DatasetStoreProperties storeProps) {
        this.sourceParser = sourceParser;
        this.storeProps = storeProps;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                storeProps.url(), storeProps.username(), storeProps.password());
    }

    /**
     * 解析 sources.json 文本。
     */
    public SourceManifest parseSourceJson(String jsonText) {
        return sourceParser.parse(jsonText);
    }

    /**
     * 根据 manifest 中的 batches 定义，在 MySQL 中创建对应的表。
     * 若表已存在则先 DROP 再 CREATE。
     */
    public void createTables(SourceManifest manifest) {
        for (SourceBatch batch : manifest.getBatches()) {
            String tableName = batch.getTable();
            List<ColumnMapping> columns = batch.getColumns();
            Map<String, String> constants = batch.getConstants();

            StringBuilder ddl = new StringBuilder();
            ddl.append("CREATE TABLE IF NOT EXISTS `").append(tableName).append("` (\n");

            List<String> colDefs = new ArrayList<>();
            for (ColumnMapping mapping : columns) {
                colDefs.add("  `" + mapping.getName() + "` " + mapping.toMysqlType());
            }
            // 常量列
            if (constants != null) {
                for (String constCol : constants.keySet()) {
                    colDefs.add("  `" + constCol + "` VARCHAR(64)");
                }
            }
            ddl.append(String.join(",\n", colDefs));
            ddl.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            executeDdl(tableName, ddl.toString());
            log.info("SourceIngestService: 创建表 {} ({}列)", tableName, colDefs.size());
        }
    }

    /**
     * 使用 EasyExcel 流式读取 Excel 文件，边读边批量 INSERT，每 BATCH_SIZE 行提交一次并释放内存。
     *
     * @param batch      批次定义
     * @param excelInput Excel 文件输入流
     * @return 插入行数
     */
    public int ingestBatch(SourceBatch batch, InputStream excelInput) {
        List<ColumnMapping> columnMappings = batch.getColumns();
        Map<String, String> constants = batch.getConstants();

        // 列映射信息（在 invokeHeadMap 中填充）
        List<Integer> sourceColIndexes = new ArrayList<>();
        List<ColumnMapping> activeMappings = new ArrayList<>();
        List<String> targetCols = new ArrayList<>();
        List<String> constValues = new ArrayList<>();

        // 构建 INSERT SQL（延迟到拿到表头后再构建）
        String[] insertSqlHolder = new String[1];

        AtomicInteger rowCount = new AtomicInteger(0);

        AnalysisEventListener<Map<Integer, String>> listener =
                new AnalysisEventListener<>() {
                    private Connection conn;
                    private PreparedStatement ps;
                    private int paramCount;

                    @Override
                    public void invokeHeadMap(
                            Map<Integer, String> headMap, AnalysisContext context) {
                        // 建立 Excel 表头名 → 列索引 的映射
                        Map<String, Integer> headerIndex = new LinkedHashMap<>();
                        for (Map.Entry<Integer, String> entry : headMap.entrySet()) {
                            if (entry.getValue() != null) {
                                headerIndex.put(entry.getValue().trim(), entry.getKey());
                            }
                        }

                        // 按 manifest 列映射找到对应的 Excel 列
                        for (ColumnMapping mapping : columnMappings) {
                            String excelColName =
                                    mapping.getComment() != null && !mapping.getComment().isBlank()
                                            ? mapping.getComment()
                                            : mapping.getName();
                            Integer idx = headerIndex.get(excelColName);
                            if (idx == null && !excelColName.equals(mapping.getName())) {
                                idx = headerIndex.get(mapping.getName());
                            }
                            if (idx == null) {
                                log.warn(
                                        "批次 {}: Excel 列 '{}' 在表头中未找到，跳过",
                                        batch.getId(),
                                        excelColName);
                                continue;
                            }
                            targetCols.add("`" + mapping.getName() + "`");
                            sourceColIndexes.add(idx);
                            activeMappings.add(mapping);
                        }

                        // 常量列
                        if (constants != null) {
                            for (Map.Entry<String, String> ce : constants.entrySet()) {
                                targetCols.add("`" + ce.getKey() + "`");
                                constValues.add(ce.getValue());
                            }
                        }

                        paramCount = targetCols.size();
                        String sql =
                                "INSERT INTO `"
                                        + batch.getTable()
                                        + "` ("
                                        + String.join(", ", targetCols)
                                        + ") VALUES ("
                                        + String.join(
                                                ", ", targetCols.stream().map(c -> "?").toList())
                                        + ")";
                        insertSqlHolder[0] = sql;

                        try {
                            conn = connect();
                            conn.setAutoCommit(false);
                            ps = conn.prepareStatement(sql);
                        } catch (SQLException e) {
                            throw new RuntimeException(
                                    "批次 " + batch.getId() + " 准备 INSERT 失败: " + e.getMessage(), e);
                        }
                    }

                    @Override
                    public void invoke(Map<Integer, String> rowData, AnalysisContext context) {
                        if (ps == null) return; // 表头尚未解析完成
                        try {
                            int paramIdx = 1;
                            for (int i = 0; i < sourceColIndexes.size(); i++) {
                                String rawValue = rowData.get(sourceColIndexes.get(i));
                                if (rawValue != null) {
                                    rawValue = rawValue.trim();
                                    if (rawValue.isEmpty()) rawValue = null;
                                }
                                setParameter(
                                        ps, paramIdx++, rawValue, activeMappings.get(i).getType());
                            }
                            // 常量值
                            for (String cv : constValues) {
                                ps.setString(paramIdx++, cv);
                            }
                            ps.addBatch();
                            int count = rowCount.incrementAndGet();

                            if (count % BATCH_SIZE == 0) {
                                ps.executeBatch();
                                ps.clearBatch();
                            }
                        } catch (SQLException e) {
                            throw new RuntimeException(
                                    "批次 "
                                            + batch.getId()
                                            + " 行 "
                                            + rowCount.get()
                                            + " 插入失败: "
                                            + e.getMessage(),
                                    e);
                        }
                    }

                    @Override
                    public void doAfterAllAnalysed(AnalysisContext context) {
                        if (ps == null) return;
                        try {
                            ps.executeBatch();
                            conn.commit();
                        } catch (SQLException e) {
                            throw new RuntimeException(
                                    "批次 " + batch.getId() + " 最终提交失败: " + e.getMessage(), e);
                        } finally {
                            closeQuietly(ps, conn);
                        }
                    }

                    @Override
                    public void onException(Exception exception, AnalysisContext context)
                            throws Exception {
                        log.warn(
                                "批次 {} EasyExcel 解析异常 (行 {}): {}",
                                batch.getId(),
                                context.readRowHolder().getRowIndex(),
                                exception.getMessage());
                        // 跳过异常行，继续处理
                    }
                };

        try {
            EasyExcel.read(excelInput)
                    .registerReadListener(listener)
                    .sheet(0)
                    .headRowNumber(1)
                    .doRead();
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException("批次 " + batch.getId() + " 数据灌入失败: " + e.getMessage(), e);
        }

        int total = rowCount.get();
        log.info("SourceIngestService: 批次 {} 插入 {} 行到表 {}", batch.getId(), total, batch.getTable());
        return total;
    }

    private static void closeQuietly(AutoCloseable... closeables) {
        for (AutoCloseable c : closeables) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
    }

    /**
     * 按声明顺序执行派生表 SQL（CREATE TABLE AS SELECT）。
     */
    public void executeDerived(List<DerivedTable> derived) {
        if (derived == null || derived.isEmpty()) return;

        try (Connection conn = connect();
                Statement stmt = conn.createStatement()) {
            for (DerivedTable dt : derived) {
                // 先删除已有表
                stmt.execute("DROP TABLE IF EXISTS `" + dt.getTable() + "`");
                // CREATE TABLE AS SELECT
                String createSql = "CREATE TABLE `" + dt.getTable() + "` AS " + dt.getSql().trim();
                stmt.execute(createSql);
                log.info("SourceIngestService: 创建派生表 {}", dt.getTable());
            }
        } catch (Exception e) {
            throw new RuntimeException("派生表执行失败: " + e.getMessage(), e);
        }
    }

    private void executeDdl(String tableName, String ddl) {
        try (Connection conn = connect();
                Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS `" + tableName + "`");
            stmt.execute(ddl);
        } catch (Exception e) {
            throw new RuntimeException("建表失败 " + tableName + ": " + e.getMessage(), e);
        }
    }

    /**
     * 根据 sources.json 中的类型设置 PreparedStatement 参数。
     */
    private void setParameter(PreparedStatement ps, int idx, String value, String type)
            throws java.sql.SQLException {
        if (value == null || value.isEmpty()) {
            ps.setNull(idx, java.sql.Types.VARCHAR);
            return;
        }
        try {
            if (type == null) {
                ps.setString(idx, value);
                return;
            }
            switch (type.toLowerCase()) {
                case "bigint" -> ps.setLong(idx, Long.parseLong(value));
                case "double" -> ps.setDouble(idx, Double.parseDouble(value));
                case "timestamp" -> {
                    LocalDateTime ldt = parseTimestamp(value);
                    ps.setObject(idx, ldt);
                }
                case "date_compact" -> {
                    LocalDate ld = parseDate(value);
                    ps.setObject(idx, ld);
                }
                case "percent" -> {
                    // 处理 "85.5%" 或 "0.855" 格式
                    String cleaned = value.replace("%", "").trim();
                    ps.setBigDecimal(idx, new BigDecimal(cleaned));
                }
                case "yesno" -> {
                    boolean yes =
                            "是".equals(value)
                                    || "yes".equalsIgnoreCase(value)
                                    || "true".equalsIgnoreCase(value)
                                    || "1".equals(value);
                    ps.setInt(idx, yes ? 1 : 0);
                }
                default -> ps.setString(idx, value);
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            log.warn("类型转换失败: value='{}' type={}, 降级为字符串", value, type);
            ps.setString(idx, value);
        }
    }

    private static final DateTimeFormatter[] TIMESTAMP_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
    };

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("yyyyMMdd"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
    };

    private LocalDateTime parseTimestamp(String value) {
        for (DateTimeFormatter fmt : TIMESTAMP_FORMATS) {
            try {
                return LocalDateTime.parse(value, fmt);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        throw new DateTimeParseException("无法解析时间戳: " + value, value, 0);
    }

    private LocalDate parseDate(String value) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, fmt);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        throw new DateTimeParseException("无法解析日期: " + value, value, 0);
    }
}
