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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Locates the official wren skills directory (ADR 0035, specs/022): the {@code skills_content}
 * root bundled inside the installed wrenai pip package, holding one folder per skill
 * ({@code generate-mdl}, {@code enrich-context}, {@code usage}, {@code onboarding},
 * {@code dlt-connector}) each with a {@code SKILL.md}.
 *
 * <p>Resolution order: an explicit {@code dataagent.wren.skills-dir} override wins (invalid
 * values warn and fall through), otherwise the directory is inferred from
 * {@link WrenProperties#resolveExecutable()} — the pip layout puts {@code site-packages/wren}
 * next to the {@code Scripts}/{@code bin} directory that holds the {@code wren} launcher. A
 * candidate only counts when it exists and contains at least one skill folder with a
 * {@code SKILL.md}; otherwise the locator returns empty and callers fail soft (the modeling
 * agent keeps the {@code wren_skills_get} channel).
 *
 * <p>The local package is the preferred source over a git checkout because skill scripts must
 * match the local wren-core serde behavior (the missing-{@code type} incident was exactly a
 * cloud-format vs local-engine mismatch).
 */
@Component
public class WrenSkillsLocator {

    private static final Logger log = LoggerFactory.getLogger(WrenSkillsLocator.class);

    private final WrenProperties props;

    public WrenSkillsLocator(WrenProperties props) {
        this.props = props;
    }

    /**
     * Returns the official skills root, or empty when neither the explicit override nor any
     * inferred candidate points at a valid skills directory. Never throws.
     */
    public Optional<Path> locate() {
        String explicit = props.skillsDir();
        if (!explicit.isBlank()) {
            Path p = Paths.get(explicit).toAbsolutePath().normalize();
            if (isSkillsRoot(p)) {
                return Optional.of(p);
            }
            log.warn(
                    "dataagent.wren.skills-dir '{}' is not a valid skills root (expects skill"
                            + " folders with SKILL.md); falling back to executable inference",
                    p);
        }
        Path exe = Paths.get(props.resolveExecutable());
        Path exeDir = exe.toAbsolutePath().normalize().getParent();
        if (exeDir == null) {
            // Bare "wren" (PATH resolution): no anchor to infer the package layout from.
            return Optional.empty();
        }
        for (Path candidate : candidates(exeDir)) {
            if (isSkillsRoot(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * pip layout candidates relative to the launcher's <em>parent prefix</em>, tried in order.
     * Every real layout keeps {@code site-packages} under the prefix that also holds the
     * launcher directory ({@code Scripts}/{@code bin}): Windows system/venv {@code
     * <prefix>\Lib\site-packages}, Windows user-site {@code <prefix>\site-packages}, Unix
     * {@code <prefix>/lib/python3.x/site-packages} and venv {@code <prefix>/lib/site-packages}.
     * All shapes are probed on every platform — wrong shapes simply fail the existence check,
     * keeping the locator platform-agnostic and trivially testable.
     */
    List<Path> candidates(Path exeDir) {
        List<Path> out = new ArrayList<>();
        Path prefix = exeDir.getParent();
        if (prefix == null) {
            return out;
        }
        out.add(
                prefix.resolve("Lib")
                        .resolve("site-packages")
                        .resolve("wren")
                        .resolve("skills_content"));
        out.add(prefix.resolve("site-packages").resolve("wren").resolve("skills_content"));
        Path lib = prefix.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (Stream<Path> pyDirs = Files.list(lib)) {
                pyDirs.filter(Files::isDirectory)
                        .filter(d -> d.getFileName().toString().startsWith("python3"))
                        .forEach(
                                d ->
                                        out.add(
                                                d.resolve("site-packages")
                                                        .resolve("wren")
                                                        .resolve("skills_content")));
            } catch (IOException e) {
                log.debug("Cannot list {} for python3.* probing: {}", lib, e.getMessage());
            }
        }
        out.add(lib.resolve("site-packages").resolve("wren").resolve("skills_content"));
        return out;
    }

    /** A valid skills root is a directory holding at least one skill folder with a SKILL.md. */
    private boolean isSkillsRoot(Path p) {
        if (!Files.isDirectory(p)) {
            return false;
        }
        try (Stream<Path> subs = Files.list(p)) {
            return subs.anyMatch(sub -> Files.isRegularFile(sub.resolve("SKILL.md")));
        } catch (IOException e) {
            log.debug("Cannot read skills candidate {}: {}", p, e.getMessage());
            return false;
        }
    }
}
