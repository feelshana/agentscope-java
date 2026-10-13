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
package io.agentscope.dataagent.tools.data;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import io.agentscope.dataagent.dataset.WrenProjectAccess.Project;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tenant path adapter for the harness's native file operations; no YAML or semantic decisions. */
public final class WrenProjectFilesystem implements AbstractFilesystem {
    private final WrenProjectAccess access;

    public WrenProjectFilesystem(WrenProjectAccess access) {
        this.access = access;
    }

    private LocalFilesystem delegate(Project project) {
        return new LocalFilesystem(project.root(), true, 10);
    }

    private String checked(Project project, String path, boolean write) {
        Path resolved = access.path(project, path, write);
        String relative = project.root().relativize(resolved).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    @Override
    public LsResult ls(RuntimeContext context, String path) {
        try {
            Project p = access.resolve(context);
            access.checkTree(p);
            LsResult result = delegate(p).ls(context, checked(p, path, false));
            if (!result.isSuccess()) return result;
            return LsResult.success(
                    result.entries().stream().filter(f -> readable(p, f.path())).toList());
        } catch (RuntimeException e) {
            return LsResult.fail(e.getMessage());
        }
    }

    @Override
    public ReadResult read(RuntimeContext context, String path, int offset, int limit) {
        try {
            Project p = access.resolve(context);
            return delegate(p).read(context, checked(p, path, false), offset, limit);
        } catch (RuntimeException e) {
            return ReadResult.fail(e.getMessage());
        }
    }

    @Override
    public WriteResult write(RuntimeContext context, String path, String content) {
        try {
            Project p = access.resolve(context);
            return access.locked(
                    p,
                    () -> {
                        WriteResult result =
                                delegate(p).write(context, checked(p, path, true), content);
                        if (result.isSuccess()) access.changed(p);
                        return result;
                    });
        } catch (RuntimeException e) {
            return WriteResult.fail(e.getMessage());
        }
    }

    @Override
    public EditResult edit(
            RuntimeContext context, String path, String oldString, String newString, boolean all) {
        try {
            Project p = access.resolve(context);
            return access.locked(
                    p,
                    () -> {
                        EditResult result =
                                delegate(p)
                                        .edit(
                                                context,
                                                checked(p, path, true),
                                                oldString,
                                                newString,
                                                all);
                        if (result.isSuccess()) access.changed(p);
                        return result;
                    });
        } catch (RuntimeException e) {
            return EditResult.fail(e.getMessage());
        }
    }

    private boolean readable(Project p, String path) {
        try {
            access.path(p, path, false);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Searches only exposed roots so protected platform files are never read by the native search. */
    private List<String> searchRoots(Project p, String path) {
        String relative = checked(p, path, false);
        if (!relative.isBlank() && !relative.equals(".")) return List.of(relative);
        LsResult listed = delegate(p).ls(RuntimeContext.empty(), ".");
        if (!listed.isSuccess()) throw new IllegalArgumentException(listed.error());
        return listed.entries().stream()
                .filter(f -> readable(p, f.path()))
                .map(f -> checked(p, f.path(), false))
                .toList();
    }

    @Override
    public GrepResult grep(RuntimeContext context, String pattern, String path, String glob) {
        try {
            Project p = access.resolve(context);
            access.checkTree(p);
            List<GrepMatch> matches = new ArrayList<>();
            for (String root : searchRoots(p, path)) {
                GrepResult result = delegate(p).grep(context, pattern, root, glob);
                if (!result.isSuccess()) return result;
                matches.addAll(
                        result.matches().stream().filter(m -> readable(p, m.path())).toList());
            }
            return GrepResult.success(matches);
        } catch (RuntimeException e) {
            return GrepResult.fail(e.getMessage());
        }
    }

    @Override
    public GlobResult glob(RuntimeContext context, String pattern, String path) {
        try {
            Project p = access.resolve(context);
            access.checkTree(p);
            // Globbing reads metadata only. Filtering the native result preserves root files
            // and root-relative patterns without exposing protected platform entries.
            GlobResult result = delegate(p).glob(context, pattern, checked(p, path, false));
            if (!result.isSuccess()) return result;
            return GlobResult.success(
                    result.matches().stream().filter(f -> readable(p, f.path())).toList());
        } catch (RuntimeException e) {
            return GlobResult.fail(e.getMessage());
        }
    }

    public String preflight(RuntimeContext context, ToolUseBlock tool) {
        try {
            Project p = access.resolve(context);
            var r = preview(p, tool.getName(), tool.getInput());
            return r.ok() ? null : r.error();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    public ModelingToolkit.PreviewResult preview(
            String owner, String group, String name, Map<String, Object> input) {
        return preview(access.resolve(owner, group), name, input);
    }

    private ModelingToolkit.PreviewResult preview(
            Project p, String name, Map<String, Object> input) {
        return access.locked(
                p,
                () -> {
                    try {
                        if (input == null || !(input.get("path") instanceof String path))
                            throw new IllegalArgumentException("缺少文件路径");
                        Path file = access.path(p, path, true);
                        String old =
                                Files.isRegularFile(file)
                                        ? Files.readString(file, StandardCharsets.UTF_8)
                                        : "";
                        String next;
                        if (name.equals("write_file")) {
                            if (Files.exists(file))
                                throw new IllegalArgumentException("文件已存在，请使用 edit_file 修改");
                            if (!(input.get("content") instanceof String content))
                                throw new IllegalArgumentException("缺少文件内容");
                            next = content;
                        } else if (name.equals("edit_file")) {
                            if (!Files.isRegularFile(file))
                                throw new IllegalArgumentException("待修改文件不存在");
                            if (!(input.get("old_string") instanceof String original)
                                    || original.isEmpty()
                                    || !(input.get("new_string") instanceof String replacement))
                                throw new IllegalArgumentException("缺少有效替换内容");
                            Object[] result =
                                    FilesystemUtils.performStringReplacement(
                                            old.replace("\r\n", "\n").replace("\r", "\n"),
                                            original.replace("\r\n", "\n").replace("\r", "\n"),
                                            replacement.replace("\r\n", "\n").replace("\r", "\n"),
                                            Boolean.TRUE.equals(input.get("replace_all")));
                            if (result.length == 1)
                                throw new IllegalArgumentException(String.valueOf(result[0]));
                            next = String.valueOf(result[0]);
                        } else if (name.equals("delete_file")) {
                            if (Files.isDirectory(file)) {
                                try (var entries = Files.list(file)) {
                                    if (entries.findAny().isPresent())
                                        throw new IllegalArgumentException("只允许删除文件或空目录，请先逐个撤回文件");
                                }
                            }
                            next = "";
                        } else
                            throw new IllegalArgumentException(
                                    "只支持通用 write_file / edit_file / delete_file");
                        return new ModelingToolkit.PreviewResult(true, null, old, next);
                    } catch (Exception e) {
                        return new ModelingToolkit.PreviewResult(false, e.getMessage(), "", "");
                    }
                });
    }

    @Override
    public boolean exists(RuntimeContext c, String path) {
        try {
            Project p = access.resolve(c);
            return Files.exists(access.path(p, path, false));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public WriteResult delete(RuntimeContext c, String path) {
        try {
            Project p = access.resolve(c);
            return access.locked(
                    p,
                    () -> {
                        try {
                            Path file = access.path(p, path, true);
                            if (Files.isDirectory(file)) {
                                try (var entries = Files.list(file)) {
                                    if (entries.findAny().isPresent())
                                        return WriteResult.fail("只允许删除文件或空目录");
                                }
                            }
                            WriteResult result = delegate(p).delete(c, checked(p, path, true));
                            if (result.isSuccess()) access.changed(p);
                            return result;
                        } catch (java.io.IOException e) {
                            return WriteResult.fail(e.getMessage());
                        }
                    });
        } catch (RuntimeException e) {
            return WriteResult.fail(e.getMessage());
        }
    }

    @io.agentscope.core.tool.Tool(
            name = "delete_file",
            description = "撤回当前授权草稿工程的文件或空目录，适用于官方检查失败后的修正；不能删除平台文件或递归删除目录，需写权限确认。")
    public String deleteFile(
            RuntimeContext context,
            @io.agentscope.core.tool.ToolParam(name = "path", description = "当前工程相对文件或空目录路径")
                    String path) {
        WriteResult result = delete(context, path);
        return result.isSuccess() ? "已撤回工程文件：" + path : "error: " + result.error();
    }

    @Override
    public WriteResult move(RuntimeContext c, String from, String to) {
        return WriteResult.fail("工程移动未开放");
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext c, List<Map.Entry<String, byte[]>> files) {
        throw new UnsupportedOperationException("请使用平台上传入口");
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext c, List<String> paths) {
        throw new UnsupportedOperationException("请使用 read_file");
    }
}
