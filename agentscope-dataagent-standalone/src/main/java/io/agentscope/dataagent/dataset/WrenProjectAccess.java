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
package io.agentscope.dataagent.dataset;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.runtime.session.ConversationScopeRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Resolves the authenticated conversation's single draft project, without modeling decisions. */
@Component
public final class WrenProjectAccess {
    // cubes/ and views/ are neither readable nor writable by the modeling agent; existing assets
    // still take part in the official CLI's validate/build, which reads the project directly.
    private static final Set<String> CLOSED_ROOTS = Set.of("cubes", "views");
    private static final Set<String> READ_ROOTS =
            Set.of(
                    "models",
                    "relationships.yml",
                    "knowledge",
                    "raw",
                    "target",
                    "wren_project.yml",
                    CaliberList.DIRECTORY);
    private final DatasetGroupService groups;
    private final MdlWorkspaceService workspace;
    private final DatasetKnowledgeRepository knowledge;
    private final ConversationScopeRegistry scopes;
    private final io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasets;

    @org.springframework.beans.factory.annotation.Autowired
    public WrenProjectAccess(
            DatasetGroupService groups,
            MdlWorkspaceService workspace,
            DatasetKnowledgeRepository knowledge,
            ConversationScopeRegistry scopes,
            io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasets) {
        this.groups = groups;
        this.workspace = workspace;
        this.knowledge = knowledge;
        this.scopes = scopes;
        this.datasets = datasets;
    }

    public WrenProjectAccess(
            DatasetGroupService groups,
            MdlWorkspaceService workspace,
            DatasetKnowledgeRepository knowledge,
            ConversationScopeRegistry scopes) {
        this(groups, workspace, knowledge, scopes, null);
    }

    public record Project(
            String ownerId,
            String groupId,
            String name,
            Path root,
            boolean assetTurn,
            // spec114 §8: the coach reads existing query templates to judge reuse, never writes.
            boolean coachTurn,
            // spec149: drafting caliber lists from business documents.
            boolean caliberTurn,
            // spec149 P6: the user's own words (a chat request or confirmation answers); it may
            // edit an existing caliber list.
            boolean chatTurn,
            // spec149 P7-2 (ADR0156): a caliber knowledge base; only it uses caliber lists.
            boolean caliberKb) {
        public Project(
                String ownerId,
                String groupId,
                String name,
                Path root,
                boolean assetTurn,
                boolean coachTurn,
                boolean caliberTurn,
                boolean chatTurn) {
            this(ownerId, groupId, name, root, assetTurn, coachTurn, caliberTurn, chatTurn, false);
        }

        public Project(
                String ownerId,
                String groupId,
                String name,
                Path root,
                boolean assetTurn,
                boolean coachTurn,
                boolean caliberTurn) {
            this(ownerId, groupId, name, root, assetTurn, coachTurn, caliberTurn, false);
        }

        public Project(String ownerId, String groupId, String name, Path root) {
            this(ownerId, groupId, name, root, false, false, false);
        }

        public Project(String ownerId, String groupId, String name, Path root, boolean assetTurn) {
            this(ownerId, groupId, name, root, assetTurn, false, false);
        }

        public Project(
                String ownerId,
                String groupId,
                String name,
                Path root,
                boolean assetTurn,
                boolean coachTurn) {
            this(ownerId, groupId, name, root, assetTurn, coachTurn, false);
        }
    }

    public Project resolve(RuntimeContext context) {
        if (context == null) throw new DatasetException("缺少建模会话上下文", 403);
        DatasetScope scope = context.get(DatasetScope.class);
        String user = context.getUserId();
        if (scope == null) {
            if (user == null || user.isBlank()) throw new DatasetException("无法确定租户上下文", 403);
            List<String> selected = scopes.get(user, context.getSessionId());
            scope = new DatasetScope(user, selected == null ? List.of() : selected);
        }
        if (scope.ownerId() == null
                || scope.ownerId().isBlank()
                || (user != null && !user.isBlank() && !user.equals(scope.ownerId()))) {
            throw new DatasetException("建模会话租户不匹配", 403);
        }
        List<String> selected = scope.groupIds();
        if (selected == null || selected.size() != 1) {
            throw new DatasetException("建模会话必须绑定一个知识库，请在当前知识库重新开始会话", 400);
        }
        Project project = resolve(scope.ownerId(), selected.get(0));
        ModelingTurnPurpose purpose = context.get(ModelingTurnPurpose.class);
        // A turn without a declared purpose is the user's own chat request (ModelingTurnPurpose).
        boolean chat = purpose == null || purpose.value() == null;
        // Confirmation answers are the user's own decisions too, so they may settle a caliber
        // list's pending items the same way a chat request does.
        boolean userWords = chat || ModelingTurnPurpose.CONFIRM.equals(purpose.value());
        if (!userWords && !(purpose.asset() || purpose.coach() || purpose.caliber()))
            return project;
        return new Project(
                project.ownerId(),
                project.groupId(),
                project.name(),
                project.root(),
                !chat && purpose.asset(),
                !chat && purpose.coach(),
                !chat && purpose.caliber(),
                userWords,
                project.caliberKb());
    }

