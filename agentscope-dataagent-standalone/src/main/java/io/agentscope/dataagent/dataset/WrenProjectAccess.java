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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Resolves the authenticated conversation's single draft project, without modeling decisions. */
@Component
public final class WrenProjectAccess {
    private static final Set<String> READ_ROOTS =
            Set.of(
                    "models",
                    "views",
                    "cubes",
                    "relationships.yml",
                    "knowledge",
                    "raw",
                    "target",
                    "wren_project.yml");
    private final DatasetGroupService groups;
    private final MdlWorkspaceService workspace;
    private final DatasetKnowledgeRepository knowledge;
    private final ConversationScopeRegistry scopes;

    public WrenProjectAccess(
            DatasetGroupService groups,
            MdlWorkspaceService workspace,
            DatasetKnowledgeRepository knowledge,
            ConversationScopeRegistry scopes) {
        this.groups = groups;
        this.workspace = workspace;
        this.knowledge = knowledge;
        this.scopes = scopes;
    }

    public record Project(String ownerId, String groupId, String name, Path root) {}

    public Project resolve(RuntimeContext context) {
        if (context == null) throw new DatasetException("缺少建模会话上下文", 403);
        DatasetScope scope = context.get(DatasetScope.class);
        String user = context.getUserId();
        if (scope == null) {
            if (user == null || user.isBlank()) throw new DatasetException("无法确定租户上下文", 403);
            List<String> selected = scopes.get(context.getSessionId());
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
                    workspace.ensureWorkspace(group);
                    syncRaw(group, root);
                    return new Project(owner, group, entity.getName(), root);
                });
    }

    /** Only the platform's uploaded document is mirrored; agent-authored raw files are preserved. */
    private void syncRaw(String group, Path root) {
        Path file = root.resolve("raw/business-document.md");
        assertNoLinks(file);
        try {
            var document = knowledge.findById(group).orElse(null);
            String content = document == null ? null : document.getContent();
            if (content == null || content.isBlank()) {
                Files.deleteIfExists(file);
            } else if (!Files.isRegularFile(file)
                    || !content.equals(Files.readString(file, StandardCharsets.UTF_8))) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new DatasetException("同步业务文档到 Wren raw 目录失败", e);
        }
    }

    public Path path(Project project, String path, boolean write) {
        String relative = relative(path);
        String first = relative.split("/", 2)[0].toLowerCase(Locale.ROOT);
        if (!relative.equals(".") && !READ_ROOTS.contains(first)) {
            throw new DatasetException("只能访问当前 Wren 工程的模型、视图、Cube、知识、资料与产物", 403);
        }
        Path resolved = project.root().resolve(relative).normalize();
        if (!resolved.startsWith(project.root())) throw new DatasetException("工程路径越界", 403);
        assertNoLinks(resolved);
        if (write) {
            String lower = relative.toLowerCase(Locale.ROOT);
            if (relative.equals(".")
                    || lower.equals("raw/business-document.md")
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

    private static void assertNoLinks(Path path) {
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
