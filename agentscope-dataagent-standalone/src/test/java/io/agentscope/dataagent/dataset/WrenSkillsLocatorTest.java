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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the official wren skills resolution (specs/022, ADR 0035): the explicit
 * {@code dataagent.wren.skills-dir} override wins when valid, otherwise the directory is inferred
 * from the resolved executable via the pip layouts (Windows user-site / venv, Unix python3.* /
 * venv), and every candidate must hold at least one skill folder with a {@code SKILL.md}.
 * Everything misses → empty, callers fail soft.
 */
class WrenSkillsLocatorTest {

    @TempDir Path temp;

    private WrenProperties propsFor(String executable) {
        return new WrenProperties(
                executable, temp.resolve("mdl").toString(), "dataagent", "mysql", 30);
    }

    /** Creates &lt;skillsRoot&gt;/&lt;skill&gt;/SKILL.md so the directory counts as a skills root. */
    private static Path seedSkill(Path skillsRoot, String skill) throws IOException {
        Path dir = skillsRoot.resolve(skill);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "# " + skill + "\n");
        return skillsRoot;
    }

    @Test
    void explicitConfigWinsWhenValid() throws IOException {
        Path explicit = seedSkill(temp.resolve("custom-skills"), "generate-mdl");
        WrenProperties props = propsFor("wren");
        props.setSkillsDir(explicit.toString());
        assertEquals(
                Optional.of(explicit.toAbsolutePath().normalize()),
                new WrenSkillsLocator(props).locate());
    }

    @Test
    void invalidExplicitFallsBackToInference() throws IOException {
        // Explicit override exists but holds no SKILL.md → ignored.
        Path broken = Files.createDirectories(temp.resolve("broken").resolve("generate-mdl"));
        Path inferred =
                seedSkill(
                        temp.resolve("site-packages").resolve("wren").resolve("skills_content"),
                        "enrich-context");
        WrenProperties props = propsFor(temp.resolve("Scripts").resolve("wren.exe").toString());
        props.setSkillsDir(broken.toString());
        assertEquals(
                Optional.of(inferred.toAbsolutePath().normalize()),
                new WrenSkillsLocator(props).locate(),
                "must fall through to executable inference");
    }

    @Test
    void windowsUserSiteLayoutIsDetected() throws IOException {
        // pip install --user: <root>\Scripts\wren.exe, <root>\site-packages\wren\skills_content.
        Path skills =
                seedSkill(
                        temp.resolve("site-packages").resolve("wren").resolve("skills_content"),
                        "usage");
        Files.createDirectories(temp.resolve("Scripts"));
        Optional<Path> found =
                new WrenSkillsLocator(
                                propsFor(temp.resolve("Scripts").resolve("wren.exe").toString()))
                        .locate();
        assertEquals(Optional.of(skills.toAbsolutePath().normalize()), found);
    }

    @Test
    void windowsVenvLayoutIsDetected() throws IOException {
        // venv: <venv>\Scripts\wren.exe, <venv>\Lib\site-packages\wren\skills_content.
        Path skills =
                seedSkill(
                        temp.resolve("Lib")
                                .resolve("site-packages")
                                .resolve("wren")
                                .resolve("skills_content"),
                        "onboarding");
        Files.createDirectories(temp.resolve("Scripts"));
        Optional<Path> found =
                new WrenSkillsLocator(
                                propsFor(temp.resolve("Scripts").resolve("wren.exe").toString()))
                        .locate();
        assertEquals(Optional.of(skills.toAbsolutePath().normalize()), found);
    }

    @Test
    void unixPython3LayoutIsDetected() throws IOException {
        // pip install --user on Linux: <prefix>/bin/wren,
        // <prefix>/lib/python3.x/site-packages/wren/skills_content.
        Path skills =
                seedSkill(
                        temp.resolve("lib")
                                .resolve("python3.13")
                                .resolve("site-packages")
                                .resolve("wren")
                                .resolve("skills_content"),
                        "generate-mdl");
        Files.createDirectories(temp.resolve("bin"));
        Optional<Path> found =
                new WrenSkillsLocator(propsFor(temp.resolve("bin").resolve("wren").toString()))
                        .locate();
        assertEquals(Optional.of(skills.toAbsolutePath().normalize()), found);
    }

    @Test
    void unixVenvLayoutIsDetected() throws IOException {
        // venv on Linux: <venv>/bin/wren, <venv>/lib/site-packages/wren/skills_content.
        Path skills =
                seedSkill(
                        temp.resolve("lib")
                                .resolve("site-packages")
                                .resolve("wren")
                                .resolve("skills_content"),
                        "usage");
        Files.createDirectories(temp.resolve("bin"));
        Optional<Path> found =
                new WrenSkillsLocator(propsFor(temp.resolve("bin").resolve("wren").toString()))
                        .locate();
        assertEquals(Optional.of(skills.toAbsolutePath().normalize()), found);
    }

    @Test
    void bareExecutableYieldsEmpty() {
        // Default "wren" resolves via PATH at spawn time; no anchor → nothing to infer from.
        assertTrue(new WrenSkillsLocator(propsFor("wren")).locate().isEmpty());
    }

    @Test
    void directoryWithoutSkillMdYieldsEmpty() throws IOException {
        // Layout matches but no skill folder carries a SKILL.md → not a skills root.
        Files.createDirectories(
                temp.resolve("site-packages")
                        .resolve("wren")
                        .resolve("skills_content")
                        .resolve("generate-mdl"));
        Files.createDirectories(temp.resolve("Scripts"));
        assertTrue(
                new WrenSkillsLocator(
                                propsFor(temp.resolve("Scripts").resolve("wren.exe").toString()))
                        .locate()
                        .isEmpty(),
                "a directory without SKILL.md must not count as a skills root");
    }
}
