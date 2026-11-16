package io.agentscope.dataagent.tools.data;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Native file-tool adapter: only the conversation-bound HTML draft is writable. */
public final class DashboardFilesystem implements AbstractFilesystem {
    private final DashboardService dashboards;

    public DashboardFilesystem(DashboardService dashboards) {
        this.dashboards = dashboards;
    }

    private LocalFilesystem fs(DashboardService.Workspace w) {
        return new LocalFilesystem(w.root(), true, 10);
    }

    private String path(DashboardService.Workspace w, String input, boolean write) {
        String relative =
                w.root().relativize(dashboards.file(w, input, write)).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    @Override
    public LsResult ls(RuntimeContext c, String p) {
        try {
            return dashboards.withWorkspace(
                    c,
                    w -> {
                        var result = fs(w).ls(c, path(w, p, false));
                        if (!result.isSuccess()) return result;
                        return LsResult.success(
                                result.entries().stream()
                                        .filter(
                                                f -> {
                                                    try {
                                                        dashboards.file(w, f.path(), false);
                                                        return true;
                                                    } catch (RuntimeException denied) {
                                                        return false;
                                                    }
                                                })
                                        .toList());
                    });
        } catch (RuntimeException e) {
            return LsResult.fail(e.getMessage());
        }
    }

    @Override
    public ReadResult read(RuntimeContext c, String p, int offset, int limit) {
        try {
            return dashboards.withWorkspace(
                    c, w -> fs(w).read(c, path(w, p, false), offset, limit));
        } catch (RuntimeException e) {
            return ReadResult.fail(e.getMessage());
        }
    }

    @Override
    public WriteResult write(RuntimeContext c, String p, String text) {
        if (text == null
                || text.getBytes(StandardCharsets.UTF_8).length > DashboardService.MAX_HTML_BYTES)
            return WriteResult.fail("HTML最多512KB");
        try {
            return dashboards.withWorkspace(c, w -> fs(w).write(c, path(w, p, true), text));
        } catch (RuntimeException e) {
            return WriteResult.fail(e.getMessage());
        }
    }

    @Override
    public EditResult edit(
            RuntimeContext c, String p, String old, String replacement, boolean all) {
        try {
            return dashboards.withWorkspace(
                    c,
                    w -> {
                        String target = path(w, p, true);
                        var file = dashboards.file(w, p, true);
                        try {
                            String existing = java.nio.file.Files.readString(file);
                            String updated =
                                    all
                                            ? existing.replace(old, replacement)
                                            : existing.replaceFirst(
                                                    java.util.regex.Pattern.quote(old),
                                                    java.util.regex.Matcher.quoteReplacement(
                                                            replacement));
                            if (updated.getBytes(StandardCharsets.UTF_8).length
                                    > DashboardService.MAX_HTML_BYTES)
                                return EditResult.fail("HTML最多512KB");
                        } catch (java.io.IOException e) {
                            return EditResult.fail("读取草稿失败");
                        }
                        return fs(w).edit(c, target, old, replacement, all);
                    });
        } catch (RuntimeException e) {
            return EditResult.fail(e.getMessage());
        }
    }

    @Override
    public GrepResult grep(RuntimeContext c, String text, String p, String glob) {
        return GrepResult.fail("请用 read_file 读取当前看板");
    }

    @Override
    public GlobResult glob(RuntimeContext c, String pattern, String p) {
        return GlobResult.fail("请用 list_files 查看当前看板");
    }

    @Override
    public WriteResult delete(RuntimeContext c, String p) {
        return WriteResult.fail("请用 edit_file 修改草稿，不开放删除");
    }

    @Override
    public WriteResult move(RuntimeContext c, String a, String b) {
        return WriteResult.fail("不开放移动文件");
    }

    @Override
    public boolean exists(RuntimeContext c, String p) {
        return dashboards.withWorkspace(
                c, w -> java.nio.file.Files.exists(dashboards.file(w, p, false)));
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext c, List<Map.Entry<String, byte[]>> f) {
        throw new UnsupportedOperationException("不开放上传");
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext c, List<String> p) {
        throw new UnsupportedOperationException("不开放下载");
    }
}
