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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the per-group official wren workspace (specs/019, ADR 0033 D2/D3): {@code
 * <mdlRoot>/<groupId>/workspace/} is the single source of truth for semantic assets. The platform
 * creates the official {@code wren context init --empty --force} skeleton on demand and keeps
 * {@code wren_project.yml} converged with the group's datasource, while agent edits everywhere
 * else are append-preserving. Also guards platform-owned paths ({@code wren_project.yml}, {@code
 * .platform/**}, {@code target/**}) against agent writes and serialises workspace mutations per
 * group. Agent write tools pre-validate proposals on a throwaway copy ({@code scratch/}) so a
 * rejected write never touches the real workspace.
 *
 * <p>Lock order contract: workspace locks are only ever acquired either alone (agent file tools)
 * or nested inside the group's publish lock (publish chain). Never the other way around.
 */
@Service
public class MdlWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(MdlWorkspaceService.class);

    private final WrenProperties props;
    private final WrenCli wrenCli;

    /** Per-group mutex serialising workspace file mutations (seeder, agent write tools). */
    private final Map<String, ReentrantLock> workspaceLocks = new ConcurrentHashMap<>();

    public MdlWorkspaceService(WrenProperties props, WrenCli wrenCli) {
        this.props = props;
        this.wrenCli = wrenCli;
    }

    /** The group's workspace root (may not exist yet). */
    public Path workspaceRoot(String groupId) {
        return props.groupRoot(groupId).resolve("workspace");
    }

    /** Runs the action while holding the group's workspace mutex. */
    public <T> T withWorkspaceLock(String groupId, Supplier<T> action) {
        ReentrantLock lock = workspaceLocks.computeIfAbsent(groupId, key -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates the official workspace skeleton when missing, then converges {@code
     * wren_project.yml} to the platform-rendered content (group id + datasource). The init never
     * re-runs over an existing workspace: files are append-only per ADR 0033 D2.
     */
    public void ensureWorkspace(String groupId) {
        Path root = workspaceRoot(groupId);
        if (!Files.isRegularFile(root.resolve("wren_project.yml"))) {
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new DatasetException("创建工作区目录失败：" + e.getMessage(), e);
            }
            WrenCli.Result init =
                    wrenCli.run(
                            root,
                            props.timeout(),
                            List.of("context", "init", "--empty", "--force"));
            if (!init.ok()) {
                throw new DatasetException("初始化 wren 工作区失败：" + firstLine(init.output()), 500);
            }
        }
        convergeProjectYml(groupId);
    }

    /**
     * Rewrites {@code wren_project.yml} when its content drifted from the platform render. The
     * file is platform-owned: agents can read but never write it (see {@link #resolveWritable}).
     */
    private void convergeProjectYml(String groupId) {
        Path file = workspaceRoot(groupId).resolve("wren_project.yml");
        String desired = renderProjectYml(groupId);
        try {
            if (!Files.exists(file)
                    || !desired.equals(Files.readString(file, StandardCharsets.UTF_8))) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, desired, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new DatasetException("写入 wren_project.yml 失败：" + e.getMessage(), e);
        }
    }

    /**
     * Resolves a workspace-relative path an agent may write to, refusing traversal outside the
     * workspace and every platform-owned location ({@code wren_project.yml}, {@code
     * .platform/**}, {@code target/**}).
     */
    public Path resolveWritable(String groupId, String relative) {
        Path ws = workspaceRoot(groupId).toAbsolutePath().normalize();
        Path resolved = ws.resolve(relative).normalize();
        if (!resolved.startsWith(ws)) {
            throw new DatasetException("工作区路径越界：" + relative, 400);
        }
        if (isPlatformOwned(groupId, relative)) {
            throw new DatasetException("该文件由平台管理，禁止修改：" + relative, 403);
        }
        return resolved;
    }

    /** Paths that never ship to a publish: the platform mapping and build output. */
    private static boolean isPublishExcluded(String relative) {
        String normalized = relative.replace('\\', '/').strip();
        return normalized.equals(".platform")
                || normalized.startsWith(".platform/")
                || normalized.equals("target")
                || normalized.startsWith("target/");
    }

    /** Platform-owned workspace locations: the project file, the dataset mapping, build output. */
    public boolean isPlatformOwned(String groupId, String relative) {
        String normalized = relative.replace('\\', '/').strip();
        return normalized.equals("wren_project.yml")
                || normalized.equals(".platform")
                || normalized.startsWith(".platform/")
                || normalized.equals("target")
                || normalized.startsWith("target/");
    }

    /**
     * Copies the workspace into the group's staging directory ({@code project/}) for the publish
     * chain, excluding the platform mapping ({@code .platform/}) and build output ({@code
     * target/}); the chain runs its own {@code context build} there. {@code wren_project.yml}
     * IS copied: it is the official project file wren requires at build/query time, even though
     * agents may not edit it. Callers hold the group's publish lock.
     */
    public Path copyToStaging(String groupId) {
        Path workspaceRoot = workspaceRoot(groupId);
        Path staging = props.groupRoot(groupId).resolve("project");
        if (!Files.isDirectory(workspaceRoot)) {
            throw new DatasetException("工作区尚未初始化，无法发布", 400);
        }
        try {
            deleteTree(staging);
            Files.createDirectories(staging);
        } catch (IOException e) {
            throw new DatasetException("拷贝工作区到发布暂存目录失败：" + e.getMessage(), e);
        }
        copyTree(workspaceRoot, staging);
        return staging;
    }

    /**
     * Copies the workspace into the group's scratch directory ({@code scratch/}) as the host for
     * agent write pre-validation (specs/019 §4): the write tools overlay the proposed content on
     * this copy and run the official {@code context validate --strict} there, so a rejected write
     * never touches the real workspace. Exclusions match {@link #copyToStaging}. Callers hold the
     * group's workspace lock; pair with {@link #deleteScratchQuietly} in a {@code finally}.
     */
    public Path copyToScratch(String groupId) {
        Path workspaceRoot = workspaceRoot(groupId);
        if (!Files.isDirectory(workspaceRoot)) {
            throw new DatasetException("工作区尚未初始化，无法预检", 400);
        }
        Path scratch = props.groupRoot(groupId).resolve("scratch");
        try {
            deleteTree(scratch);
            Files.createDirectories(scratch);
        } catch (IOException e) {
            throw new DatasetException("准备工作区预检副本失败：" + e.getMessage(), e);
        }
        copyTree(workspaceRoot, scratch);
        return scratch;
    }

    /** Best-effort removal of the group's scratch directory (pre-write validation host). */
    public void deleteScratchQuietly(String groupId) {
        try {
            deleteTree(props.groupRoot(groupId).resolve("scratch"));
        } catch (IOException e) {
            log.warn(
                    "MdlWorkspaceService: could not clean scratch for group {}: {}",
                    groupId,
                    e.getMessage());
        }
    }

    /** Shared tree copy for publish staging and scratch validation (publish-excluded paths skipped). */
    private void copyTree(Path from, Path to) {
        try {
            try (Stream<Path> walk = Files.walk(from)) {
                for (Path source : walk.filter(Files::isRegularFile).toList()) {
                    String rel = from.relativize(source).toString().replace('\\', '/');
                    if (isPublishExcluded(rel)) {
                        continue;
                    }
                    Path destination = to.resolve(rel);
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new DatasetException("拷贝工作区文件失败：" + e.getMessage(), e);
        }
    }

    /**
     * Platform-rendered project file (schema_version 5, probe-verified golden shape). No {@code
     * profile:} line on purpose: a pinned but missing profile turns into a --strict warning; the
     * runtime profile is pinned via {@code published/wren-source.properties} instead.
     */
    private String renderProjectYml(String groupId) {
        return "schema_version: 5\n"
                + "name: kb-"
                + MdlPublishService.yamlScalar(groupId)
                + "\n"
                + "version: '1.0'\n"
                + "catalog: wren\n"
                + "schema: public\n"
                + "data_source: "
                + MdlPublishService.yamlScalar(props.dataSource())
                + "\n";
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "(无输出)";
        }
        String t = output.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl);
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }
}
