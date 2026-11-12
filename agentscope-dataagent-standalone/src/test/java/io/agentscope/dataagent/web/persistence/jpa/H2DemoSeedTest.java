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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.dataagent.web.auth.UserStore;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that {@code data-h2.sql} executes on top of a <strong>file-mode</strong> H2 DataSource
 * with explicitly enabled development seeding, including the {@code bob} and {@code alice} accounts
 * with passwords identical to the usernames.
 *
 * <p>The file-mode coverage matters because Spring Boot 4's {@code spring.sql.init.mode=embedded}
 * only classifies {@code jdbc:h2:mem:} as embedded
 * (<a href="https://github.com/spring-projects/spring-boot/issues/32865">spring-boot#32865</a>);
 * an in-memory test alone would not catch the regression where file-mode H2 silently skips the
 * seed. This test also locks in:
 *
 * <ul>
 *   <li>BCrypt hash constants in {@code data-h2.sql} verify against the documented plaintext
 *       passwords ({@code bob} / {@code alice}).
 *   <li>Explicit {@code spring.sql.init.mode=always} makes the seed run for file URLs.
 *   <li>The {@code @PostConstruct} admin seed is order-safe and still inserts {@code admin}
 *       even when the SQL initializer has already populated other rows.
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaPersistenceConfig.class, H2DemoSeedTest.JsonConfig.class})
@ActiveProfiles("integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.defer-datasource-initialization=true",
            "spring.jpa.show-sql=false",
            "dataagent.seed-demo-users=true",
            "spring.sql.init.mode=always",
            "spring.sql.init.platform=h2"
        })
@Tag("integration")
class H2DemoSeedTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {
        @org.springframework.context.annotation.Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }

    @TempDir static Path directory;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void fileDatabase(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:h2:file:"
                                + directory.resolve("db").toString().replace('\\', '/')
                                + ";MODE=MYSQL");
    }

    @Autowired UserStore userStore;

    @Test
    void bobAndAliceSeededWithMatchingPasswords() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:file:");
        }
        UserStore.UserRecord bob =
                userStore
                        .findByUsername("bob")
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "bob was not seeded — check data-h2.sql or"
                                                        + " spring.sql.init wiring"));
        UserStore.UserRecord alice =
                userStore
                        .findByUsername("alice")
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "alice was not seeded — check data-h2.sql"));

        assertThat(bob.roles()).containsExactly("user");
        assertThat(alice.roles()).containsExactly("user");

        assertThat(userStore.verifyPassword(bob, "bob"))
                .as("bob's seeded password hash must verify against the plaintext 'bob'")
                .isTrue();
        assertThat(userStore.verifyPassword(alice, "alice"))
                .as("alice's seeded password hash must verify against the plaintext 'alice'")
                .isTrue();

        assertThat(userStore.verifyPassword(bob, "alice")).isFalse();

        UserStore.UserRecord admin =
                userStore
                        .findById("admin")
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "admin was not seeded —"
                                                    + " JpaUserStore.seedDefaultAdmin must remain"
                                                    + " order-safe with data-h2.sql"));
        assertThat(admin.roles()).contains("admin");
        assertThat(userStore.verifyPassword(admin, "admin")).isTrue();
    }
}
