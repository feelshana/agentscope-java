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
package io.agentscope.dataagent.runtime.session.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.runtime.session.CommandLane;
import io.agentscope.dataagent.runtime.session.HistoryResult;
import io.agentscope.dataagent.runtime.session.PendingCompletion;
import io.agentscope.dataagent.runtime.session.SendResult;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionConstants;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionView;
import io.agentscope.dataagent.runtime.session.SpawnResult;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.tool.TaskTool;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool facade exposing managed subagent session operations to the agent.
 *
 * <p>Mirrors the OpenClaw sessions tool set:
 *
 * <ul>
 *   <li>{@code sessions_spawn} — spawn an isolated subagent session
 *   <li>{@code sessions_send} — send a message to an existing session by key or label
 *   <li>{@code sessions_list} — list managed sessions with optional filters
 *   <li>{@code sessions_history} — read a session's conversation transcript
 * </ul>
 *
 * <p>Async operations ({@code timeout_seconds=0}) submit work to {@link TaskRepository} and return
 * a {@code task_id} for retrieval via the companion {@link TaskTool}. The same completion may
 * also be queued for the requester as a structured {@link PendingCompletion} (poll with {@code
 * sessions_pending_completions}).
 *
 * <p>Uses {@link SessionAgentManager} for full session lifecycle management.
 */
public class SessionsTool {

