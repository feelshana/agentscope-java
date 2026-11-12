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
package io.agentscope.dataagent.support;

import io.agentscope.dataagent.web.DataAgentApp;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Boots production wiring with real JPA and files, without borrowing external runtime services. */
@SpringBootTest(classes = DataAgentApp.class)
@Import(IsolatedApplicationTestSupport.ExternalServices.class)
@ActiveProfiles("integration-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
        properties = {
            "spring.config.location=classpath:/application.yml",
            "spring.datasource.url=jdbc:h2:mem:${test.integration.id};MODE=MYSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.open-in-view=false",
            "spring.sql.init.mode=never",
            "dataagent.jwt.secret=test-integration-key-at-least-32-characters-long",
            "dataagent.openai.api-key=",
            "dataagent.dashscope.api-key=",
            "dataagent.demo.enabled=false",
            "dataagent.demo.samples-enabled=false"
        })
public abstract class IsolatedApplicationTestSupport {
    @TempDir protected static Path testRoot;

    @DynamicPropertySource
    static void isolatedPaths(DynamicPropertyRegistry registry) {
        registry.add("test.integration.id", () -> testRoot.getFileName().toString());
        registry.add("test.integration.root", () -> testRoot.toString().replace('\\', '/'));
        registry.add("dataagent.workspace", () -> testRoot.resolve("workspace").toString());
        registry.add("dataagent.wren.mdl-home", () -> testRoot.resolve("mdl").toString());
        registry.add("dataagent.wren.executable", () -> testRoot.resolve("no-wren").toString());
        registry.add("dataagent.wren.skills-dir", () -> testRoot.resolve("no-skills").toString());
        registry.add("user.home", () -> testRoot.resolve("home").toString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ExternalServices {
        @Bean
        @SuppressWarnings("unchecked")
        SandboxClient<DockerSandboxClientOptions> sandboxClient() {
            // Keep the real registry and bootstrap, but never run Docker orphan cleanup.
            return Mockito.mock(SandboxClient.class);
        }
    }
}
