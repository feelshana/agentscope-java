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
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
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
            Set.of("models", "relationships.yml", "knowledge", "raw", "target", "wren_project.yml");
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

    public record Project(String ownerId, String groupId, String name, Path root) {}

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
        return resolve(scope.ownerId(), selected.get(0));
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
                    return new Project(owner, group, entity.getName(), root);
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
        if (CLOSED_ROOTS.contains(first)) {
            throw new DatasetException(
                    "基础语义建模不读取也不写入 Cube/View 资产，它们由独立模块处理；已有资产仍会参与官方校验与编译，无需读取，请继续当前建模。", 403);
        }
        if (!relative.equals(".") && !READ_ROOTS.contains(first)) {
            throw new DatasetException("只能访问当前 Wren 工程的模型、关系、知识、资料与产物", 403);
        }
        Path resolved = project.root().resolve(relative).normalize();
        if (!resolved.startsWith(project.root())) throw new DatasetException("工程路径越界", 403);
        assertNoLinks(resolved);
        if (write) {
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

    static void assertNoLinks(Path path) {
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
