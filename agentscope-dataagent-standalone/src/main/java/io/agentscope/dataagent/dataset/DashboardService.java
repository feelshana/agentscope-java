package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Durable GenBI assets, immutable published revisions and per-dashboard authorization. */
@Service
public class DashboardService {
    public static final String AGENT_ID = "dashboard-agent";
    public static final String APP_NAME = "dashboard";
    public static final int MAX_HTML_BYTES = 512 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WrenProperties props;
    private final KnowledgeBaseAccessService access;
    private final WrenQueryAccess queryAccess;
    private final WrenPublishedProject published;
    private final WrenCli cli;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> checking = new ConcurrentHashMap<>();
    private final String generationProcess = UUID.randomUUID().toString();

    public String generationProcess() {
        return generationProcess;
    }

    public record GenerationWorkspace(Asset asset, Path file) {}

    /** Host-only task journal access; generated file tools never receive this path. */
    public <T> T withGeneration(String user, String id, Function<GenerationWorkspace, T> action) {
        synchronized (lock(id)) {
            Asset asset = requireDraft(user, id);
            Path file = root(id).resolve(DashboardGeneration.FILE_NAME);
            WrenProjectAccess.assertNoLinks(file);
            return action.apply(new GenerationWorkspace(asset, file));
        }
    }

    private void requireNoGeneration(String id) {
        requireCheckIdle(id);
        if (activeGeneration(id) != null) throw new DatasetException("看板仍在生成，请先停止任务或等待完成", 409);
    }

    private DashboardGeneration activeGeneration(String id) {
        Path file = root(id).resolve(DashboardGeneration.FILE_NAME);
        WrenProjectAccess.assertNoLinks(file);
        if (!Files.isRegularFile(file)) return null;
        try {
            if (Files.size(file) > MAX_HTML_BYTES) throw new DatasetException("生成任务记录异常", 409);
            var run = JSON.readValue(Files.readString(file), DashboardGeneration.class);
            return run.active() && generationProcess.equals(run.processId()) ? run : null;
        } catch (IOException e) {
            throw new DatasetException("读取生成任务失败", e);
        }
    }

    public record Asset(
            String id,
            String ownerId,
            String groupId,
            String title,
            String publishedRevision,
            String updatedAt,
            int mdlVersion,
            String description,
            String createdAt) {
        public Asset(
                String id,
                String ownerId,
                String groupId,
                String title,
                String publishedRevision,
                String updatedAt,
                int mdlVersion) {
            this(id, ownerId, groupId, title, publishedRevision, updatedAt, mdlVersion, "", null);
        }
    }

    public record FileCheck(String status, String error) {}

    public record View(
            String id,
            String groupId,
            String title,
            String revision,
            String publishedRevision,
            boolean canEdit,
            boolean canPublish,
            boolean verified,
            String html,
            String updatedAt,
            boolean owned,
            boolean deletionPending,
            String description,
            String createdAt,
            @com.fasterxml.jackson.annotation.JsonIgnore String creatorId,
            FileCheck fileCheck) {}

    public record Workspace(Asset asset, Path root) {}

    public DashboardService(
            WrenProperties props,
            KnowledgeBaseAccessService access,
            WrenQueryAccess queryAccess,
            WrenPublishedProject published,
            WrenCli cli) {
        this.props = props;
        this.access = access;
        this.queryAccess = queryAccess;
        this.published = published;
        this.cli = cli;
    }

    public View create(String user, String groupId, String title) {
        return create(user, groupId, title == null || title.isBlank() ? "未命名仪表盘" : title, "");
    }

