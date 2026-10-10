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
package io.agentscope.dataagent.runtime.wren;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.WrenProfileHome;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-knowledge-base {@code wren serve mcp} instance pool (specs/010 M3, ADR 0018 D8).
 *
 * <p>Each published group gets one long-lived stdio MCP subprocess serving its snapshot at
 * {@code <mdlRoot>/<groupId>/published} (the MCP server resolves the manifest from {@code
 * <project>/target/mdl.json}, which {@code wren context build} wrote before the snapshot copy).
 * Instances spawn on demand, are reaped after {@code dataagent.wren.instance-idle-seconds} of
 * inactivity and are rebuilt after a publish ({@link #invalidate}) — the ADR-verified reason is
 * that the engine freezes the manifest at startup, so hot-reloading the new MDL is not an option.
 *
 * <p>The subprocess runs with a platform-owned {@code WREN_HOME} ({@link WrenProfileHome}) so the
 * connection profile always points at the dataset store, and with UTF-8 mode forced — both are
 * probe-verified requirements of the wren CLI on Windows.
 *
 * <p>MCP request failures never kill the engine: {@code ok=false} results carry the server-side
 * diagnostics back to the model for self-correction, while transport-level failures drop the
 * instance so the next call respawns it.
 *
 * <p>Calls on one group's instance are serialized (ADR 0023): the MCP stdio transport
 * underneath is not thread-safe — {@code StdioClientTransport#sendMessage} is a Reactor
 * {@code Sinks#tryEmitNext}, which returns {@code FAIL_NON_SERIALIZED} under concurrent
 * emission — so parallel tool calls on the same instance produced the paired "Failed to
 * enqueue message" + "MCP session with server terminated" failures (2026-09-29 LLM.log,
 * deterministic). Distinct groups use separate gates and stay parallel.
 */
@Component
public class WrenInstanceRegistry implements WrenQueryGateway {

    private static final Logger log = LoggerFactory.getLogger(WrenInstanceRegistry.class);

    /** MCP initialization timeout handed to the SDK (cold start measured at 2-4s). */
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(60);

    /** Upper bound for the blocking initialize call; guards against a wedged subprocess. */
    private static final Duration INIT_BLOCK = Duration.ofSeconds(90);

    /** Extra slack over the per-request timeout before the blocking call gives up. */
    private static final Duration CALL_SLACK = Duration.ofSeconds(30);

    private static final long SWEEP_INTERVAL_SECONDS = 60;

    private final WrenProperties props;
    private final WrenProfileHome profileHome;

    private final Map<String, Instance> instances = new ConcurrentHashMap<>();
    private final Map<String, Object> spawnLocks = new ConcurrentHashMap<>();

    /**
     * Per-group call gates (ADR 0023). Entries are never removed: one monitor object per
     * knowledge base is negligible next to the subprocess it guards.
     */
    private final Map<String, Object> callGates = new ConcurrentHashMap<>();

    private final ScheduledExecutorService reaper =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "wren-instance-reaper");
                        t.setDaemon(true);
                        return t;
                    });

    public WrenInstanceRegistry(WrenProperties props, WrenProfileHome profileHome) {
        this.props = props;
        this.profileHome = profileHome;
    }

    /** A live subprocess bound to one group's published snapshot. */
    private static final class Instance {
        private final Path projectDir;
        private final McpClientWrapper client;
        private final AtomicLong lastUsed = new AtomicLong(System.currentTimeMillis());

        Instance(Path projectDir, McpClientWrapper client) {
            this.projectDir = projectDir;
            this.client = client;
        }

        void touch() {
            lastUsed.set(System.currentTimeMillis());
        }
    }

    @PostConstruct
    void startReaper() {
        reaper.scheduleWithFixedDelay(
                this::sweepIdle, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void close() {
        reaper.shutdownNow();
        for (Map.Entry<String, Instance> e : instances.entrySet()) {
            if (instances.remove(e.getKey(), e.getValue())) {
                closeQuietly(e.getKey(), e.getValue());
            }
        }
    }

    @Override
    public WrenCallResult call(String groupId, String tool, Map<String, Object> arguments) {
        // Serialize per group: the sibling tool call racing on this instance would otherwise hit
        // Sinks FAIL_NON_SERIALIZED and its invalidate() would kill our in-flight request
        // (both errors observed paired in the 2026-09-29 log). Lock ordering is one-way
        // (callGate -> spawnLock inside acquire), so no deadlock is possible.
        Object gate = callGates.computeIfAbsent(groupId, k -> new Object());
        synchronized (gate) {
            return doCall(groupId, tool, arguments);
        }
    }

    private WrenCallResult doCall(String groupId, String tool, Map<String, Object> arguments) {
        Instance inst = acquire(groupId);
        inst.touch();
        McpSchema.CallToolResult result;
        try {
            result = inst.client.callTool(tool, arguments).block(props.timeout().plus(CALL_SLACK));
        } catch (RuntimeException e) {
            // Transport/protocol failure (or request timeout): the instance may be wedged, so it
            // is dropped and the next call respawns it. Query-level errors come back as ok=false
            // results instead and deliberately keep the instance alive.
            invalidate(groupId);
            throw new DatasetException("wren 语义引擎调用失败（实例已重置，重试将自动重建）：" + rootMessage(e), 503);
        }
        if (result == null) {
            invalidate(groupId);
            throw new DatasetException("wren 语义引擎无响应（" + tool + "），实例已重置", 503);
        }
        inst.touch();
        String text = extractText(result);
        boolean ok = !Boolean.TRUE.equals(result.isError());
        if (!ok) {
            log.warn("wren {} rejected on group {}: {}", tool, groupId, firstLine(text));
        }
        return new WrenCallResult(ok, text);
    }

    @Override
    public void invalidate(String groupId) {
        Instance inst = instances.remove(groupId);
        if (inst != null) {
            closeQuietly(groupId, inst);
            log.info(
                    "wren instance for group {} closed; the next query respawns it against the"
                            + " current published snapshot",
                    groupId);
        }
    }

    // ------------------------------------------------------------------ instance lifecycle

    @Override
    public void closeForDeletion(String groupId) {
        Object gate = callGates.computeIfAbsent(groupId, ignored -> new Object());
        synchronized (gate) {
            Instance instance = instances.get(groupId);
            if (instance == null) return;
            // Retain the instance on failure so a retry can close it again.
            instance.client.close();
            instances.remove(groupId, instance);
            log.info("wren instance closed for deleted group {}", groupId);
        }
    }

    private Instance acquire(String groupId) {
        Instance inst = instances.get(groupId);
        if (inst != null) {
            return inst;
        }
        Object lock = spawnLocks.computeIfAbsent(groupId, k -> new Object());
        synchronized (lock) {
            inst = instances.get(groupId);
            if (inst != null) {
                return inst;
            }
            Instance created = spawn(groupId);
            instances.put(groupId, created);
            return created;
        }
    }

    private Instance spawn(String groupId) {
        Path project = props.groupRoot(groupId).resolve("published");
        if (!Files.isRegularFile(project.resolve("target").resolve("mdl.json"))) {
            throw new DatasetException("知识库的语义模型产物不存在（缺少已发布的 mdl.json）。请在「语义建模」页重新发布后再查询。", 409);
        }
        return spawnProject(groupId, project, resolveSnapshotProfile(project, props.profile()));
    }

    @Override
    public WrenCallResult callDraft(
            String groupId, Path project, String profile, String sql, int limit) {
        Path allowed = props.groupRoot(groupId).resolve("scratch").toAbsolutePath().normalize();
        if (!allowed.equals(project.toAbsolutePath().normalize())) {
            throw new DatasetException("草稿验证工程路径无效", 400);
        }
        Instance draft = spawnProject(groupId + "-validation", project, profile);
        try {
            McpSchema.CallToolResult result =
                    draft.client
                            .callTool("run_sql", Map.of("sql", sql, "limit", limit))
                            .block(props.timeout().plus(CALL_SLACK));
            if (result == null) throw new DatasetException("草稿查询无响应", 503);
            return new WrenCallResult(!Boolean.TRUE.equals(result.isError()), extractText(result));
        } finally {
            closeQuietly(groupId + "-validation", draft);
        }
    }

    private Instance spawnProject(String groupId, Path project, String profile) {
        Path home = profileHome.ensure();
        // specs/010 M4: the snapshot pins its connection (ext-<id> for external-source groups);
        // a missing marker falls back to the default profile so pre-M4 snapshots keep working.
        List<String> args =
                List.of(
                        "serve",
                        "mcp",
                        "--project",
                        project.toAbsolutePath().toString(),
                        "--profile",
                        profile,
                        "--quiet");
        // Merged onto the inherited environment by the MCP stdio transport; UTF-8 mode is
        // mandatory on Windows (see the wren subprocess integration notes).
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("WREN_HOME", home.toAbsolutePath().toString());

        McpClientWrapper client =
                McpClientBuilder.create("wren-" + groupId)
                        .stdioTransport(props.resolveExecutable(), args, env)
                        .timeout(props.timeout())
                        .initializationTimeout(INIT_TIMEOUT)
                        .buildSync();
        try {
            client.initialize().block(INIT_BLOCK);
        } catch (RuntimeException e) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // best-effort cleanup of the failed client
            }
            throw new DatasetException(
                    WrenProperties.unavailableMessage(props.executable(), rootMessage(e)), 503);
        }
        log.info("wren instance started for group {} (project={})", groupId, project);
        return new Instance(project, client);
    }

    private void sweepIdle() {
        long cutoff = System.currentTimeMillis() - props.instanceIdle().toMillis();
        for (Map.Entry<String, Instance> e : instances.entrySet()) {
            Instance inst = e.getValue();
            if (inst.lastUsed.get() >= cutoff) {
                continue;
            }
            if (instances.remove(e.getKey(), inst)) {
                closeQuietly(e.getKey(), inst);
                log.info(
                        "wren instance for group {} reaped after {}s idle",
                        e.getKey(),
                        props.instanceIdle().toSeconds());
            }
        }
    }

    private void closeQuietly(String groupId, Instance inst) {
        try {
            inst.client.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close wren instance for group {}: {}", groupId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Reads the profile pinned by the publish flow from {@code
     * <published>/wren-source.properties} ({@code profile=<name>}); falls back to the default
     * dataset-store profile when the marker is absent (pre-M4 snapshot) or unreadable.
     */
    static String resolveSnapshotProfile(Path project, String fallback) {
        Path marker = project.resolve("wren-source.properties");
        if (Files.isRegularFile(marker)) {
            try {
                for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                    String t = line.strip();
                    if (t.startsWith("profile=") && !t.substring("profile=".length()).isBlank()) {
                        return t.substring("profile=".length()).strip();
                    }
                }
            } catch (IOException e) {
                log.warn(
                        "WrenInstanceRegistry: could not read {} ({}); using default profile",
                        marker,
                        e.getMessage());
            }
        }
        return fallback;
    }

    private static String extractText(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int nl = text.indexOf('\n');
        String line = nl >= 0 ? text.substring(0, nl) : text;
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }
}
