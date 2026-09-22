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
package io.agentscope.dataagent.web.marketplace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.web.persistence.jpa.ContributionEntity;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agent-facing tool for nominating workspace files for promotion to the shared workspace. The
 * tool records a {@code PENDING} {@link ContributionEntity}; an admin must approve before the
 * payload is materialised under {@code shared/agents/<targetAgentId>/<type>/<path>}.
 *
 * <p>Unlike the original version, this tool does <em>not</em> require the LLM to inline the file
 * content — instead, the LLM lists workspace-relative source paths in {@code source_paths} and the
 * tool reads the bytes from the caller's sandbox via {@link WorkspaceManagerFactory}. This avoids
 * payload truncation in the LLM's tool-call serialization, keeps large bundles cheap to nominate,
 * and ensures the admin sees exactly what the contributor has on disk.
 *
 * <p>For multi-file skill bundles ({@code target_type=skill}), pass several comma-separated
 * {@code source_paths} — each lands as a file inside the bundle under its file name (basename).
 * Sub-directory layouts inside a skill bundle are best driven from the web UI's file-tree picker.
 *
 * <p>Registered as a singleton onto the built-in {@code data-agent} main agent at startup by
 * {@link ContributionToolRegistrar}.
 */
public final class ContributeWorkspaceTool {

    private final MarketContributionService service;
    private final WorkspaceManagerFactory workspaceFactory;

    public ContributeWorkspaceTool(
            MarketContributionService service, WorkspaceManagerFactory workspaceFactory) {
        this.service = Objects.requireNonNull(service, "service");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
    }

    @Tool(
            name = "contribute_to_workspace",
            description =
                    """
                    将一个或多个工作区文件（skill、sub-agent、memory 片段、\
                    AGENTS.md 或知识文档）提名升级到代理的共享工作区，\
                    以便同一代理的其他用户也能受益。工具从调用者的沙箱中读取文件内容；\
                    请勿将内容内联到参数中。提交一个待审批（PENDING）的贡献，\
                    需要管理员批准后才能生效。调用此工具前请先征得用户同意。\
                    成功返回 "ok: contribution #N submitted, awaiting admin approval"，\
                    或返回以 "error:" 开头的错误信息。\
                    """)
    public String contribute(
            @ToolParam(
                            name = "source_user_id",
                            description = "来源用户的身份标识，即该文件所属的用户。" + "从当前活跃会话上下文中获取。")
                    String sourceUserId,
            @ToolParam(name = "source_agent_id", description = "源文件所在的代理 ID。工具从此用户的该代理沙箱中读取文件。")
                    String sourceAgentId,
            @ToolParam(
                            name = "target_type",
                            description =
                                    "目标类型，可选值: skill | subagent | memory | agents_md | knowledge")
                    String targetType,
            @ToolParam(
                            name = "target_path",
                            description =
                                    "文件在 shared/agents/<targetAgentId>/<targetType>/ 下的"
                                            + "目标路径。skill 类型为 bundle 目录名；subagent/memory/knowledge"
                                            + "为文件路径；agents_md 为字面值 'AGENTS.md'。")
                    String targetPath,
            @ToolParam(
                            name = "source_paths",
                            description =
                                    "逗号分隔的源文件路径列表（相对于工作区），"
                                            + "从调用者沙箱中采集。单文件目标类型传一个路径；"
                                            + "skill bundle 传每个文件的路径。")
                    String sourcePaths,
            @ToolParam(
                            name = "target_agent_id",
                            description = "目标代理 ID；默认与 source_agent_id 相同。",
                            required = false)
                    String targetAgentId,
            @ToolParam(name = "rationale", description = "向审核管理员说明的一两句话理由。", required = false)
                    String rationale) {
        try {
            List<FileEntry> payload =
                    harvestFiles(sourceUserId, sourceAgentId, targetType, sourcePaths);
            ContributionEntity saved =
                    service.submit(
                            sourceUserId,
                            sourceAgentId,
                            targetAgentId,
                            targetType,
                            targetPath,
                            rationale,
                            payload);
            return "ok: contribution #" + saved.getId() + " submitted, awaiting admin approval";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Reads each comma-separated source path from the caller's sandbox and builds the FileEntry
     * list. For multi-file skill bundles the FileEntry's relPath is set to the file's basename;
     * for single-file target types the relPath is empty (the service routes it to the appropriate
     * default).
     */
    private List<FileEntry> harvestFiles(
            String sourceUserId, String sourceAgentId, String targetType, String sourcePaths) {
        if (sourcePaths == null || sourcePaths.isBlank()) {
            throw new IllegalArgumentException("source_paths must contain at least one path");
        }
        String[] rawPaths = sourcePaths.split(",");
        List<String> cleaned = new ArrayList<>(rawPaths.length);
        for (String raw : rawPaths) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            cleaned.add(trimmed);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("source_paths must contain at least one path");
        }
        boolean isSkillBundle = ContributionEntity.TARGET_SKILL.equals(targetType);
        if (!isSkillBundle && cleaned.size() != 1) {
            throw new IllegalArgumentException(
                    "target_type "
                            + targetType
                            + " accepts exactly one source path (got "
                            + cleaned.size()
                            + ")");
        }

        WorkspaceManager wm = workspaceFactory.forAgent(sourceUserId, sourceAgentId);
        RuntimeContext rc = RuntimeContext.builder().userId(sourceUserId).build();
        List<FileEntry> out = new ArrayList<>(cleaned.size());
        for (String path : cleaned) {
            String content = wm.readManagedWorkspaceFileUtf8(rc, path);
            if (content == null || content.isEmpty()) {
                throw new IllegalArgumentException("source file is empty or unreadable: " + path);
            }
            String relPath = isSkillBundle ? Paths.get(path).getFileName().toString() : "";
            out.add(new FileEntry(relPath, content));
        }
        return out;
    }
}
