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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.workspace.SharedWorkspaceSeeder.SeedResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Covers the three-state write policy of {@link SharedWorkspaceSeeder} (ADR 0007): zero-byte
 * self-heal, version upgrade of files untouched since seeding, operator edits and marketplace
 * contributions left alone, plus the one-off adoption pass when no manifest exists yet.
 */
class SharedWorkspaceSeederTest {

    private static final ResourcePatternResolver RESOLVER =
            new PathMatchingResourcePatternResolver();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SQL_SKILL = "agents/data-agent/skills/sql-analysis/SKILL.md";
    private static final String CONTRIBUTED_SKILL =
            "agents/data-agent/skills/cohort-builder/SKILL.md";

    @TempDir Path sharedRoot;

    @Test
    void seedsEveryShippedFileAndWritesManifest() throws IOException {
        SeedResult result = seed();

        assertThat(result.copied()).isGreaterThan(0);
        assertThat(result.total()).isEqualTo(result.copied());
        assertThat(Files.size(sharedRoot.resolve(SQL_SKILL))).isGreaterThan(0L);
        assertThat(readManifest()).containsKey(SQL_SKILL);
        assertThat(readManifest()).hasSize(result.copied());
    }

    @Test
    void secondPassChangesNothing() {
        seed();

        SeedResult result = seed();

        assertThat(result.copied()).isZero();
        assertThat(result.upgraded()).isZero();
        assertThat(result.keptExisting()).isEqualTo(result.total());
    }

    @Test
    void healsZeroByteSkillFile() throws IOException {
        seed();
        Files.write(sharedRoot.resolve(SQL_SKILL), new byte[0]);

        SeedResult result = seed();

        assertThat(result.copied()).isEqualTo(1);
        assertThat(read(SQL_SKILL)).isEqualTo(shipped(SQL_SKILL));
    }

    @Test
    void upgradesShippedFileThatWasNotHandEdited() throws IOException {
        seed();
        // Simulate an older bundled version that is still on disk unmodified: the manifest records
        // exactly what the previous build shipped.
        String stale = "# 旧版技能\n";
        Files.writeString(sharedRoot.resolve(SQL_SKILL), stale, StandardCharsets.UTF_8);
        putManifestEntry(SQL_SKILL, sha256(stale.getBytes(StandardCharsets.UTF_8)));

        SeedResult result = seed();

        assertThat(result.upgraded()).isEqualTo(1);
        assertThat(read(SQL_SKILL)).isEqualTo(shipped(SQL_SKILL));
        assertThat(readManifest()).containsEntry(SQL_SKILL, sha256(shippedBytes(SQL_SKILL)));
    }

    @Test
    void keepsOperatorHandEdit() throws IOException {
        seed();
        String handEdited = shipped(SQL_SKILL) + "\n# 运营补充的口径说明\n";
        Files.writeString(sharedRoot.resolve(SQL_SKILL), handEdited, StandardCharsets.UTF_8);

        SeedResult result = seed();

        assertThat(result.keptModified()).isEqualTo(1);
        assertThat(result.upgraded()).isZero();
        assertThat(read(SQL_SKILL)).isEqualTo(handEdited);
    }

    @Test
    void keepsUnknownFileAtShippedPathWhenManifestAlreadyExists() throws IOException {
        seed();
        String contributed = "---\nname: sql-analysis\n---\n# 市场贡献版\n";
        Files.writeString(sharedRoot.resolve(SQL_SKILL), contributed, StandardCharsets.UTF_8);
        Map<String, String> manifest = readManifest();
        manifest.remove(SQL_SKILL);
        writeManifest(manifest);

        SeedResult result = seed();

        assertThat(result.keptForeign()).isEqualTo(1);
        assertThat(read(SQL_SKILL)).isEqualTo(contributed);
    }

    @Test
    void adoptsDriftedFilesOnFirstRunWithNoManifest() throws IOException {
        // Pre-manifest deployment: a drifted copy sits on disk and nothing records its provenance.
        Files.createDirectories(sharedRoot.resolve(SQL_SKILL).getParent());
        Files.writeString(sharedRoot.resolve(SQL_SKILL), "# 漂移副本\n", StandardCharsets.UTF_8);
        assertThat(Files.exists(sharedRoot.resolve(SharedWorkspaceSeeder.MANIFEST_FILE))).isFalse();

        SeedResult result = seed();

        assertThat(result.adopted()).isGreaterThan(0);
        assertThat(read(SQL_SKILL)).isEqualTo(shipped(SQL_SKILL));
    }

    @Test
    void neverTouchesMarketplaceContribution() throws IOException {
        seed();
        Path contributed = sharedRoot.resolve(CONTRIBUTED_SKILL);
        Files.createDirectories(contributed.getParent());
        Files.writeString(contributed, "---\nname: cohort-builder\n---\n", StandardCharsets.UTF_8);

        SeedResult result = seed();

        assertThat(read(CONTRIBUTED_SKILL)).isEqualTo("---\nname: cohort-builder\n---\n");
        assertThat(readManifest()).doesNotContainKey(CONTRIBUTED_SKILL);
        assertThat(result.total()).isEqualTo(result.keptExisting());
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private SeedResult seed() {
        return SharedWorkspaceSeeder.seedSharedTree(RESOLVER, sharedRoot);
    }

    private String read(String relPath) throws IOException {
        return Files.readString(sharedRoot.resolve(relPath), StandardCharsets.UTF_8);
    }

    private static String shipped(String relPath) {
        return new String(shippedBytes(relPath), StandardCharsets.UTF_8);
    }

    private static byte[] shippedBytes(String relPath) {
        try (InputStream in =
                SharedWorkspaceSeederTest.class
                        .getClassLoader()
                        .getResourceAsStream("shared/" + relPath)) {
            assertThat(in).as("classpath resource shared/%s must exist", relPath).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("failed to read shipped " + relPath, e);
        }
    }

    private Map<String, String> readManifest() throws IOException {
        Path manifest = sharedRoot.resolve(SharedWorkspaceSeeder.MANIFEST_FILE);
        assertThat(Files.isRegularFile(manifest)).as("manifest must be written").isTrue();
        return MAPPER.readValue(
                Files.readString(manifest, StandardCharsets.UTF_8),
                new TypeReference<LinkedHashMap<String, String>>() {});
    }

    private void putManifestEntry(String relPath, String hash) throws IOException {
        Map<String, String> manifest = readManifest();
        manifest.put(relPath, hash);
        writeManifest(manifest);
    }

    private void writeManifest(Map<String, String> manifest) throws IOException {
        Files.writeString(
                sharedRoot.resolve(SharedWorkspaceSeeder.MANIFEST_FILE),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