    public Project resolve(String owner, String group) {
        DatasetGroupEntity entity = groups.getGroup(owner, group);
        Path root = workspace.workspaceRoot(group).toAbsolutePath().normalize();
        assertNoLinks(root);
        assertNoLinks(root.resolve("wren_project.yml"));
        return workspace.withWorkspaceLock(
                group,
                () -> {
                    groups.getGroup(owner, group);
                    workspace.ensureWorkspace(group);
                    syncRaw(group, root);
                    if (datasets != null)
                        DatasetSampleDocuments.sync(root, datasets.findByGroupId(group));
                    return new Project(
                            owner,
                            group,
                            entity.getName(),
                            root,
                            false,
                            false,
                            false,
                            false,
                            entity.isCaliberMode());
                });
    }

    /**
     * Mirrors the platform's question history only. Business documents live in the draft under
     * {@code raw/docs/} and are managed by {@link BusinessDocumentService} (spec092).
     */
    private void syncRaw(String group, Path root) {
        try {
            Path history = root.getParent().resolve("raw/question-history.md");
            Path material = root.resolve("raw/question-history.md");
            assertNoLinks(history);
            assertNoLinks(material);
            if (Files.isRegularFile(history)) {
                Files.createDirectories(material.getParent());
                Files.copy(history, material, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DatasetException("同步问数历史到 Wren raw 目录失败", e);
        }
    }

    public Path path(Project project, String path, boolean write) {
        String relative = relative(path);
        String first = relative.split("/", 2)[0].toLowerCase(Locale.ROOT);
        // ADR0128: only the turn a data developer started for assets opens views/.
        boolean openView =
                first.equals("views") && (project.assetTurn() || (project.coachTurn() && !write));
        if (CLOSED_ROOTS.contains(first) && !openView) {
            throw new DatasetException(
                    "基础语义建模不读取也不写入 Cube/View 资产，它们由独立模块处理；已有资产仍会参与官方校验与编译，无需读取，请继续当前建模。", 403);
        }
        if (!relative.equals(".") && !READ_ROOTS.contains(first) && !openView) {
            throw new DatasetException("只能访问当前 Wren 工程的模型、关系、知识、资料与产物", 403);
        }
        Path resolved = project.root().resolve(relative).normalize();
        if (!resolved.startsWith(project.root())) throw new DatasetException("工程路径越界", 403);
        assertNoLinks(resolved);
        if (write) {
            // spec149: generated caliber Views and Cubes change only through their list.
            if (openView && CaliberCompiler.isGeneratedPath(project.root(), relative)) {
                throw new DatasetException("这是由口径清单生成的查询模板，不能直接修改；请修改 calibers/ 下对应的口径清单。", 403);
            }
            String lowerPath = relative.toLowerCase(Locale.ROOT);
            boolean caliberFile = first.equals(CaliberList.DIRECTORY);
            // spec149 P7-2 (ADR0156): only a caliber knowledge base keeps caliber lists.
            if (caliberFile && !project.caliberKb())
                throw new DatasetException(
                        "这是普通问数知识库，不使用口径清单：业务文档中的口径按规则和列描述写入。需要按口径严格取数时，请新建“口径问数”知识库。", 403);
            // spec149 P6: a chat turn may edit a list that exists; new lists come from documents.
            if (caliberFile
                    && !project.caliberTurn()
                    && !(project.chatTurn() && Files.isRegularFile(resolved))) {
                throw new DatasetException(
                        project.chatTurn()
                                ? "这张表还没有口径清单，对话中不能新建。口径清单按业务文档起草：告诉用户上传开头写明“适用模型”的业务文档后，页面会自动起草；已有文档时可在“口径”页点“起草口径清单”。"
                                : "口径清单只在起草口径清单、用户对话或写入用户确定的轮次中修改；需要时提示用户在“口径”页起草。",
                        403);
            }
            if (caliberFile && !lowerPath.matches("calibers/[a-z0-9_-]{1,64}\\.yml")) {
                throw new DatasetException(
                        "口径清单只能写 calibers/<名称>.yml（名称用小写字母、数字、下划线），不要创建子目录或其他文件。", 403);
            }
            // The caliber turn drafts lists and may rewrite rules that duplicate them, nothing
            // else.
            if (project.caliberTurn() && !caliberFile) {
                throw new DatasetException("本轮只起草口径清单：只能写 calibers/<名称>.yml。", 403);
            }
            // spec149 P7-3 (ADR0156): in a caliber knowledge base the lists are the only place
            // for calibers, metrics and scope; rules and relationships would compete with them
            // (2026-11-25: an answer wrote a non-existent vip_type_6 value into the rules).
            if (project.caliberKb()
                    && (lowerPath.equals("relationships.yml")
                            || (lowerPath.startsWith("knowledge/")
                                    && !lowerPath.startsWith("knowledge/questions/")))) {
                throw new DatasetException(
                        "口径问数知识库不写业务规则、术语和表间关系：口径、指标、统计范围和使用场景都只写在 calibers/"
                                + " 的口径清单里（只能在起草口径清单、用户对话或写入用户确定的轮次修改），平台据此生成选表规则；列含义写在模型列描述。",
                        403);
            }
            // spec090: Q&A pairs are written by MdlQuestionStore#decide after user confirmation
            // only.
            String sqlDir = relative.toLowerCase(Locale.ROOT);
            if (sqlDir.equals("knowledge/sql") || sqlDir.startsWith("knowledge/sql/")) {
                throw new DatasetException(
                        "knowledge/sql/ 中的问答对只能在用户逐题确认后由平台保存；请把问题与 SQL 写入"
                                + " knowledge/questions/<ID>.yml。",
                        403);
            }
            // Official layout (wren context v2-v5): metadata.yml plus an optional ref_sql.sql.
            if (first.equals("models")
                    && !relative.toLowerCase(Locale.ROOT)
                            .matches("models/[^/]+/(metadata\\.yml|ref_sql\\.sql)")) {
                throw new DatasetException(
                        "models/ 下只能写 models/<模型名>/metadata.yml 或官方"
                                + " ref_sql.sql，不要创建副本或临时文件；请直接在原文件上用 edit_file 修正。",
                        403);
            }
            // spec113: a ref_sql model is a semantic asset, drafted only in a declared asset turn.
            if (!project.assetTurn()
                    && relative.toLowerCase(Locale.ROOT).matches("models/[^/]+/ref_sql\\.sql")) {
                throw new DatasetException(
                        "加工表只在建设语义资产轮次中起草，不要在本轮写入；需要时提示用户在问数优化中讨论，用户明确要求时用 propose_modeling_change"
                                + " 提议。",
                        403);
            }
            if (openView
                    && !relative.toLowerCase(Locale.ROOT)
                            .matches("views/[^/]+/(metadata\\.yml|sql\\.yml)")) {
                throw new DatasetException(
                        "views/ 下只能写 views/<视图名>/metadata.yml 或 sql.yml，不要创建副本或临时文件。", 403);
            }
            String lower = relative.toLowerCase(Locale.ROOT);
            if (relative.equals(".")
                    || lower.equals("raw/business-document.md")
                    || lower.equals("raw/question-history.md")
                    || workspace.isPlatformOwned(project.groupId(), lower)) {
                throw new DatasetException("该位置由平台管理，禁止修改", 403);
            }
            return workspace.resolveWritable(project.groupId(), relative);
        }
        return resolved;
    }

    public String relative(String path) {
        String value = path == null || path.isBlank() ? "." : path.replace('\\', '/');
        if (value.startsWith("//") || value.indexOf(':') >= 0 || value.indexOf('\0') >= 0) {
            throw new DatasetException("不允许宿主绝对路径或特殊路径", 403);
        }
        if (value.equals("/project")) value = ".";
        else if (value.startsWith("/project/")) value = value.substring(9);
        else if (value.startsWith("/")) value = value.substring(1);
        if (value.isBlank()) value = ".";
        for (String part : value.split("/")) {
            if (part.equals("..")
                    || (!part.equals(".") && (part.endsWith(".") || part.endsWith(" ")))
                    || (part.startsWith(".") && !part.equals(".") && !part.equals(".gitkeep"))) {
                throw new DatasetException("不允许路径穿越或隐藏平台目录", 403);
            }
        }
        String normalized = Path.of(value).normalize().toString().replace('\\', '/');
        return normalized.isBlank() ? "." : normalized;
    }

    /** CLI loaders and native searches must not follow links into another tenant's files. */
    public void checkTree(Project project) {
        assertNoLinks(project.root());
        try (var entries = Files.walk(project.root())) {
            if (entries.anyMatch(Files::isSymbolicLink)) {
                throw new DatasetException("工程包含符号链接，禁止跨路径访问", 403);
            }
        } catch (IOException e) {
            throw new DatasetException("检查工程路径失败", e);
        }
    }

    public static void assertNoLinks(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new DatasetException("不允许通过符号链接访问工程", 403);
        }
    }

    public <T> T locked(Project project, java.util.function.Supplier<T> operation) {
        return workspace.withWorkspaceLock(project.groupId(), operation);
    }

    /** Derived memory indexes live outside publishable source files. */
    public Path memoryPath(Project project) {
        Path path = project.root().getParent().resolve("runtime/modeling-memory");
        assertNoLinks(path);
        if (Files.exists(path))
            checkTree(new Project(project.ownerId(), project.groupId(), project.name(), path));
        return path;
    }

    public void changed(Project project) {
        groups.markMdlDirty(project.ownerId(), project.groupId());
    }
}
