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
 * <p>On the default embedded-H2 deployment the registry is seeded with a {@code demo-db} source
 * pointing at the application's own database, where {@code data-h2.sql} maintains the {@code
 * demo_orders} demo table — so the full list → describe → query → chart flow works out of the
 * box. External RDBMS deployments ({@code jdbc} profile) start with an empty registry: operators
 * seed their own sources via a custom bean.
 *
 * <p>The actual registration of the toolkit onto the main agent's toolkit lives in
 * {@link DataToolkitRegistrar} so the {@code @PostConstruct} cannot tangle with self-injection
 * of the {@code @Bean} methods defined here.
 */
@Configuration
public class DataToolkitConfig {

    private static final Logger log = LoggerFactory.getLogger(DataToolkitConfig.class);

    @Bean
    @ConditionalOnMissingBean(DataSourceRegistry.class)
    public DataSourceRegistry inMemoryDataSourceRegistry(
            @Value("${spring.datasource.url:}") String jdbcUrl,
            @Value("${spring.datasource.username:sa}") String username,
            @Value("${spring.datasource.password:}") String password) {
        if (jdbcUrl.startsWith("jdbc:h2")) {
            Map<String, String> props = new LinkedHashMap<>();
            props.put("jdbcUrl", jdbcUrl);
            props.put("username", username);
            props.put("password", password);
            DataSource demo =
                    new DataSource(
                            "demo-db",
                            "Demo analytics DB",
                            "Embedded demo database with the demo_orders fact table "
                                    + "(2025 orders: date, region, category, channel, quantity, "
                                    + "amount, status). Query with SELECT-only SQL.",
                            "jdbc",
                            null,
                            List.of("demo", "h2"),
                            props);
            log.info(
                    "DataToolkitConfig: seeded demo-db data source pointing at the embedded H2"
                            + " database");
            return new InMemoryDataSourceRegistry(List.of(demo));
        }
        log.info(
                "DataToolkitConfig: no DataSourceRegistry bean found and the configured database"
                        + " is not embedded H2, using empty InMemoryDataSourceRegistry");
        return new InMemoryDataSourceRegistry(List.of());
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
