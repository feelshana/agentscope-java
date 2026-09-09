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
package io.agentscope.dataagent.tools.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the DataAgent toolkit defaults. Exposes a {@link DataSourceRegistry}, a
 * {@link SqlConnector} ({@link JdbcSqlConnector}) and {@link ChartRenderer} ({@link
 * StubChartRenderer}) so operators can override each independently — e.g. a Spring profile that
 * wires a richer registry or a server-side PNG renderer.
 *
 * <p>On the default embedded-H2 deployment the registry is seeded with the application's own
 * database as a demo source AND a {@code test-data} MySQL source pointing at
 * {@code 127.0.0.1:3306/test_data} for the tenant analytics tables. The MySQL DDL lives in
 * {@code src/main/resources/mysql-schema.sql}.
 *
 * <p>The actual registration of the toolkit onto the main agent's toolkit lives in
 * {@link DataToolkitRegistrar} so the {@code @PostConstruct} cannot tangle with self-injection
 * of the {@code @Bean} methods defined here.
 */
@Configuration
public class DataToolkitConfig {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitConfig.class);

    /** Default MySQL connection for the tenant analytics tables. */
    private static final String MYSQL_URL =
            "jdbc:mysql://127.0.0.1:3306/test_data?useUnicode=true"
                    + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai";

    private static final String MYSQL_USER = "root";
    private static final String MYSQL_PASSWORD = "123456";

    @Bean
    @ConditionalOnMissingBean(DataSourceRegistry.class)
    public DataSourceRegistry inMemoryDataSourceRegistry(
            @Value("${spring.datasource.url:}") String jdbcUrl,
            @Value("${spring.datasource.username:sa}") String username,
            @Value("${spring.datasource.password:}") String password) {

        List<DataSource> sources = new ArrayList<>();

        // Register the application's own database (H2 or MySQL) as a queryable source.
        if (jdbcUrl.startsWith("jdbc:h2") || jdbcUrl.startsWith("jdbc:mysql")) {
            Map<String, String> props = new LinkedHashMap<>();
            props.put("jdbcUrl", jdbcUrl);
            props.put("username", username);
            props.put("password", password);
            sources.add(
                    new DataSource(
                            "app-db",
                            "Application DB",
                            "The application's own database (user accounts, agent state, etc.)",
                            "jdbc",
                            null,
                            List.of("app", "internal"),
                            props));
        }

        // Always register the tenant analytics MySQL source.
        Map<String, String> mysqlProps = new LinkedHashMap<>();
        mysqlProps.put("jdbcUrl", MYSQL_URL);
        mysqlProps.put("username", MYSQL_USER);
        mysqlProps.put("password", MYSQL_PASSWORD);
        sources.add(
                new DataSource(
                        "test-data",
                        "Tenant analytics DB",
                        "MySQL database with tenant_storage_utilization (per-table"
                                + " storage metrics) and project_info (owner names)."
                                + " Join on project_name. Query with SELECT-only SQL.",
                        "jdbc",
                        null,
                        List.of("mysql", "analytics", "tenant"),
                        mysqlProps));

        log.info(
                "DataToolkitConfig: seeded {} data source(s): {}",
                sources.size(),
                sources.stream().map(DataSource::id).toList());
        return new InMemoryDataSourceRegistry(sources);
    }

    @Bean
    @ConditionalOnMissingBean(SqlConnector.class)
    public SqlConnector jdbcSqlConnector() {
        log.info("DataToolkitConfig: no SqlConnector bean found, using JdbcSqlConnector");
        return new JdbcSqlConnector();
    }

    @Bean
    @ConditionalOnMissingBean(ChartRenderer.class)
    public ChartRenderer stubChartRenderer() {
        log.info("DataToolkitConfig: no ChartRenderer bean found, using StubChartRenderer");
        return new StubChartRenderer();
    }
}
