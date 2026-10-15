package io.agentscope.dataagent.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Deploys official source and liked examples together without changing compiled semantics. */
@Service
public final class WrenPublishedProject {
    private final WrenProperties props;
    private final DatasetGroupService groups;
    private final WrenCli cli;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public WrenPublishedProject(WrenProperties props, DatasetGroupService groups) {
        this(props, groups, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WrenPublishedProject(WrenProperties props, DatasetGroupService groups, WrenCli cli) {
        this.props = props;
        this.groups = groups;
        this.cli = cli;
    }

    /** Rebuilds the official derived index only when deployed source changes. */
    public Path indexed(String owner, String group) {
        return locked(
                group,
                () -> {
                    Path project = prepare(owner, group);
                    if (cli == null) return project;
                    try {
                        var digest = java.security.MessageDigest.getInstance("SHA-256");
                        digest.update(Files.readAllBytes(project.resolve("target/mdl.json")));
                        Path knowledge = project.resolve("knowledge");
                        if (Files.isDirectory(knowledge)) {
                            try (var paths = Files.walk(knowledge)) {
                                for (Path file :
                                        paths.filter(Files::isRegularFile).sorted().toList()) {
                                    digest.update(
                                            knowledge
                                                    .relativize(file)
                                                    .toString()
                                                    .getBytes(StandardCharsets.UTF_8));
                                    digest.update(Files.readAllBytes(file));
                                }
                            }
                        }
                        String hash = java.util.HexFormat.of().formatHex(digest.digest());
                        Path marker = project.resolve(".indexed-source");
                        String expectedMarker = hash + "\nbackend=lancedb";
                        if (!Files.isRegularFile(marker)
                                || !expectedMarker.equals(Files.readString(marker))) {
                            var result =
                                    cli.run(
                                            project,
                                            props.timeout(),
                                            List.of(
                                                    "memory",
                                                    "index",
                                                    "--mdl",
                                                    project.resolve("target/mdl.json").toString(),
                                                    "--path",
                                                    project.resolve(".wren/memory").toString(),
                                                    "--no-seed"));
                            if (!result.ok())
                                throw new DatasetException("官方 Wren 记忆索引失败，请检查多语言模型依赖或缓存后重试", 502);
                            var status =
                                    cli.run(
                                            project,
                                            props.timeout(),
                                            List.of(
                                                    "memory",
                                                    "status",
                                                    "--path",
                                                    project.resolve(".wren/memory").toString()));
                            if (!status.ok()
                                    || !status.output()
                                            .toLowerCase(java.util.Locale.ROOT)
                                            .contains("lancedb")) {
                                throw new DatasetException(
                                        "Wren 向量记忆后端不可用，请安装 wrenai[memory-onnx] 并确认 LanceDB 配置",
                                        502);
                            }
                            Files.writeString(marker, expectedMarker, StandardCharsets.UTF_8);
                        }
                        return project;
                    } catch (DatasetException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new DatasetException("准备官方 Wren 记忆索引失败", e);
                    }
                });
    }

    public Path prepare(String owner, String group) {
        var entity = groups.getGroup(owner, group);
        return locked(
                group,
                () -> {
                    Path published =
                            props.groupRoot(group)
                                    .resolve("published")
                                    .toAbsolutePath()
                                    .normalize();
                    check(published);
                    if (!Files.isRegularFile(published.resolve("target/mdl.json")))
                        throw new DatasetException("知识库尚无已发布 Wren 工程，请先发布", 409);
                    Path runtime =
                            props.groupRoot(group)
                                    .resolve("runtime/query-project")
                                    .toAbsolutePath()
                                    .normalize();
                    check(runtime);
                    try {
                        Files.createDirectories(runtime);
                        String version = String.valueOf(entity.getMdlVersion());
                        Path marker = runtime.resolve(".published-version");
                        if (!Files.isRegularFile(marker)
                                || !version.equals(Files.readString(marker))) {
                            for (String name :
                                    List.of(
                                            "models",
                                            "views",
                                            "cubes",
                                            "knowledge",
                                            "raw",
                                            "target",
                                            "relationships.yml",
                                            "wren_project.yml",
                                            "wren-source.properties")) {
                                remove(runtime.resolve(name), runtime);
                            }
                            copy(published, runtime);
                            Files.writeString(marker, version, StandardCharsets.UTF_8);
                        }
                        mergeExamples(
                                published.resolve("knowledge/sql"),
                                likedProject(owner, group).resolve("knowledge/sql"),
                                runtime.resolve("knowledge/sql"));
                        return runtime;
                    } catch (IOException e) {
                        throw new DatasetException("部署已发布 Wren 工程失败", e);
                    }
                });
    }

    /** Official store writes here; only its Markdown source is merged into query projects. */
    public Path likedProject(String owner, String group) {
        groups.getGroup(owner, group);
        Path root =
                props.groupRoot(group)
                        .resolve("runtime/liked-examples")
                        .toAbsolutePath()
                        .normalize();
        check(root);
        return root;
    }

    public <T> T locked(String group, Supplier<T> action) {
        synchronized (locks.computeIfAbsent(group, ignored -> new Object())) {
            return action.get();
        }
    }

    private static void mergeExamples(Path published, Path liked, Path to) throws IOException {
        check(published);
        check(liked);
        check(to);
        Files.createDirectories(to);
        Map<String, Path> sources = new LinkedHashMap<>();
        for (Path file : examples(published)) sources.put(file.getFileName().toString(), file);
        for (Path file : examples(liked)) {
            // Both official stores can generate query.md for different Chinese questions.
            // Only derived filenames change; preserve the official Markdown bytes verbatim.
            String name = "liked-" + file.getFileName();
            while (sources.containsKey(name)) name = "liked-" + name;
            sources.put(name, file);
        }
        for (var entry : sources.entrySet()) {
            Path target = to.resolve(entry.getKey());
            if (!Files.isRegularFile(target) || Files.mismatch(entry.getValue(), target) != -1)
                Files.copy(entry.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
        }
        // This directory is a projection of the two sources, never an editable source itself.
        for (Path file : examples(to)) {
            if (!sources.containsKey(file.getFileName().toString())) Files.delete(file);
        }
    }

    private static List<Path> examples(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (var entries = Files.walk(from)) {
            for (Path source : entries.filter(Files::isRegularFile).toList()) {
                Path relative = from.relativize(source);
                if (relative.toString().startsWith(".")) continue;
                Path target = to.resolve(relative);
                check(target);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void remove(Path path, Path root) throws IOException {
        if (!path.normalize().startsWith(root)) throw new DatasetException("部署路径越界", 403);
        check(path);
        if (!Files.exists(path)) return;
        try (var entries = Files.walk(path)) {
            for (Path p : entries.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(p);
        }
    }

    public static void check(Path path) {
        for (Path p = path; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new DatasetException("Wren 工程路径包含链接，拒绝访问", 403);
        if (Files.isDirectory(path)) {
            try (var entries = Files.walk(path)) {
                if (entries.anyMatch(Files::isSymbolicLink))
                    throw new DatasetException("Wren 工程包含链接，拒绝访问", 403);
            } catch (IOException e) {
                throw new DatasetException("检查 Wren 工程失败", e);
            }
        }
    }
}
