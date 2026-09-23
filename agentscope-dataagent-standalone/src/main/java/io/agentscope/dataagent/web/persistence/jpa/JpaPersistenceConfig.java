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
package io.agentscope.dataagent.web.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.binding.UserBindingStore;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Wires the JPA-backed {@link UserStore} and {@link UserAgentDefinitionStore}. This is the only
 * persistence backend the data-agent ships with.
 *
 * <h2>Default DataSource</h2>
 *
 * <p>The default {@code application.yml} points {@code spring.datasource.url} at a local MySQL
 * instance ({@code localhost:3306/dataagent_platform}, user {@code root}). This matches the dataset
 * store convention so a single MySQL server serves both platform metadata and uploaded datasets.
 *
 * <h2>Falling back to H2</h2>
 *
 * <p>Activate the bundled {@code h2} Spring profile to switch to an embedded file-based H2
 * database (useful when MySQL is not available):
 *
 * <pre>{@code
 * --spring.profiles.active=h2
 *
 * # Or override individual settings without the profile:
 * DATAAGENT_DB_URL=jdbc:postgresql://host:5432/dataagent_platform
 * DATAAGENT_DB_DRIVER=org.postgresql.Driver
 * DATAAGENT_DB_USER=...
 * DATAAGENT_DB_PASSWORD=...
 * DATAAGENT_JPA_DDL_AUTO=validate          # once Flyway / Liquibase manage the schema
 * }</pre>
 *
 * <p>The MySQL ({@code com.mysql:mysql-connector-j}), PostgreSQL ({@code org.postgresql:postgresql}),
 * and H2 JDBC drivers are bundled at runtime scope; the active Hibernate dialect is resolved from
 * the {@code spring.datasource.url}.
 */
@Configuration
@EnableJpaRepositories(basePackageClasses = JpaPersistenceConfig.class)
@EntityScan(basePackageClasses = JpaPersistenceConfig.class)
@EnableTransactionManagement
public class JpaPersistenceConfig {

    private static final Logger log = LoggerFactory.getLogger(JpaPersistenceConfig.class);

    @Bean
    public UserStore jpaUserStore(UserEntityRepository repository) {
        log.info("Persistence: user store backed by JPA");
        return new JpaUserStore(repository);
    }

    @Bean
    public UserBindingStore jpaUserBindingStore(
            UserEntityRepository repository, ObjectMapper objectMapper) {
        log.info("Persistence: user binding store backed by JPA");
        return new JpaUserBindingStore(repository, objectMapper);
    }

    @Bean
    public UserAgentDefinitionStore jpaUserAgentDefinitionStore(AgentEntityRepository repository) {
        log.info("Persistence: agent definition store backed by JPA");
        return new JpaUserAgentDefinitionStore(repository);
    }
}
