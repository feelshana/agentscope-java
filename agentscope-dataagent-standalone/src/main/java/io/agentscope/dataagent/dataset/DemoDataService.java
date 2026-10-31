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

import io.agentscope.dataagent.dataset.parser.ExcelParser;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

/**
 * spec105 / ADR0122: the demo files read from the deployment directory, the {@code demo_data}
 * database loaded from them, and the built-in read-only data source pointing at it.
 *
 * <p>The source is a row owned by {@link #OWNER} so Wren profiles, sampling and publishing treat it
 * like any other external source; only listing and association know it is shared.
 */
@Service
public class DemoDataService {
    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);

    public static final String OWNER = "__system__";
    public static final String SOURCE_ID = "builtin-demo-data";
    public static final String SOURCE_NAME = "示例数据源";
    public static final String SCHEMA = "demo_data";
    public static final List<String> SAMPLE_FILES = List.of("报表访问明细.xlsx", "用户列表.xlsx");

    private static final int BATCH = 1000;
    private static final String VISITS = "report_visits";
    private static final String USERS = "platform_users";
    private static final int VISIT_TIME_COLUMN = 5;
    private static final Pattern SLASHED_TIME =
            Pattern.compile("(\\d{4})/(\\d{1,2})/(\\d{1,2}) (\\d{1,2}):(\\d{2})");

    private final DemoDataProperties properties;
    private final DatasetStoreProperties store;
    private final ExternalDataSourceRepository sources;
    private final DataSourceIntrospector introspector;
    private final ExcelParser parser;
    private volatile boolean sourceReady;

    public DemoDataService(
            DemoDataProperties properties,
            DatasetStoreProperties store,
            ExternalDataSourceRepository sources,
            DataSourceIntrospector introspector,
            ExcelParser parser) {
        this.properties = properties;
        this.store = store;
        this.sources = sources;
        this.introspector = introspector;
        this.parser = parser;
    }

    public static boolean isBuiltin(ExternalDataSourceEntity source) {
        return OWNER.equals(source.getOwnerId());
    }

    /** Whether users can pick the built-in source right now. */
    public boolean sourceAvailable() {
        return properties.isEnabled() && sourceReady;
    }

    /** The demo files present in the deployment directory, in display order. */
    public List<String> samples() {
        if (!properties.isEnabled()) return List.of();
        return SAMPLE_FILES.stream().filter(name -> Files.isRegularFile(path(name))).toList();
    }

    /** A demo file's bytes; only the fixed names are served, so no path comes from the caller. */
    public byte[] sample(String name) {
        if (!samples().contains(name)) throw new DatasetException("示例文件不存在", 404);
        try {
            return Files.readAllBytes(path(name));
        } catch (IOException e) {
            throw new DatasetException("示例文件读取失败", 500);
        }
    }

    private Path path(String name) {
        return Path.of(properties.getSamplesDir()).resolve(name);
    }

    @EventListener(ApplicationReadyEvent.class)
    void prepareInBackground() {
        if (!properties.isEnabled()) return;
        Schedulers.boundedElastic().schedule(this::prepare);
    }

    /** A failure only leaves the built-in source unavailable; upload samples keep working. */
    void prepare() {
        try {
            if (properties.getPassword() == null || properties.getPassword().isBlank()) {
                log.warn("Demo data source skipped: dataagent.demo.password is not set");
                return;
            }
            try (Connection admin =
                    DriverManager.getConnection(store.url(), store.username(), store.password())) {
                seed(admin);
                grantReader(admin);
            } catch (SQLException | IOException e) {
                // A DBA may have prepared demo_data; the connection check below has the last word.
                log.warn("Demo database not prepared here: {}", e.getMessage());
            }
            ExternalDataSourceEntity source = saveSource();
            String error = introspector.connectionError(source);
            sourceReady = error == null;
            if (error != null) log.warn("Demo data source unavailable: {}", error);
            else log.info("Demo data source ready: {}", SCHEMA);
        } catch (RuntimeException e) {
            log.warn("Demo data source unavailable: {}", e.getMessage());
        }
    }

    private void seed(Connection admin) throws SQLException, IOException {
        try (Statement st = admin.createStatement()) {
            st.execute(
                    "CREATE DATABASE IF NOT EXISTS "
                            + SCHEMA
                            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            st.execute(
                    "CREATE TABLE IF NOT EXISTS "
                            + SCHEMA
                            + "."
                            + VISITS
                            + " (project_name VARCHAR(191) COMMENT '项目名称',"
                            + " report_name VARCHAR(512) COMMENT '报表名称',"
                            + " directory VARCHAR(512) COMMENT '目录',"
                            + " asset_type VARCHAR(32) COMMENT '类型',"
                            + " user_account VARCHAR(128) COMMENT '用户账号',"
                            + " visit_time DATETIME COMMENT '访问时间',"
                            + " INDEX idx_visit_time (visit_time),"
                            + " INDEX idx_user_account (user_account))"
                            + " COMMENT='报表访问明细'");
            st.execute(
                    "CREATE TABLE IF NOT EXISTS "
                            + SCHEMA
                            + "."
                            + USERS
                            + " (user_id VARCHAR(32) COMMENT '用户ID',"
                            + " account VARCHAR(128) COMMENT '账号',"
                            + " company VARCHAR(191) COMMENT '公司',"
                            + " department VARCHAR(191) COMMENT '部门',"
                            + " title VARCHAR(64) COMMENT '职位',"
                            + " INDEX idx_account (account))"
                            + " COMMENT='用户列表'");
        }
        upgradeVisitTime(admin);
        load(admin, VISITS, SAMPLE_FILES.get(0), 6);
        load(admin, USERS, SAMPLE_FILES.get(1), 5);
    }

    /** Tables loaded before visit_time became DATETIME; same steps as migration 0106. */
    private void upgradeVisitTime(Connection admin) throws SQLException {
        try (PreparedStatement type =
                admin.prepareStatement(
                        "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?"
                                + " AND TABLE_NAME = ? AND COLUMN_NAME = 'visit_time'")) {
            type.setString(1, SCHEMA);
            type.setString(2, VISITS);
            try (ResultSet rs = type.executeQuery()) {
                if (!rs.next() || "datetime".equalsIgnoreCase(rs.getString(1))) return;
            }
        }
        String table = SCHEMA + "." + VISITS;
        try (Statement st = admin.createStatement()) {
            st.executeUpdate(
                    "UPDATE "
                            + table
                            + " SET visit_time = CONCAT('2026-09-30 ',"
                            + " LPAD(SUBSTRING_INDEX(visit_time, ' ', -1), 5, '0'), ':00')"
                            + " WHERE visit_time LIKE '2026/9/31 %'");
            st.execute("ALTER TABLE " + table + " MODIFY visit_time DATETIME COMMENT '访问时间'");
        }
        log.info("Demo table {} upgraded: visit_time is DATETIME", VISITS);
    }

    /**
     * The file writes visit times as text in two shapes, including a day past the end of the month
     * (2026/9/31); that one moves to the month's last day so every row keeps a real time.
     */
    static String standardVisitTime(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher slashed = SLASHED_TIME.matcher(value.trim());
        if (slashed.matches()) {
            YearMonth month =
                    YearMonth.of(
                            Integer.parseInt(slashed.group(1)), Integer.parseInt(slashed.group(2)));
            int day = Math.min(Integer.parseInt(slashed.group(3)), month.lengthOfMonth());
            return String.format(
                    "%s %02d:%s:00",
                    month.atDay(day), Integer.parseInt(slashed.group(4)), slashed.group(5));
        }
        return value.trim().length() == 10 ? value.trim() + " 00:00:00" : value.trim();
    }

    /** Loads a table once; columns follow file order and only visit_time is normalised. */
    private void load(Connection admin, String table, String file, int columns)
            throws SQLException, IOException {
        try (Statement st = admin.createStatement();
                ResultSet rs =
                        st.executeQuery("SELECT 1 FROM " + SCHEMA + "." + table + " LIMIT 1")) {
            if (rs.next()) return;
        }
        if (!Files.isRegularFile(path(file))) {
            log.warn("Demo table {} left empty: {} not found", table, file);
            return;
        }
        List<List<String>> rows;
        try (InputStream in = Files.newInputStream(path(file))) {
            rows = parser.parse(in, file, Integer.MAX_VALUE).rows();
        }
        String holders = String.join(",", java.util.Collections.nCopies(columns, "?"));
        boolean autoCommit = admin.getAutoCommit();
        admin.setAutoCommit(false);
        try (PreparedStatement insert =
                admin.prepareStatement(
                        "INSERT INTO " + SCHEMA + "." + table + " VALUES (" + holders + ")")) {
            int pending = 0;
            for (List<String> row : rows) {
                for (int i = 0; i < columns; i++) {
                    String value = i < row.size() ? row.get(i) : null;
                    boolean time = VISITS.equals(table) && i == VISIT_TIME_COLUMN;
                    insert.setString(i + 1, time ? standardVisitTime(value) : value);
                }
                insert.addBatch();
                if (++pending == BATCH) {
                    insert.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) insert.executeBatch();
            admin.commit();
        } catch (SQLException e) {
            admin.rollback();
            throw e;
        } finally {
            admin.setAutoCommit(autoCommit);
        }
        log.info("Demo table {} loaded: {} rows", table, rows.size());
    }

    /** The reader sees demo_data only, so the shared source cannot reach platform data. */
    private void grantReader(Connection admin) throws SQLException {
        String user = properties.getUsername();
        if (user == null || !user.matches("[A-Za-z0-9_]{1,32}")) {
            throw new SQLException("dataagent.demo.username 只能包含字母、数字和下划线");
        }
        String account = "'" + user + "'@'%'";
        String secret = properties.getPassword().replace("\\", "\\\\").replace("'", "''");
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE USER IF NOT EXISTS " + account + " IDENTIFIED BY '" + secret + "'");
            st.execute("ALTER USER " + account + " IDENTIFIED BY '" + secret + "'");
            st.execute("GRANT SELECT ON " + SCHEMA + ".* TO " + account);
        }
    }

    private ExternalDataSourceEntity saveSource() {
        ExternalDataSourceEntity source =
                sources.findById(SOURCE_ID)
                        .orElseGet(
                                () ->
                                        new ExternalDataSourceEntity(
                                                SOURCE_ID,
                                                OWNER,
                                                SOURCE_NAME,
                                                "mysql",
                                                properties.getJdbcUrl(),
                                                properties.getUsername(),
                                                properties.getPassword(),
                                                true));
        source.setJdbcUrl(properties.getJdbcUrl());
        source.setUsername(properties.getUsername());
        source.setPassword(properties.getPassword());
        source.setUpdatedAt(Instant.now());
        return sources.save(source);
    }
}
