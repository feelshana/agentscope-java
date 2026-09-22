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
package io.agentscope.dataagent.web.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Materialises the bundled {@code classpath:shared/**} tree (default skills, sub-agents, memory
 * snippets shipped with the DataAgent build) onto disk at {@code ${cwd}/shared/}.
 *
 * <p>{@code src/main/resources/shared} is the single source of truth for shipped content;
 * {@code ${cwd}/shared/} is a runtime directory that additionally holds admin-approved
 * contributions materialised by
 * {@link io.agentscope.dataagent.web.marketplace.MarketContributionService}. To tell the two
 * apart, every seeded file's sha256 is recorded in {@code ${cwd}/shared/.seed-manifest.json} and
 * the write policy becomes three-state (ADR 0007):
 *
 * <ol>
 *   <li><b>Missing or zero-byte</b> on disk — write the shipped content. This self-heals the
 *       truncation class of failure, where an emptied {@code SKILL.md} silently drops a skill from
 *       the agent's {@code available_skills} because its frontmatter no longer parses.
 *   <li><b>Unmodified since seeding</b> (disk hash equals the manifest hash) but the shipped
 *       content changed — overwrite it, so bundled skills and sub-agents upgrade with the build.
 *   <li><b>Hand-edited by an operator</b> (disk hash differs from the manifest hash) or
 *       <b>not shipped at all</b> (absent from the manifest, i.e. a marketplace contribution) —
 *       never touched.
 * </ol>
 *
 * <p>The one exception is the very first run after this policy was introduced: with no manifest on
 * disk yet, shipped paths are adopted (overwritten) once so a pre-existing drifted copy converges
 * to the bundled content. Previous content stays recoverable from git history, since
 * {@code ${cwd}/shared/} used to be tracked. Deleting {@code .seed-manifest.json} re-enters
 * adoption mode; deleting a single file on disk forces just that file to be re-seeded.
 *
 * <p>Wiring: the seed target ({@code ${cwd}/shared/}) is the same path {@link UserSandboxRegistry}
 * mounts into every fresh container as a read-only workspace projection, and the same path
 * {@code LocalApprovalMarketplace} reads when listing/fetching contributed skills. Seeding here
 * therefore makes every tenant see the bundled {@code sql-analysis} / {@code chart-rendering}
 * skills and {@code data-explorer} / {@code report-writer} sub-agents out of the box without any
 * per-tenant copy step.
 */
@Component
public class SharedWorkspaceSeeder {

    private static final Logger log = LoggerFactory.getLogger(SharedWorkspaceSeeder.class);
    private static final String CLASSPATH_PREFIX = "shared/";

    /** Seed bookkeeping file inside the shared root: {@code relPath -> sha256(shipped bytes)}. */
    static final String MANIFEST_FILE = ".seed-manifest.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataAgentBootstrap bootstrap;
    private final ResourcePatternResolver resolver;

    public SharedWorkspaceSeeder(DataAgentBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.resolver = new PathMatchingResourcePatternResolver();
    }

    @PostConstruct
    void seed() {
        SeedResult result = seedSharedTree(resolver, bootstrap.cwd().resolve("shared").normalize());
        log.info(
                "SharedWorkspaceSeeder: target={}, copied={}, upgraded={}, adopted={},"
                        + " kept-existing={}, kept-modified={}, kept-foreign={}",
                bootstrap.cwd().resolve("shared").normalize(),
                result.copied(),
                result.upgraded(),
                result.adopted(),
                result.keptExisting(),
                result.keptModified(),
                result.keptForeign());
    }

    /**
     * Seeds one shared root. Static and resolver-injected so tests can exercise the policy against
     * a {@code @TempDir} without a {@link DataAgentBootstrap}.
     */
    static SeedResult seedSharedTree(ResourcePatternResolver resolver, Path target) {
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            log.warn("SharedWorkspaceSeeder: failed to create {}: {}", target, e.getMessage());
            return new SeedResult(0, 0, 0, 0, 0, 0);
        }

        Path manifestPath = target.resolve(MANIFEST_FILE);
        // A manifest that exists but fails to parse still counts as present: staying in
        // "never adopt" mode is safer than clobbering operator edits on a corrupt bookkeeping file.
        boolean manifestPresent = Files.exists(manifestPath);
        Map<String, String> manifest = readManifest(manifestPath);

        int copied = 0;
        int upgraded = 0;
        int adopted = 0;
        int keptExisting = 0;
        int keptModified = 0;
        int keptForeign = 0;

        try {
            Path root = target.normalize();
            for (LoadedFile lf : loadClasspathFiles(resolver)) {
                Path dest = root.resolve(lf.relPath()).normalize();
                if (!dest.startsWith(root)) {
                    continue;
                }
                byte[] content = lf.content();
                String shippedHash = sha256(content);

                if (!Files.exists(dest) || Files.size(dest) == 0L) {
                    writeAtomic(dest, content);
                    manifest.put(lf.relPath(), shippedHash);
                    copied++;
                    continue;
                }

                String diskHash = sha256(Files.readAllBytes(dest));
                if (diskHash.equals(shippedHash)) {
                    manifest.put(lf.relPath(), shippedHash);
                    keptExisting++;
                    continue;
                }

                String recorded = manifest.get(lf.relPath());
                if (recorded == null) {
                    if (manifestPresent) {
                        // Marketplace contribution (or a file seeded before bookkeeping existed
                        // while a manifest is already in place): not ours to overwrite.
                        keptForeign++;
                        continue;
                    }
                    writeAtomic(dest, content);
                    manifest.put(lf.relPath(), shippedHash);
                    adopted++;
                    continue;
                }
                if (recorded.equals(diskHash)) {
                    writeAtomic(dest, content);
                    manifest.put(lf.relPath(), shippedHash);
                    upgraded++;
                    continue;
                }
                // Operator hand-edit wins over the shipped copy.
                keptModified++;
            }
        } catch (IOException e) {
            log.warn("SharedWorkspaceSeeder: scan/copy failed: {}", e.getMessage());
        }

        writeManifest(manifestPath, manifest);
        return new SeedResult(copied, upgraded, adopted, keptExisting, keptModified, keptForeign);
    }

    /** Outcome counters of one {@link #seedSharedTree} pass, for logging and assertions. */
    record SeedResult(
            int copied,
            int upgraded,
            int adopted,
            int keptExisting,
            int keptModified,
            int keptForeign) {

        int total() {
            return copied + upgraded + adopted + keptExisting + keptModified + keptForeign;
        }
    }

    /** Reads {@code relPath -> sha256} bookkeeping; returns an empty map when absent or corrupt. */
    private static Map<String, String> readManifest(Path manifestPath) {
        if (!Files.isRegularFile(manifestPath)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> parsed =
                    MAPPER.readValue(
                            Files.readString(manifestPath, StandardCharsets.UTF_8),
                            new TypeReference<LinkedHashMap<String, String>>() {});
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            log.warn(
                    "SharedWorkspaceSeeder: unreadable {} ({}); shipped files will not be"
                            + " upgraded until the manifest is deleted",
                    manifestPath,
                    e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** Best-effort persist of the bookkeeping file; a failure must never abort startup. */
    private static void writeManifest(Path manifestPath, Map<String, String> manifest) {
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
            Files.writeString(manifestPath, json, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("SharedWorkspaceSeeder: failed to write {}: {}", manifestPath, e.getMessage());
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Eagerly enumerates every regular file under {@code classpath*:shared/**}, regardless of
     * whether the classpath element is an exploded directory (development) or a JAR (production
     * fat-jar). Returns paths relative to {@code shared/}.
     */
    private static List<LoadedFile> loadClasspathFiles(ResourcePatternResolver resolver)
            throws IOException {
        Resource[] resources = resolver.getResources("classpath*:" + CLASSPATH_PREFIX + "**/*");
        List<LoadedFile> out = new ArrayList<>();
        for (Resource r : resources) {
            if (!r.isReadable()) continue;
            URI uri;
            try {
                uri = r.getURI();
            } catch (IOException e) {
                continue;
            }
            String scheme = uri.getScheme();
            String rel = extractRelative(uri.toString());
            if (rel == null || rel.isEmpty() || rel.endsWith("/")) continue;

            if ("file".equals(scheme)) {
                Path p = Paths.get(uri);
                if (Files.isDirectory(p)) continue;
                out.add(new LoadedFile(rel, Files.readAllBytes(p)));
            } else if ("jar".equals(scheme)) {
                String s = uri.toString();
                int bang = s.indexOf("!/");
                if (bang < 0) continue;
                URI jarFile = URI.create(s.substring(0, bang + 2));
                String inJarPath = s.substring(bang + 2);
                try (FileSystem fs = openOrCreateJarFs(jarFile)) {
                    Path p = fs.getPath(inJarPath);
                    if (!Files.isRegularFile(p)) continue;
                    out.add(new LoadedFile(rel, Files.readAllBytes(p)));
                }
            } else {
                try (InputStream in = r.getInputStream()) {
                    out.add(new LoadedFile(rel, in.readAllBytes()));
                }
            }
        }

        // Some classpath roots (notably Spring Boot devtools layouts) don't surface intermediate
        // directories via the "**/*" glob; the per-file walk above is the authoritative source.
        // Filter out anything that did not start with the shared/ prefix as a safety net.
        out.removeIf(lf -> lf.relPath().isEmpty());
        return out;
    }

    /**
     * Extracts the path component after {@code /shared/} from a classpath resource URI. Returns
     * {@code null} when the URI does not contain the expected prefix.
     */
    private static String extractRelative(String uriString) {
        int idx = uriString.indexOf("/" + CLASSPATH_PREFIX);
        if (idx < 0) {
            // Might be `shared/...` without leading slash (rare with classpath* roots).
            idx = uriString.indexOf(CLASSPATH_PREFIX);
            if (idx < 0) return null;
            return uriString.substring(idx + CLASSPATH_PREFIX.length());
        }
        return uriString.substring(idx + CLASSPATH_PREFIX.length() + 1);
    }

    private static FileSystem openOrCreateJarFs(URI jarFile) throws IOException {
        try {
            return FileSystems.getFileSystem(jarFile);
        } catch (Exception ignore) {
            return FileSystems.newFileSystem(jarFile, Map.of());
        }
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record LoadedFile(String relPath, byte[] content) {}
}