    private static final Logger log = LoggerFactory.getLogger(SessionsTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 600;

    private static final String BG_RESULT_TEMPLATE =
            """
            status: accepted
            task_id: %s
            Use task_output with task_id='%s' to retrieve the result.\
            """;

    private final SessionAgentManager sessionAgentManager;
    private final TaskRepository taskRepository;
    private final String parentSessionKey;
    private final int parentSpawnDepth;

    /**
     * @param sessionAgentManager full session lifecycle manager
     * @param taskRepository background task store (for async fire-and-forget)
     * @param parentSessionKey session key of the agent that owns this tool instance (null = main)
     * @param parentSpawnDepth spawn depth of the owning agent (0 = main agent)
     */
    public SessionsTool(
            SessionAgentManager sessionAgentManager,
            TaskRepository taskRepository,
            String parentSessionKey,
            int parentSpawnDepth) {
        this.sessionAgentManager =
                Objects.requireNonNull(sessionAgentManager, "sessionAgentManager");
        this.taskRepository = taskRepository;
        this.parentSessionKey = parentSessionKey;
        this.parentSpawnDepth = parentSpawnDepth;
    }

    // -----------------------------------------------------------------
    //  sessions_spawn
    // -----------------------------------------------------------------

    @Tool(
            name = "sessions_spawn",
            description =
                    """
                    创建一个隔离的子代理会话，用于委派或后台任务。\
                    mode="run"（默认）立即执行任务并返回结果。\
                    mode="session" 注册一个持久会话，后续可通过 sessions_send 继续发送消息。\
                    timeout_seconds=0 表示即发即忘——返回 task_id 供 task_output 查询，\
                    并将完成记录放入 sessions_pending_completions 队列。\
                    返回 run_id 和 session_key 供后续 sessions_send 使用。\
                    """)
    public String sessionsSpawn(
            RuntimeContext runtimeContext,
            @ToolParam(name = "agent_id", description = "要实例化的子代理标识符") String agentId,
            @ToolParam(
                            name = "task",
                            description =
                                    """
                                    发送给子代理的初始任务或提示词。省略则仅创建会话而不执行任务。\
                                    """,
                            required = false)
                    String task,
            @ToolParam(
                            name = "label",
                            description =
                                    """
                                    可选的人类可读标签，用于通过 sessions_send 或 sessions_list 引用此会话\
                                    """,
                            required = false)
                    String label,
            @ToolParam(
                            name = "mode",
                            description =
                                    """
                                    "run"（一次性执行，默认）或 "session"（持久会话，注册后通过 sessions_send 发送消息）\
                                    """,
                            required = false)
                    String mode,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    等待任务结果的最大秒数。0=即发即忘，返回 task_id。\
                                    默认: 30。最大: 600。\
                                    """,
                            required = false)
                    Integer timeoutSeconds) {

        String canonLabel = label != null && !label.isBlank() ? label.trim() : null;
        boolean sessionMode = "session".equalsIgnoreCase(mode);
        boolean hasTask = task != null && !task.isBlank();

        if (sessionMode && canonLabel != null) {
            var existingKey = sessionAgentManager.resolveSessionKey(canonLabel);
            if (existingKey.isPresent()) {
                var existingEntry = sessionAgentManager.getSession(existingKey.get());
                if (existingEntry.isPresent()) {
                    SessionEntry e = existingEntry.get();
                    String resumeInfo = formatExistingSessionInfo(e);
                    if (!hasTask) {
                        return resumeInfo + "\nstatus: resumed";
                    }
                    long tMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
                    if (tMs == 0) {
                        String taskId = "task_" + UUID.randomUUID();
                        final String capturedTask = task;
                        taskRepository.putTask(
                                runtimeContext,
                                taskId,
                                e.agentId(),
                                parentSessionScope(),
                                new TaskRunSpec.LocalTaskRunSpec(
                                        () -> {
                                            SendResult r =
                                                    sessionAgentManager.execute(
                                                            e.sessionKey(),
                                                            capturedTask,
                                                            0,
                                                            true,
                                                            CommandLane.SUBAGENT);
                                            return "ok".equals(r.status())
                                                    ? r.reply()
                                                    : "Error: " + r.error();
                                        }));
                        return resumeInfo
                                + "\n"
                                + String.format(BG_RESULT_TEMPLATE, taskId, taskId);
                    }
                    SendResult result =
                            sessionAgentManager.execute(
                                    e.sessionKey(), task, tMs, false, CommandLane.SUBAGENT);
                    if ("error".equals(result.status())) {
                        return resumeInfo + "\nstatus: error\nerror: " + result.error();
                    }
                    return resumeInfo + "\nstatus: ok\nreply:\n" + result.reply();
                }
            }
        }

        SpawnResult reg =
                sessionAgentManager.registerSession(
                        agentId, canonLabel, parentSessionKey, parentSpawnDepth);

        if ("error".equals(reg.status())) {
            return "错误: " + reg.error();
        }

        String spawnedInfo = formatSpawnInfo(reg);

        if (sessionMode || !hasTask) {
            return spawnedInfo + "\nstatus: accepted";
        }

        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            final String spawnedSessionKey = reg.sessionKey();
            taskRepository.putTask(
                    runtimeContext,
                    taskId,
                    agentId,
                    parentSessionScope(),
                    new TaskRunSpec.LocalTaskRunSpec(
                            () -> {
                                SendResult r =
                                        sessionAgentManager.execute(
                                                spawnedSessionKey,
                                                capturedTask,
                                                0,
                                                true,
                                                CommandLane.SUBAGENT);
                                return "ok".equals(r.status()) ? r.reply() : "Error: " + r.error();
                            }));
            return spawnedInfo + "\n" + String.format(BG_RESULT_TEMPLATE, taskId, taskId);
        }

        SendResult result =
                sessionAgentManager.execute(
                        reg.sessionKey(), task, timeoutMs, false, CommandLane.SUBAGENT);
        if ("error".equals(result.status())) {
            return spawnedInfo + "\nstatus: error\nerror: " + result.error();
        }
        return spawnedInfo + "\nstatus: ok\nreply:\n" + result.reply();
    }

    // -----------------------------------------------------------------
    //  sessions_send
    // -----------------------------------------------------------------

    @Tool(
            name = "sessions_send",
            description =
                    """
                    向已存在的管理会话发送消息（通过 session_key 或 label 定位）。\
                    timeout_seconds=0 表示即发即忘——返回 task_id 供 task_output 查询。\
                    """)
    public String sessionsSend(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "session_key",
                            description =
                                    """
                                    sessions_spawn 返回的 AgentStateStore 键。与 label 互斥。\
                                    """,
                            required = false)
                    String sessionKey,
            @ToolParam(
                            name = "label",
                            description =
                                    """
                                    创建会话时分配的标签。与 session_key 互斥。\
                                    """,
                            required = false)
                    String label,
            @ToolParam(name = "message", description = "发送给会话的消息或后续任务") String message,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    等待回复的最大秒数。0=即发即忘，返回 task_id。\
                                    默认: 30。最大: 600。\
                                    """,
                            required = false)
                    Integer timeoutSeconds) {

        boolean hasKey = sessionKey != null && !sessionKey.isBlank();
        boolean hasLabel = label != null && !label.isBlank();
        if (hasKey && hasLabel) {
            return "错误: 请提供 session_key 或 label，但不能同时提供两者。";
        }
        if (!hasKey && !hasLabel) {
            return "错误: 必须提供 session_key 或 label。";
        }
        if (message == null || message.isBlank()) {
            return "错误: message 不能为空";
        }

        String target = hasKey ? sessionKey.trim() : label.trim();
        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            String resolvedAgentId =
                    sessionAgentManager
                            .getSession(
                                    sessionAgentManager.resolveSessionKey(target).orElse(target))
                            .map(e -> e.agentId())
                            .orElse("unknown");
            final String capturedTarget = target;
            taskRepository.putTask(
                    runtimeContext,
                    taskId,
                    resolvedAgentId,
                    parentSessionScope(),
                    new TaskRunSpec.LocalTaskRunSpec(
                            () -> {
                                SendResult r =
                                        sessionAgentManager.execute(
                                                capturedTarget,
                                                message,
                                                0,
                                                true,
                                                CommandLane.SUBAGENT);
                                return "ok".equals(r.status()) ? r.reply() : "Error: " + r.error();
                            }));
            return String.format(BG_RESULT_TEMPLATE, taskId, taskId);
        }

