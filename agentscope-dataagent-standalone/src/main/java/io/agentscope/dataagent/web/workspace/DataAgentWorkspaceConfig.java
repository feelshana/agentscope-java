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
package io.agentscope.dataagent.web.workspace;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the per-tenant workspace filesystem used by every per-agent
 * {@code WorkspaceManager}.
 *
 * <p>DataAgent is a multi-tenant deployable. Both the browser workspace controllers and the agent
 * runtime read/write through one live Docker {@link
 * io.agentscope.harness.agent.sandbox.Sandbox} per {@code (userId, agentId)} owned by
 * {@link UserSandboxRegistry}. This is what makes the workspace user-isolated — every other route
 * the old {@code CompositeFilesystem} fell through to a shared {@code LocalFilesystem}, which
 * leaked content across tenants.
 *
 * <p>The shared, read-only seed content (AGENTS.md / skills/ / subagents/ / knowledge/) lives
 * under {@code ${cwd}/shared/} on the host and is projected into every fresh container via the
 * registry's {@code __workspace_projection__} entry.
 *
 * <p>Multi-replica deployments must use sticky load-balancing by {@code userId} so a user's
 * traffic lands on the same pod — the registry is in-memory only, and two pods otherwise spin up
 * independent containers for the same user.
 */
@Configuration
public class DataAgentWorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentWorkspaceConfig.class);

    @Value("${dataagent.sandbox.instance-id:standalone}")
    private String instanceId;

    @Value("${dataagent.sandbox.max-containers:8}")
    private int maxContainers;

    @Value("${dataagent.sandbox.memory-mb:1024}")
    private long memoryMb;

    @Value("${dataagent.sandbox.cpus:1}")
    private long cpus;

    @Value("${dataagent.sandbox.workspace-mb:512}")
    private long workspaceMb;

    @Value("${dataagent.sandbox.idle-ttl-min:15}")
    private long idleTtlMinutes;

    @Value("${dataagent.sandbox.eviction-poll-sec:60}")
    private long evictionPollSeconds;

    /**
     * Docker image for every per-user sandbox container. Defaults to the project-built analysis
     * image (see {@code docker/sandbox.Dockerfile}: Python 3 + pandas/matplotlib/scipy + CJK
     * fonts) so the agent's {@code run_python} tool works out of the box. Build it once with
     * {@code docker build -f docker/sandbox.Dockerfile -t agentscope/dataagent-sandbox:latest .}
     * from the module directory, or point this property at any image whose default user is root
     * and which ships the Python analysis stack.
     */
    @Value("${dataagent.sandbox.image:agentscope/dataagent-sandbox:latest}")
    private String sandboxImage;

    /**
     * Same property {@code DataAgentConfig} reads for {@code cwd}. Resolved independently here so
     * {@link #userSandboxRegistry} does not have to inject {@code DataAgentBootstrap} — the
     * bootstrap itself depends on this registry, which would form a cycle.
     */
    @Value("${dataagent.workspace:}")
    private String workspaceDir;

    /**
     * Default {@link SandboxClient} bean — a no-arg {@link DockerSandboxClient}. Operators can
     * override by declaring their own {@code SandboxClient<DockerSandboxClientOptions>} bean.
     */
    @Bean
    @ConditionalOnMissingBean(SandboxClient.class)
    public SandboxClient<DockerSandboxClientOptions> sandboxClient() {
        log.info("Wiring default DockerSandboxClient for per-user workspace sandboxes");
        return new DockerSandboxClient();
    }

    @Bean
    public UserSandboxRegistry userSandboxRegistry(
            SandboxClient<DockerSandboxClientOptions> sandboxClient) {
        Path sharedRoot = resolveCwd().resolve("shared");
        Duration idleTtl = Duration.ofMinutes(idleTtlMinutes);
        Duration evictionPoll = Duration.ofSeconds(evictionPollSeconds);
        if (memoryMb < 128 || cpus < 1 || workspaceMb < 64)
            throw new IllegalArgumentException("Invalid sandbox resource limits");
        DockerSandboxClientOptions optionsTemplate =
                new DockerSandboxClientOptions()
                        .workspaceRoot("/workspace")
                        .memorySizeBytes(Math.multiplyExact(memoryMb, 1024L * 1024L))
                        .cpuCount(cpus)
                        .additionalRunArgs(
                                "--label=dataagent.managed=true",
                                "--label=dataagent.instance=" + instanceId,
                                "--read-only",
                                "--tmpfs=/tmp:rw,nosuid,nodev,size=67108864",
                                "--env=HOME=/tmp",
                                "--env=MPLCONFIGDIR=/tmp/matplotlib",
                                "--pids-limit=256",
                                "--memory-swap=" + Math.multiplyExact(memoryMb, 1024L * 1024L),
                                "--tmpfs=/workspace:rw,nosuid,nodev,size="
                                        + Math.multiplyExact(workspaceMb, 1024L * 1024L));
        if (sandboxImage != null && !sandboxImage.isBlank()) {
            optionsTemplate.image(sandboxImage.trim());
        }
        log.info(
                "DataAgent sandbox registry: hostWorkspaceRoot={}, idleTtl={}, evictionPoll={},"
                        + " image={}",
                sharedRoot,
                idleTtl,
                evictionPoll,
                optionsTemplate.getImage());
        UserSandboxRegistry registry =
                new UserSandboxRegistry(
                        sandboxClient, sharedRoot, idleTtl, evictionPoll, optionsTemplate);
        registry.configureLimits(instanceId, maxContainers);
        return registry;
    }

    private Path resolveCwd() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    @Bean
    public WorkspaceManagerFactory workspaceManagerFactory(UserSandboxRegistry registry) {
        return new WorkspaceManagerFactory(registry);
    }
}
