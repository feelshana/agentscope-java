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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

/**
 * Bundled Excel upload samples and the optional read-only source in {@code demo_commerce_v1}.
 *
 * <p>The source is a row owned by {@link #OWNER} so Wren profiles, sampling and publishing treat it
 * like any other external source; only listing and association know it is shared.
 */
@Service
public class DemoDataService {
    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);

    public static final String OWNER = "__system__";
    public static final String SOURCE_ID = "builtin-demo-commerce-v1";
    public static final String SOURCE_NAME = "虚构零售示例数据源";
    public static final String SCHEMA = "demo_commerce_v1";
    public static final List<String> SAMPLE_FILES = List.of("示例订单.xlsx", "示例客户.xlsx");
    public static final List<String> TABLE_NAMES = List.of("demo_orders", "demo_customers");
    private static final int BATCH = 1000;
    private static final int LEGACY_CUSTOMER_COUNT = 2000;
    private static final int LEGACY_ORDER_COUNT = 48000;

    private record DemoTable(
            String name, String file, int rows, List<String> columns, String ddl) {}

    private static final DemoTable CUSTOMERS =
            new DemoTable(
                    "demo_customers",
                    SAMPLE_FILES.get(1),
                    200,
                    List.of(
                            "customer_id",
                            "customer_name",
                            "registered_at",
                            "region",
                            "member_level",
                            "acquisition_channel"),
                    "customer_id VARCHAR(16) PRIMARY KEY COMMENT '客户唯一编号',"
                            + "customer_name VARCHAR(32) NOT NULL COMMENT '虚构中文姓名，允许重名，不作关联键',"
                            + "registered_at DATETIME NOT NULL COMMENT '注册时间，北京时间',"
                            + "region VARCHAR(32) NOT NULL COMMENT '客户地区快照',"
                            + "member_level VARCHAR(32) NOT NULL COMMENT '客户等级快照',"
                            + "acquisition_channel VARCHAR(32) NOT NULL COMMENT '首次获客来源，不是订单销售渠道'");
    private static final DemoTable ORDERS =
            new DemoTable(
                    "demo_orders",
                    SAMPLE_FILES.get(0),
                    10000,
                    List.of(
                            "order_id",
                            "customer_id",
                            "order_time",
                            "product_id",
                            "product_name",
                            "category",
                            "sales_channel",
                            "campaign",
                            "order_status",
                            "quantity",
                            "unit_price",
                            "gross_amount",
                            "discount_amount",
                            "paid_amount",
                            "refund_amount",
                            "retained_cost",
                            "net_revenue",
                            "gross_profit",
                            "refunded_quantity"),
                    "order_id VARCHAR(16) PRIMARY KEY COMMENT '订单唯一编号，一行一订单，每单一种SKU',customer_id"
                        + " VARCHAR(16) NOT NULL COMMENT"
                        + " '多对一关联demo_customers.customer_id',order_time DATETIME NOT NULL COMMENT"
                        + " '下单时间，北京时间，2026年5月至10月',product_id VARCHAR(16) NOT NULL COMMENT"
                        + " '商品编号',product_name VARCHAR(64) NOT NULL COMMENT '虚构商品名称',category"
                        + " VARCHAR(32) NOT NULL COMMENT '商品品类',sales_channel VARCHAR(32) NOT NULL"
                        + " COMMENT '订单销售渠道',campaign VARCHAR(32) NOT NULL COMMENT"
                        + " '活动名称，不代表因果效果',order_status VARCHAR(16) NOT NULL COMMENT"
                        + " '已完成、已取消、已退款、部分退款',quantity INT NOT NULL COMMENT '原始购买件数',unit_price"
                        + " DECIMAL(14,2) NOT NULL COMMENT '原价单价，元',gross_amount DECIMAL(14,2) NOT"
                        + " NULL COMMENT '原价金额，元',discount_amount DECIMAL(14,2) NOT NULL COMMENT"
                        + " '优惠金额，元',paid_amount DECIMAL(14,2) NOT NULL COMMENT"
                        + " '退款前实付金额，元，取消为零',refund_amount DECIMAL(14,2) NOT NULL COMMENT"
                        + " '截至快照累计退款，元，没有退款发生日期',retained_cost DECIMAL(14,2) NOT NULL COMMENT"
                        + " '退货回冲后的留存商品成本，元',net_revenue DECIMAL(14,2) NOT NULL COMMENT"
                        + " '净销售额=实付减退款，元，不可再次扣退款',gross_profit DECIMAL(14,2) NOT NULL COMMENT"
                        + " '毛利=净销售额减留存成本，元，不是净利润',refunded_quantity INT NOT NULL COMMENT"
                        + " '退货件数',INDEX idx_order_time (order_time), INDEX idx_customer"
                        + " (customer_id)");

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

    public static boolean isCurrentBuiltin(ExternalDataSourceEntity source) {
        return isBuiltin(source) && SOURCE_ID.equals(source.getId());
    }

    /** Whether users can pick the built-in source right now. */
    public boolean sourceAvailable() {
        return properties.isEnabled() && sourceReady;
    }

    /** The bundled demo files, in display order. */
    public List<String> samples() {
        if (!properties.isSamplesEnabled()) return List.of();
        return SAMPLE_FILES.stream().filter(name -> resource(name).exists()).toList();
    }

    /** A demo file's bytes; only the fixed names are served, so no path comes from the caller. */
    public byte[] sample(String name) {
        if (!samples().contains(name)) throw new DatasetException("示例文件不存在", 404);
        try (InputStream in = resource(name).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new DatasetException("示例文件读取失败", 500);
        }
    }

    private ClassPathResource resource(String name) {
        return new ClassPathResource("demo-samples/" + name);
    }

    @EventListener(ApplicationReadyEvent.class)
    void prepareInBackground() {
        if (!properties.isEnabled()) return;
        Schedulers.boundedElastic().schedule(this::prepare);
    }

    /** A failure only leaves the built-in source unavailable; upload samples keep working. */
    void prepare() {
        sourceReady = false;
        try {
            if (properties.getPassword() == null || properties.getPassword().isBlank()) {
                log.warn("Demo data source skipped: dataagent.demo.password is not set");
                return;
            }
            if (!usesCurrentSchema(properties.getJdbcUrl())) {
                log.warn(
                        "Demo data source skipped: configure dataagent.demo.jdbc-url for {}",
                        SCHEMA);
                return;
            }
            try (Connection admin =
                    DriverManager.getConnection(store.url(), store.username(), store.password())) {
                seed(admin);
                grantReader(admin);
            } catch (SQLException | IOException e) {
                // A DBA may have prepared the versioned synthetic database; the connection check
                // below has the last word.
                log.warn("Demo database not prepared here: {}", e.getMessage());
            }
            ExternalDataSourceEntity source = saveSource();
            String error = introspector.connectionError(source);
            sourceReady = error == null && complete(source);
            if (error != null) log.warn("Demo data source unavailable: {}", error);
            else if (sourceReady) log.info("Demo data source ready: {}", SCHEMA);
            else log.warn("Demo data source unavailable: synthetic tables are incomplete");
        } catch (RuntimeException | SQLException e) {
            log.warn("Demo data source unavailable: {}", e.getMessage());
        }
    }

    static boolean usesCurrentSchema(String url) {
        return url != null && url.matches("jdbc:mysql://[^/]+/" + SCHEMA + "(?:\\?.*)?");
    }

    private void seed(Connection admin) throws SQLException, IOException {
        try (Statement st = admin.createStatement()) {
            st.execute(
                    "CREATE DATABASE IF NOT EXISTS "
                            + SCHEMA
                            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            for (DemoTable table : List.of(CUSTOMERS, ORDERS)) {
                st.execute(
                        "CREATE TABLE IF NOT EXISTS "
                                + SCHEMA
                                + "."
                                + table.name()
                                + " ("
                                + table.ddl()
                                + ") COMMENT='完全虚构的零售示例，2026-10-31快照'");
            }
            long customers = rowCount(st, CUSTOMERS);
            long orders = rowCount(st, ORDERS);
            if (completeCounts(customers, orders)) return;
            // The customer import commits first. Resume it only if no orders have been imported.
            if (orders != 0 || (customers != 0 && customers != CUSTOMERS.rows())) {
                throw new SQLException("示例表行数不完整，保留现有数据");
            }
        }
        load(admin, CUSTOMERS);
        load(admin, ORDERS);
    }

    /** Load the known schema once; never overwrite a partially populated deployment. */
    private void load(Connection admin, DemoTable table) throws SQLException, IOException {
        try (Statement st = admin.createStatement()) {
            long count = rowCount(st, table);
            if (count == table.rows()) return;
            if (count != 0) throw new SQLException("示例表行数不完整，保留现有数据：" + table.name());
        }
        List<List<String>> rows;
        try (InputStream in = resource(table.file()).getInputStream()) {
            var parsed = parser.parse(in, table.file(), table.rows() + 1);
            if (parsed.totalRows() != table.rows()
                    || parsed.truncated()
                    || !parsed.columns().stream()
                            .map(c -> c.name())
                            .toList()
                            .equals(table.columns())) {
                throw new IOException("示例文件结构或行数不匹配：" + table.file());
            }
            rows = parsed.rows();
        }
        String holders =
                String.join(",", java.util.Collections.nCopies(table.columns().size(), "?"));
        boolean autoCommit = admin.getAutoCommit();
        admin.setAutoCommit(false);
        try (PreparedStatement insert =
                admin.prepareStatement(
                        "INSERT INTO "
                                + SCHEMA
                                + "."
                                + table.name()
                                + " ("
                                + String.join(",", table.columns())
                                + ") VALUES ("
                                + holders
                                + ")")) {
            int pending = 0;
            for (List<String> row : rows) {
                for (int i = 0; i < table.columns().size(); i++)
                    insert.setString(i + 1, row.get(i));
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
        log.info("Synthetic demo table {} loaded: {} rows", table.name(), rows.size());
    }

    private boolean complete(ExternalDataSourceEntity source) throws SQLException {
        try (Connection connection =
                        DriverManager.getConnection(
                                introspector.validatedUrl(source),
                                source.getUsername(),
                                source.getPassword());
                Statement st = connection.createStatement()) {
            return completeCounts(rowCount(st, CUSTOMERS), rowCount(st, ORDERS));
        }
    }

    private long rowCount(Statement statement, DemoTable table) throws SQLException {
        try (ResultSet rows =
                statement.executeQuery("SELECT COUNT(*) FROM " + SCHEMA + "." + table.name())) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private boolean completeCounts(long customers, long orders) {
        return (customers == CUSTOMERS.rows() && orders == ORDERS.rows())
                || (customers == LEGACY_CUSTOMER_COUNT && orders == LEGACY_ORDER_COUNT);
    }

    /** The reader sees the synthetic database only, so the shared source cannot reach platform data. */
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