        SendResult result =
                sessionAgentManager.execute(
                        target, message, timeoutMs, false, CommandLane.SUBAGENT);
        return switch (result.status()) {
            case "ok" ->
                    "session_key: "
                            + result.sessionKey()
                            + "\nstatus: ok\nreply:\n"
                            + result.reply();
            case "error" -> "Error: " + result.error();
            default -> "status: " + result.status();
        };
    }

    // -----------------------------------------------------------------
    //  sessions_list
    // -----------------------------------------------------------------

    @Tool(
            name = "sessions_list",
            description =
                    """
                    列出受管理的子代理会话，支持可选过滤。返回每个会话的 session_key、\
                    agent_id、label、kind、spawn_depth、session_file_path 和 last_activity_ms。\
                    """)
    public String sessionsList(
            @ToolParam(
                            name = "kinds",
                            description =
                                    """
                                    逗号分隔的会话类型过滤（subagent, main）。默认: 全部。\
                                    """,
                            required = false)
                    String kinds,
            @ToolParam(name = "limit", description = "最大返回会话数", required = false) Integer limit,
            @ToolParam(name = "active_minutes", description = "仅包含最近 N 分钟内活跃的会话", required = false)
                    Integer activeMinutes) {

        Set<String> kindFilter = parseKinds(kinds);
        int effectiveLimit = limit != null && limit > 0 ? limit : 0;
        int effectiveActive = activeMinutes != null && activeMinutes > 0 ? activeMinutes : 0;

        List<SessionView> sessions =
                sessionAgentManager.list(kindFilter, effectiveLimit, effectiveActive);

        if (sessions.isEmpty()) {
            return "没有受管理的会话。";
        }

        StringBuilder sb = new StringBuilder("受管理的会话（").append(sessions.size()).append("）：\n");
        for (SessionView v : sessions) {
            sb.append("- session_key: ").append(v.sessionKey()).append("\n");
            sb.append("  agent_id: ").append(v.agentId()).append("\n");
            if (v.label() != null) {
                sb.append("  label: ").append(v.label()).append("\n");
            }
            sb.append("  kind: ").append(v.kind()).append("\n");
            sb.append("  spawn_depth: ").append(v.spawnDepth()).append("\n");
            if (v.spawnedBy() != null) {
                sb.append("  spawned_by: ").append(v.spawnedBy()).append("\n");
            }
            if (v.spawnRunId() != null) {
                sb.append("  run_id: ").append(v.spawnRunId()).append("\n");
            }
            List<String> ch = sessionAgentManager.listChildren(v.sessionKey());
            if (!ch.isEmpty()) {
                sb.append("  child_session_keys: ").append(String.join(", ", ch)).append("\n");
            }
            sb.append("  session_file_path: ").append(v.sessionFilePath()).append("\n");
            sb.append("  last_activity_ms: ").append(v.lastActivityMs()).append("\n");
        }
        return sb.toString().trim();
    }

    // -----------------------------------------------------------------
    //  sessions_history
    // -----------------------------------------------------------------

    @Tool(
            name = "sessions_history",
            description =
                    """
                    读取管理会话的对话记录。对话记录在消息从内存卸载时写入。\
                    返回 session_file_path 和记录内容。\
                    """)
    public String sessionsHistory(
            @ToolParam(name = "session_key", description = "目标会话的 AgentStateStore 键或标签")
                    String sessionKey,
            @ToolParam(name = "limit", description = "从末尾返回的最大行数（0 = 全部）", required = false)
                    Integer limit) {

        if (sessionKey == null || sessionKey.isBlank()) {
            return "错误: session_key 不能为空";
        }

        int effectiveLimit = limit != null && limit > 0 ? limit : 0;
        HistoryResult result = sessionAgentManager.history(sessionKey.trim(), effectiveLimit);

        if (result.error() != null) {
            return "错误: " + result.error();
        }
        return "session_key: "
                + result.sessionKey()
                + "\nsession_file_path: "
                + result.sessionFilePath()
                + "\n\n"
                + result.content();
    }

    // -----------------------------------------------------------------
    //  sessions_pending_completions
    // -----------------------------------------------------------------

    @Tool(
            name = "sessions_pending_completions",
            description =
                    """
                    排空当前请求会话的子代理完成事件队列（announce 队列）。\
                    requester_session_key 应匹配您创建子代理时的 session_key，\
                    或省略以使用顶层主会话。每条记录包含 announce_text，\
                    可合并到您的下一次回复中。\
                    """)
    public String sessionsPendingCompletions(
            @ToolParam(
                            name = "requester_session_key",
                            description =
                                    """
                                    创建子代理的请求者的 AgentStateStore 键。省略则使用默认根请求者（主会话）。\
                                    """,
                            required = false)
                    String requesterSessionKey,
            @ToolParam(name = "limit", description = "最大排空事件数（默认 10）", required = false)
                    Integer limit) {

        String rk =
                requesterSessionKey != null && !requesterSessionKey.isBlank()
                        ? requesterSessionKey.trim()
                        : SessionConstants.resolveRequesterKey(parentSessionKey);
        int lim = limit != null && limit > 0 ? limit : 10;
        List<PendingCompletion> pending = sessionAgentManager.drainPendingCompletions(rk, lim);
        if (pending.isEmpty()) {
            return "requester_session_key=" + rk + " 没有待处理的完成事件。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("待处理完成事件（").append(pending.size()).append("）来自 ").append(rk).append(":\n\n");
        for (PendingCompletion p : pending) {
            sb.append("---\n");
            sb.append("run_id: ").append(p.runId()).append("\n");
            sb.append("child_session_key: ").append(p.childSessionKey()).append("\n");
            sb.append("status: ").append(p.status()).append("\n");
            if (p.error() != null) {
                sb.append("error: ").append(p.error()).append("\n");
            }
            sb.append("\n").append(p.announceText()).append("\n");
        }
        return sb.toString().trim();
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private String parentSessionScope() {
        if (parentSessionKey == null || parentSessionKey.isBlank()) {
            return null;
        }
        return sessionAgentManager
                .getSession(parentSessionKey)
                .map(e -> e.sessionId())
                .orElse(parentSessionKey);
    }

    private static long resolveTimeoutMs(Integer timeoutSeconds, int defaultSeconds) {
        if (timeoutSeconds == null) {
            return (long) defaultSeconds * 1_000;
        }
        if (timeoutSeconds <= 0) {
            return 0L;
        }
        return (long) Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS) * 1_000;
    }

    private static String formatSpawnInfo(SpawnResult reg) {
        StringBuilder sb = new StringBuilder();
        sb.append("run_id: ").append(reg.runId()).append("\n");
        sb.append("session_key: ").append(reg.sessionKey()).append("\n");
        sb.append("agent_id: ").append(reg.agentId()).append("\n");
        sb.append("session_id: ").append(reg.sessionId()).append("\n");
        sb.append("session_file_path: ").append(reg.sessionFilePath());
        return sb.toString();
    }

    private static String formatExistingSessionInfo(SessionEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("session_key: ").append(entry.sessionKey()).append("\n");
        sb.append("agent_id: ").append(entry.agentId()).append("\n");
        sb.append("session_id: ").append(entry.sessionId()).append("\n");
        sb.append("label: ").append(entry.label()).append("\n");
        sb.append("session_file_path: ").append(entry.sessionFilePath()).append("\n");
        sb.append("note: existing session reused (label match)");
        return sb.toString();
    }

    private static Set<String> parseKinds(String kinds) {
        if (kinds == null || kinds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(kinds.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