    public View create(String user, String groupId, String title, String description) {
        var group = queryAccess.resolve(context(user, groupId), groupId);
        String name = title == null ? "" : title.strip();
        String summary = description == null ? "" : description.strip();
        if (name.isBlank() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl))
            throw new DatasetException("看板名称不能为空，最多80字且不能包含换行或控制字符", 400);
        if (summary.length() > 500) throw new DatasetException("描述最多500字", 400);
        String id = UUID.randomUUID().toString();
        synchronized (lock(id)) {
            String created = now();
            Asset asset =
                    new Asset(
                            id,
                            user,
                            groupId,
                            name,
                            null,
                            created,
                            group.getMdlVersion(),
                            summary,
                            created);
            Path project = root(id).resolve("project");
            try {
                Files.createDirectories(project.resolve("apps/" + APP_NAME));
                published.locked(
                        groupId,
                        () -> {
                            queryAccess.resolve(context(user, groupId), groupId);
                            copyContext(published.publishedRoot(groupId), project);
                            return null;
                        });
                Path mdl = project.resolve("target/mdl.json");
                if (!Files.isRegularFile(mdl)) throw new DatasetException("知识库尚无已发布模型", 409);
                Files.copy(mdl, project.resolve("apps/" + APP_NAME + "/mdl.json"));
                save(asset);
                return view(user, asset, true);
            } catch (IOException e) {
                throw new DatasetException("创建仪表盘工程失败", e);
            }
        }
    }

    public List<View> list(String user) {
        List<View> result = new ArrayList<>();
        Path home = home();
        if (!Files.isDirectory(home)) return result;
        try (var dirs = Files.list(home)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (!dir.getFileName().toString().matches("[a-f0-9-]{36}")) continue;
                synchronized (lock(dir.getFileName().toString())) {
                    if (!Files.isRegularFile(dir.resolve("asset.json"))) continue;
                    Asset asset = loadMetadata(dir.getFileName().toString());
                    try {
                        var group = access.requireQuery(user, asset.groupId());
                        boolean edit =
                                user.equals(asset.ownerId())
                                        || access.role(user, group)
                                                == KnowledgeBaseAccessService.Role.MANAGE;
                        if (edit || asset.publishedRevision() != null) {
                            boolean pending = Files.exists(dir.resolve("deleting.flag"));
                            if (pending && !edit) continue;
                            View view =
                                    pending
                                            ? new View(
                                                    asset.id(),
                                                    asset.groupId(),
                                                    asset.title(),
                                                    "",
                                                    asset.publishedRevision(),
                                                    edit,
                                                    edit
                                                            && access.role(user, group)
                                                                    == KnowledgeBaseAccessService
                                                                            .Role.MANAGE,
                                                    false,
                                                    null,
                                                    asset.updatedAt(),
                                                    user.equals(asset.ownerId()),
                                                    true,
                                                    asset.description(),
                                                    asset.createdAt(),
                                                    asset.ownerId(),
                                                    null)
                                            : view(user, asset, edit);
                            result.add(
                                    new View(
                                            view.id(),
                                            view.groupId(),
                                            view.title(),
                                            view.revision(),
                                            view.publishedRevision(),
                                            view.canEdit(),
                                            view.canPublish(),
                                            view.verified(),
                                            null,
                                            view.updatedAt(),
                                            view.owned(),
                                            view.deletionPending(),
                                            view.description(),
                                            view.createdAt(),
                                            view.creatorId(),
                                            null));
                        }
                    } catch (DatasetException denied) {
                        if (denied.status() != 403
                                && denied.status() != 404
                                && denied.status() != 409) throw denied;
                    }
                }
            }
        } catch (IOException e) {
            throw new DatasetException("读取仪表盘列表失败", e);
        }
        result.sort(java.util.Comparator.comparing(View::updatedAt).reversed());
        return result;
    }

    /** Asset labels are independent from generated HTML and immutable content revisions. */
    public View updateMetadata(
            String user, String id, String title, String description, String expectedUpdatedAt) {
        synchronized (lock(id)) {
            Asset asset = load(id);
            requireMetadataEditor(user, asset);
            String name = title == null ? "" : title.strip();
            String summary = description == null ? "" : description.strip();
            if (name.isBlank()
                    || name.length() > 80
                    || name.chars().anyMatch(Character::isISOControl))
                throw new DatasetException("看板名称不能为空，最多80字且不能包含换行或控制字符", 400);
            if (summary.length() > 500) throw new DatasetException("描述最多500字", 400);
            if (expectedUpdatedAt == null || !expectedUpdatedAt.equals(asset.updatedAt()))
                throw new DatasetException("看板信息已变化，请关闭弹窗并刷新后重试", 409);
            if (name.equals(asset.title())
                    && summary.equals(asset.description() == null ? "" : asset.description()))
                return view(user, asset, true);
            requireMetadataEditor(user, asset);
            String updated = now();
            if (updated.equals(asset.updatedAt()))
                updated = Instant.parse(updated).plusNanos(1).toString();
            Asset next =
                    new Asset(
                            asset.id(),
                            asset.ownerId(),
                            asset.groupId(),
                            name,
                            asset.publishedRevision(),
                            updated,
                            asset.mdlVersion(),
                            summary,
                            asset.createdAt());
            save(next);
            return view(user, next, true);
        }
    }

    private void requireMetadataEditor(String user, Asset asset) {
        var group = access.requireQuery(user, asset.groupId());
        if (!user.equals(asset.ownerId())
                && access.role(user, group) != KnowledgeBaseAccessService.Role.MANAGE)
            throw new DatasetException("无法编辑此仪表盘", 404);
    }

    public record Checkpoint(String revision) {}

    /** Bounded page-only undo; native conversation state and published pointers are untouched. */
    public Checkpoint checkpoint(
            String user, String id, String expectedRevision, Runnable requireIdle) {
        synchronized (lock(id)) {
            requireDraft(user, id);
            requireNoGeneration(id);
            requireIdle.run();
            String content = html(root(id).resolve("project/apps/" + APP_NAME + "/index.html"));
            String current = revision(content);
            if (!current.equals(expectedRevision)) throw new DatasetException("草稿已变化，请刷新后再开始", 409);
            Path directory = root(id).resolve("draft-history");
            WrenProjectAccess.assertNoLinks(directory);
            requireDraft(user, id);
            writeAtomic(directory.resolve(current + ".html"), content);
            if (current.equals(html(root(id).resolve("verified.txt"))))
                writeAtomic(directory.resolve(current + ".verified"), current);
            else
                try {
                    WrenProjectAccess.assertNoLinks(directory.resolve(current + ".verified"));
                    Files.deleteIfExists(directory.resolve(current + ".verified"));
                } catch (IOException e) {
                    throw new DatasetException("保存草稿检查点失败", e);
                }
            try (var files = Files.list(directory)) {
                var snapshots =
                        files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.html"))
                                .filter(p -> !p.getFileName().toString().equals(current + ".html"))
                                .sorted(
                                        java.util.Comparator.comparingLong(
                                                        (Path p) -> {
                                                            WrenProjectAccess.assertNoLinks(p);
                                                            try {
                                                                return Files.getLastModifiedTime(p)
                                                                        .toMillis();
                                                            } catch (IOException e) {
                                                                throw new DatasetException(
                                                                        "读取草稿检查点失败", e);
                                                            }
                                                        })
                                                .reversed())
                                .toList();
                for (Path old : snapshots.stream().skip(9).toList()) {
                    WrenProjectAccess.assertNoLinks(old);
                    Path marker =
                            old.resolveSibling(
                                    old.getFileName().toString().replace(".html", ".verified"));
                    WrenProjectAccess.assertNoLinks(marker);
                    Files.deleteIfExists(marker);
                    Files.delete(old);
                }
            } catch (IOException e) {
                throw new DatasetException("整理草稿检查点失败", e);
            }
            return new Checkpoint(current);
        }
    }

    public View restore(
            String user,
            String id,
            String expectedRevision,
            String targetRevision,
            Runnable requireIdle) {
        synchronized (lock(id)) {
            Asset asset = requireDraft(user, id);
            requireNoGeneration(id);
            requireIdle.run();
            if (targetRevision == null || !targetRevision.matches("[a-f0-9]{64}"))
                throw new DatasetException("草稿检查点不存在", 404);
            Path draft = root(id).resolve("project/apps/" + APP_NAME + "/index.html");
            if (!revision(html(draft)).equals(expectedRevision))
                throw new DatasetException("草稿已变化，不能覆盖其他修改，请刷新", 409);
            Path saved = root(id).resolve("draft-history").resolve(targetRevision + ".html");
            WrenProjectAccess.assertNoLinks(saved);
            if (!Files.isRegularFile(saved)) throw new DatasetException("草稿检查点不存在", 404);
            String content = html(saved);
            if (!revision(content).equals(targetRevision))
                throw new DatasetException("草稿检查点内容不一致", 409);
            requireDraft(user, id);
            if (queryAccess.resolve(context(user, asset.groupId()), asset.groupId()).getMdlVersion()
                    != asset.mdlVersion()) throw new DatasetException("知识库已发布新模型，请重新生成看板后再检查", 409);
            writeAtomic(draft, content);
            Path verified = root(id).resolve("verified.txt");
            if (targetRevision.equals(html(saved.resolveSibling(targetRevision + ".verified"))))
                writeAtomic(verified, targetRevision);
            else
                try {
                    WrenProjectAccess.assertNoLinks(verified);
                    Files.deleteIfExists(verified);
                } catch (IOException e) {
                    throw new DatasetException("恢复草稿检查状态失败", e);
                }
            Asset next =
                    new Asset(
                            asset.id(),
                            asset.ownerId(),
                            asset.groupId(),
                            asset.title(),
                            asset.publishedRevision(),
                            now(),
                            asset.mdlVersion(),
                            asset.description(),
                            asset.createdAt());
            save(next);
            return view(user, next, true);
        }
    }

    public View get(String user, String id, boolean draft) {
        synchronized (lock(id)) {
            return view(user, load(id), draft);
        }
    }

    /** A durable tombstone closes admission before external session cleanup begins. */
    public void delete(
            String user,
            String id,
            Runnable requireIdle,
            java.util.function.Consumer<Path> cleanup) {
        synchronized (lock(id)) {
            Asset asset = loadMetadata(id);
            var group = access.requireQuery(user, asset.groupId());
            if (!user.equals(asset.ownerId())
                    && access.role(user, group) != KnowledgeBaseAccessService.Role.MANAGE)
                throw new DatasetException("无法删除此仪表盘", 404);
            Path directory = root(id).toAbsolutePath().normalize();
            if (!directory.getParent().equals(home().toAbsolutePath().normalize()))
                throw new DatasetException("看板清理目录无效", 403);
            try (var walk = Files.walk(directory)) {
                for (Path file : walk.toList()) WrenProjectAccess.assertNoLinks(file);
            } catch (IOException e) {
                throw new DatasetException("检查清理目录失败，请重试", e);
            }
            requireNoGeneration(id);
            requireIdle.run();
            var current = access.requireQuery(user, asset.groupId());
            if (!user.equals(asset.ownerId())
                    && access.role(user, current) != KnowledgeBaseAccessService.Role.MANAGE)
                throw new DatasetException("无法删除此仪表盘", 404);
            writeAtomic(directory.resolve("deleting.flag"), "pending");
            cleanup.accept(directory);
            // Keep the authorization record and tombstone until all data is gone, so retries remain
            // possible.
            try (var walk = Files.walk(directory)) {
                for (Path file : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    WrenProjectAccess.assertNoLinks(file);
                    if (file.equals(directory)
                            || file.equals(directory.resolve("asset.json"))
                            || file.equals(directory.resolve("deleting.flag"))
                            || file.equals(directory.resolve("deletion-plan.json"))) continue;
                    Files.deleteIfExists(file);
                }
                Files.deleteIfExists(directory.resolve("deletion-plan.json"));
                Files.delete(directory.resolve("asset.json"));
                Files.deleteIfExists(directory.resolve("deleting.flag"));
                Files.delete(directory);
            } catch (IOException e) {
                throw new DatasetException("看板清理未完成，请重试删除", e);
            }
        }
    }

    public Asset requireDraft(String user, String id) {
        Asset asset = load(id);
        var group = access.requireQuery(user, asset.groupId());
        if (!user.equals(asset.ownerId())
                && access.role(user, group) != KnowledgeBaseAccessService.Role.MANAGE)
            throw new DatasetException("无法访问此仪表盘草稿", 404);
        queryAccess.resolve(context(user, asset.groupId()), asset.groupId());
        return asset;
    }

    public <T> T withWorkspace(RuntimeContext context, Function<Workspace, T> action) {
        DashboardTurn turn = context == null ? null : context.get(DashboardTurn.class);
        if (turn == null) throw new DatasetException("请从仪表盘工作台发起对话", 403);
        synchronized (lock(turn.dashboardId())) {
            Asset asset = requireDraft(context.getUserId(), turn.dashboardId());
            requireCheckIdle(asset.id());
            DatasetScope scope = context.get(DatasetScope.class);
            if (scope == null
                    || !context.getUserId().equals(scope.ownerId())
                    || !List.of(asset.groupId()).equals(scope.groupIds()))
                throw new DatasetException("仪表盘与本轮知识库不匹配", 403);
            return action.apply(new Workspace(asset, root(asset.id()).resolve("project")));
        }
    }

    public Path file(Workspace workspace, String input, boolean write) {
        String relative = input == null ? "." : input.replace('\\', '/').replaceFirst("^/+", "");
        if (relative.isBlank()) relative = ".";
        if (relative.contains(":") || relative.contains(".."))
            throw new DatasetException("看板文件路径越界", 403);
        boolean allowed =
                write
                        ? relative.equals("apps/dashboard/index.html")
                        : List.of(
                                        ".",
                                        "apps",
                                        "apps/dashboard",
                                        "target",
                                        "target/mdl.json",
                                        "apps/dashboard/mdl.json",
                                        "apps/dashboard/index.html")
                                .contains(relative);
        if (!allowed) throw new DatasetException("只可编辑 apps/dashboard/index.html；模型由平台准备", 403);
        Path path = workspace.root().resolve(relative).normalize();
        if (!path.startsWith(workspace.root())) throw new DatasetException("看板文件路径越界", 403);
        WrenProjectAccess.assertNoLinks(path);
        return path;
    }

    public String genbi(RuntimeContext context, String action, String prompt) {
        if ("verify".equals(action)) {
            var check =
                    withWorkspace(
                            context,
                            workspace ->
                                    reserveCheck(
                                            workspace.asset(),
                                            html(
                                                    workspace
                                                            .root()
                                                            .resolve(
                                                                    "apps/"
                                                                            + APP_NAME
                                                                            + "/index.html"))));
            return runReservedCheck(check, context.getUserId());
        }
        return withWorkspace(
                context,
                workspace -> {
                    if (!"build".equals(action))
                        throw new DatasetException("仅开放 GenBI build 和 verify", 403);
                    if (prompt == null || prompt.isBlank() || prompt.length() > 16000)
                        throw new DatasetException("请传入本轮看板需求，最多16000字", 400);
                    return command(
                            workspace.root(),
                            List.of(
                                    "genbi",
                                    "build",
                                    APP_NAME,
                                    "--prompt",
                                    prompt,
                                    "--data-mode",
                                    "live"));
                });
    }

    public View publish(String user, String id, String expectedRevision) {
        synchronized (lock(id)) {
            Asset asset = requireDraft(user, id);
            requireNoGeneration(id);
            access.requireManage(user, asset.groupId());
            Path html = root(id).resolve("project/apps/" + APP_NAME + "/index.html");
            String content = html(html);
            String revision = revision(content);
            if (content.isBlank() || !revision.equals(expectedRevision))
                throw new DatasetException("草稿已变化，请重新预览后发布", 409);
            verify(asset, user);
            // ACL is checked again after the external verification and before the commit.
            access.requireManage(user, asset.groupId());
            writeAtomic(root(id).resolve("revisions/" + revision + ".html"), content);
            save(
                    new Asset(
                            id,
                            asset.ownerId(),
                            asset.groupId(),
                            asset.title(),
                            revision,
                            now(),
                            asset.mdlVersion(),
                            asset.description(),
                            asset.createdAt()));
            return view(user, load(id), true);
        }
    }

    public String queryGroup(String user, String id, boolean draft, String expectedRevision) {
        synchronized (lock(id)) {
            Asset asset = load(id);
            View view = view(user, asset, draft);
            if (!view.revision().equals(expectedRevision))
                throw new DatasetException("看板版本已变化，请刷新后重试", 409);
            queryAccess.resolve(context(user, asset.groupId()), asset.groupId());
            return asset.groupId();
        }
    }

    public void requireCheckIdle(String id) {
        if (checking.containsKey(id)) throw new DatasetException("正在检查草稿文件，请稍后再试", 409);
    }

    public boolean checkingDraft(String id) {
        return checking.containsKey(id);
    }

    private record ReservedCheck(Asset asset, String content) {}

    private ReservedCheck reserveCheck(Asset asset, String content) {
        checking.put(asset.id(), revision(content));
        return new ReservedCheck(asset, content);
    }

    private String runReservedCheck(ReservedCheck check, String user) {
        try {
            return verifyContent(check.asset(), user, check.content());
        } finally {
            checking.remove(check.asset().id());
        }
    }

    /** Reserve the project while the CLI runs, leaving progress and previews readable. */
    private View checkDraft(String user, String id, String expectedRevision, Runnable admission) {
        ReservedCheck check;
        synchronized (lock(id)) {
            Asset asset = requireDraft(user, id);
            View current = view(user, asset, true);
            if (!current.revision().equals(expectedRevision))
                throw new DatasetException("草稿已变化，请刷新后检查", 409);
            if (current.html().isBlank()) throw new DatasetException("请先生成看板页面", 422);
            if (checking.containsKey(id)) return current;
            admission.run();
            if (current.verified()) return current;
            check = reserveCheck(asset, current.html());
        }
        runReservedCheck(check, user);
        return get(user, id, true);
    }

    public View checkIdleDraft(String user, String id, String revision, Runnable requireIdle) {
        return checkDraft(
                user,
                id,
                revision,
                () -> {
                    requireNoGeneration(id);
                    requireIdle.run();
                });
    }

    public View checkGeneratedDraft(String user, String id, String revision, String taskId) {
        return checkDraft(
                user,
                id,
                revision,
                () -> {
                    var task = activeGeneration(id);
                    if (task == null
                            || !task.id().equals(taskId)
                            || !task.userId().equals(user)
                            || !"CHECKING".equals(task.status()))
                        throw new DatasetException("生成任务已变化，请重新读取草稿", 409);
                });
    }

    private String verify(Asset asset, String user) {
        Path project = root(asset.id()).resolve("project");
        String content = html(project.resolve("apps/" + APP_NAME + "/index.html"));
        return verifyContent(asset, user, content);
    }

    private String verifyContent(Asset asset, String user, String content) {
        Path project = root(asset.id()).resolve("project");
        Path receipt = root(asset.id()).resolve("verified.txt");
        String revision = revision(content);
        try {
            if (!content.toLowerCase(java.util.Locale.ROOT).contains("<html")
                    || (!content.contains("dataagent.query")
                            && !content.contains("dataagent.panel")))
                throw new DatasetException("请生成完整 HTML 并使用 dataagent.query 获取真实数据", 422);
            if (queryAccess.resolve(context(user, asset.groupId()), asset.groupId()).getMdlVersion()
                    != asset.mdlVersion())
                throw new DatasetException("知识库已发布新模型，请新建看板工程，以新模型生成", 409);
            command(project, List.of("genbi", "register", APP_NAME, "--data-mode", "live"));
            String report = command(project, List.of("genbi", "verify", APP_NAME));
            synchronized (lock(asset.id())) {
                asset = requireDraft(user, asset.id());
                if (!content.equals(html(project.resolve("apps/" + APP_NAME + "/index.html"))))
                    throw new DatasetException("检查期间草稿已变化，请检查最新版本", 409);
                if (access.requireQuery(user, asset.groupId()).getMdlVersion()
                        != asset.mdlVersion())
                    throw new DatasetException("知识库已发布新模型，请新建看板工程，以新模型生成", 409);
                writeAtomic(receipt, revision);
                save(
                        new Asset(
                                asset.id(),
                                asset.ownerId(),
                                asset.groupId(),
                                asset.title(),
                                asset.publishedRevision(),
                                now(),
                                asset.mdlVersion(),
                                asset.description(),
                                asset.createdAt()));
            }
            return report + "\n文件检查通过，页面运行状态以预览自检为准；仅用户在页面显式点击发布才会更新正式版本。";
        } catch (RuntimeException failed) {
            String detail = failed.getMessage() == null ? "文件检查未完成，请重试" : failed.getMessage();
            writeAtomic(
                    receipt,
                    "FAILED "
                            + revision
                            + "\n"
                            + detail.substring(0, Math.min(500, detail.length())));
            throw failed;
        }
    }

    private View view(String user, Asset asset, boolean draft) {
        var group = access.requireQuery(user, asset.groupId());
        boolean manage = access.role(user, group) == KnowledgeBaseAccessService.Role.MANAGE;
        boolean edit = user.equals(asset.ownerId()) || manage;
        if (draft && !edit) throw new DatasetException("无法访问此仪表盘草稿", 404);
        if (!draft && asset.publishedRevision() == null)
            throw new DatasetException("此仪表盘尚未发布", 404);
        Path file =
                draft
                        ? root(asset.id()).resolve("project/apps/" + APP_NAME + "/index.html")
                        : root(asset.id())
                                .resolve("revisions/" + asset.publishedRevision() + ".html");
        String html = html(file);
        String revision = draft ? revision(html) : asset.publishedRevision();
        String receipt = draft ? html(root(asset.id()).resolve("verified.txt")) : revision;
        FileCheck check;
        if (draft && revision.equals(checking.get(asset.id())))
            check = new FileCheck("CHECKING", null);
        else if (draft && group.getMdlVersion() != asset.mdlVersion())
            check = new FileCheck("MODEL_CHANGED", "知识库已发布新模型，请基于该知识库新建看板");
        else if (revision.equals(receipt)) check = new FileCheck("PASSED", null);
        else if (receipt.startsWith("FAILED " + revision + "\n"))
            check = new FileCheck("FAILED", receipt.substring(receipt.indexOf('\n') + 1));
        else check = new FileCheck(receipt.isEmpty() ? "UNCHECKED" : "STALE", null);
        return new View(
                asset.id(),
                asset.groupId(),
                asset.title(),
                revision,
                asset.publishedRevision(),
                edit,
                manage,
                "PASSED".equals(check.status()),
                html,
                asset.updatedAt(),
                user.equals(asset.ownerId()),
                false,
                asset.description(),
                asset.createdAt(),
                asset.ownerId(),
                draft ? check : null);
    }

    private String command(Path root, List<String> args) {
        WrenCli.Result result = cli.run(root, props.timeout(), args);
        if (!result.ok()) throw new DatasetException("官方 GenBI 执行失败：" + result.output(), 422);
        return result.output();
    }

    private void copyContext(Path from, Path to) {
        try {
            for (String name :
                    List.of(
                            "models",
                            "relationships.yml",
                            "knowledge",
                            "wren_project.yml",
                            "target/mdl.json")) {
                Path source = from.resolve(name);
                if (!Files.exists(source)) continue;
                WrenProjectAccess.assertNoLinks(source);
                try (var walk = Files.walk(source)) {
                    for (Path file : walk.toList()) {
                        WrenProjectAccess.assertNoLinks(file);
                        Path target = to.resolve(from.relativize(file));
                        if (Files.isDirectory(file)) Files.createDirectories(target);
                        else {
                            Files.createDirectories(target.getParent());
                            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new DatasetException("复制已发布语义上下文失败", e);
        }
    }

    private Object lock(String id) {
        root(id);
        return locks.computeIfAbsent(id, ignored -> new Object());
    }

    private Path home() {
        Path p = props.mdlRoot().resolve("dashboards");
        WrenProjectAccess.assertNoLinks(p);
        return p;
    }

    private Path root(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            throw new DatasetException("仪表盘不存在", 404);
        Path p = home().resolve(id);
        WrenProjectAccess.assertNoLinks(p);
        return p;
    }

    private Asset load(String id) {
        Asset asset = loadMetadata(id);
        if (Files.exists(root(id).resolve("deleting.flag")))
            throw new DatasetException("仪表盘正在清理，请完成删除后再操作", 409);
        return asset;
    }

    private Asset loadMetadata(String id) {
        Path file = root(id).resolve("asset.json");
        WrenProjectAccess.assertNoLinks(file);
        if (!Files.isRegularFile(file)) throw new DatasetException("仪表盘不存在", 404);
        try {
            return JSON.readValue(Files.readString(file), Asset.class);
        } catch (IOException e) {
            throw new DatasetException("仪表盘记录读取失败", e);
        }
    }

    private void save(Asset asset) {
        try {
            writeAtomic(root(asset.id()).resolve("asset.json"), JSON.writeValueAsString(asset));
        } catch (IOException e) {
            throw new DatasetException("仪表盘记录保存失败", e);
        }
    }

    private static String html(Path file) {
        WrenProjectAccess.assertNoLinks(file);
        if (!Files.isRegularFile(file)) return "";
        try {
            if (Files.size(file) > MAX_HTML_BYTES) throw new DatasetException("看板页面超出512KB限制", 422);
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("读取看板页面失败", e);
        }
    }

    private static void writeAtomic(Path file, String text) {
        WrenProjectAccess.assertNoLinks(file);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                throw new DatasetException("看板持久目录必须支持原子替换", e);
            }
        } catch (IOException e) {
            throw new DatasetException("保存看板失败", e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                /* Preserve original error. */
            }
        }
    }

    private static String revision(String content) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RuntimeContext context(String user, String group) {
        return RuntimeContext.builder()
                .userId(user)
                .put(DatasetScope.class, new DatasetScope(user, List.of(group)))
                .build();
    }

    private static String now() {
        return Instant.now().toString();
    }
}
