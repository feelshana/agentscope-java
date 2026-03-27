/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.dataanalysis.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Explicit MyBatis-Plus configuration for WebFlux context.
 *
 * <p>Spring Boot WebFlux does not auto-configure a JDBC {@link SqlSessionFactory},
 * so we register it manually here alongside a transaction manager.
 */
@Configuration
@EnableTransactionManagement
public class MybatisConfig {

    /**
     * Run Flyway migrations explicitly (WebFlux does not trigger FlywayAutoConfiguration).
     * Declared as a Bean so Spring manages lifecycle; {@link SqlSessionFactory} depends on it.
     */
    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
    }

    /**
     * Creates the MyBatis-Plus {@link SqlSessionFactory} using the auto-configured DataSource.
     * Depends on {@link Flyway} to ensure tables exist before MyBatis initializes.
     */
    @Bean
    @SuppressWarnings("unused")
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource, Flyway flyway)
            throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        // Use MybatisConfiguration (MyBatis-Plus subclass) for underscore-to-camelCase
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(config);
        return factory.getObject();
    }

    /**
     * JDBC transaction manager for @Transactional support.
     */
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
