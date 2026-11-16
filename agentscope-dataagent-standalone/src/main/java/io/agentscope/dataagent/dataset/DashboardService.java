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

    public record Asset(
            String id,
            String ownerId,
            String groupId,
            String title,
            String publishedRevision,
            String updatedAt,
            int mdlVersion) {}

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
            boolean deletionPending) {}

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
        var group = queryAccess.resolve(context(user, groupId), groupId);
        String name = title == null || title.isBlank() ? "未命名仪表盘" : title.strip();
        if (name.length() > 80) throw new DatasetException("标题最多80字", 400);
        String id = UUID.randomUUID().toString();
        synchronized (lock(id)) {
            Asset asset = new Asset(id, user, groupId, name, null, now(), group.getMdlVersion());
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
                                                    true)
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
                                            view.deletionPending()));
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
        return withWorkspace(
                context,
                workspace -> {
                    if (!List.of("build", "verify").contains(action))
                        throw new DatasetException("仅开放 GenBI build 和 verify", 403);
                    if ("build".equals(action)) {
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
                    }
                    return verify(workspace.asset(), context.getUserId());
                });
    }

    public View publish(String user, String id, String expectedRevision) {
        synchronized (lock(id)) {
            Asset asset = requireDraft(user, id);
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
                            asset.mdlVersion()));
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

    private String verify(Asset asset, String user) {
        Path project = root(asset.id()).resolve("project");
        String content = html(project.resolve("apps/" + APP_NAME + "/index.html"));
        if (!content.toLowerCase(java.util.Locale.ROOT).contains("<html")
                || !content.contains("dataagent.query"))
            throw new DatasetException("请生成完整 HTML 并使用 dataagent.query 获取真实数据", 422);
        if (queryAccess.resolve(context(user, asset.groupId()), asset.groupId()).getMdlVersion()
                != asset.mdlVersion()) throw new DatasetException("知识库已发布新模型，请新建看板工程，以新模型生成", 409);
        command(project, List.of("genbi", "register", APP_NAME, "--data-mode", "live"));
        String report = command(project, List.of("genbi", "verify", APP_NAME));
        writeAtomic(root(asset.id()).resolve("verified.txt"), revision(content));
        save(
                new Asset(
                        asset.id(),
                        asset.ownerId(),
                        asset.groupId(),
                        asset.title(),
                        asset.publishedRevision(),
                        now(),
                        asset.mdlVersion()));
        return report + "\n内部预览已就绪；仅用户在页面显式点击发布才会更新正式版本。";
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
        String verified = html(root(asset.id()).resolve("verified.txt"));
        return new View(
                asset.id(),
                asset.groupId(),
                asset.title(),
                revision,
                asset.publishedRevision(),
                edit,
                manage,
                revision.equals(verified),
                html,
                asset.updatedAt(),
                user.equals(asset.ownerId()),
                false);
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
