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

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.support.IsolatedApplicationTestSupport;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore.StoredEntry;
import io.agentscope.dataagent.web.share.AgentShareGrant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the full Spring Boot context (the JPA-backed persistence path is the only backend the
 * DataAgent ships with) on top of an in-memory H2 database to validate user / agent persistence
 * end-to-end. Confirms that:
 *
 * <ul>
 *   <li>{@link JpaPersistenceConfig} is active by default and wires both stores;
 *   <li>{@link JpaUserStore} supports explicit development seeding and full CRUD;
 *   <li>{@link JpaUserAgentDefinitionStore} round-trips agent definitions including share
 *       grants;
 *   <li>The {@link UserStore} / {@link UserAgentDefinitionStore} beans wired into the rest of the
 *       app are the JPA implementations.
 * </ul>
 */
@TestPropertySource(properties = "dataagent.seed-demo-users=true")
@Tag("integration")
class JpaPersistenceIntegrationTest extends IsolatedApplicationTestSupport {

    @Autowired UserStore userStore;
    @Autowired UserAgentDefinitionStore agentStore;
    @Autowired DataAgentBootstrap bootstrap;

    @Test
    void jpaBeansAreWiredByDefault() {
        assertThat(userStore).isInstanceOf(JpaUserStore.class);
        assertThat(agentStore).isInstanceOf(JpaUserAgentDefinitionStore.class);
        assertThat(bootstrap).isNotNull();
    }

    @Test
    void explicitDevelopmentSeedCreatesAdminOnEmptyDatabase() {
        Optional<UserStore.UserRecord> admin = userStore.findById("admin");
        assertThat(admin).isPresent();
        assertThat(admin.get().roles()).contains("admin");
        assertThat(userStore.verifyPassword(admin.get(), "admin")).isTrue();
    }

    @Test
    void userCrudRoundTripsThroughJpa() {
        userStore.createUser("alice-1", "alice", "secret123", List.of("user"));
        UserStore.UserRecord byUsername = userStore.findByUsername("alice").orElseThrow();
        assertThat(byUsername.userId()).isEqualTo("alice-1");

        userStore.updatePassword("alice-1", "new-secret");
        UserStore.UserRecord reloaded = userStore.findById("alice-1").orElseThrow();
        assertThat(userStore.verifyPassword(reloaded, "new-secret")).isTrue();

        userStore.updateRoles("alice-1", List.of("user", "admin"));
        assertThat(userStore.findById("alice-1").orElseThrow().roles())
                .containsExactly("user", "admin");

        assertThat(userStore.deleteUser("alice-1")).isTrue();
        assertThat(userStore.findById("alice-1")).isEmpty();
    }

    @Test
    void agentDefinitionRoundTripsAndCarriesWorkspacePath() {
        userStore.createUser("bob-1", "bob", "secret123", List.of("user"));

        StoredEntry entry =
                new StoredEntry(
                        "demo",
                        "Demo Agent",
                        "demo description",
                        "system prompt body",
                        "qwen-max",
                        12,
                        List.of("read_file", "list_files"),
                        List.of("execute"),
                        "Demo",
                        ":rocket:",
                        null,
                        Boolean.FALSE,
                        List.of("foo-skill"),
                        null,
                        100L,
                        200L,
                        List.of(
                                new AgentShareGrant(
                                        AgentShareGrant.GRANTEE_USER,
                                        "bob-1",
                                        AgentShareGrant.TIER_RUN,
                                        100L,
                                        "bob-1")),
                        "INVOKER",
                        null,
                        "/tmp/agentscope-test/demo",
                        null,
                        null,
                        null);

        StoredEntry saved = agentStore.save("bob-1", entry);
        assertThat(saved.id()).isEqualTo("demo");

        StoredEntry reloaded = agentStore.findById("bob-1", "demo").orElseThrow();
        assertThat(reloaded.name()).isEqualTo("Demo Agent");
        assertThat(reloaded.toolsAllow()).containsExactly("read_file", "list_files");
        assertThat(reloaded.toolsDeny()).containsExactly("execute");
        assertThat(reloaded.skillsAllow()).containsExactly("foo-skill");
        assertThat(reloaded.shares()).hasSize(1);
        assertThat(reloaded.shares().get(0).granteeId()).isEqualTo("bob-1");
        assertThat(reloaded.workspacePath()).isEqualTo("/tmp/agentscope-test/demo");

        List<StoredEntry> all = agentStore.list("bob-1");
        assertThat(all).extracting(StoredEntry::id).containsExactly("demo");

        assertThat(agentStore.delete("bob-1", "demo")).isTrue();
        assertThat(agentStore.findById("bob-1", "demo")).isEmpty();
    }
}
