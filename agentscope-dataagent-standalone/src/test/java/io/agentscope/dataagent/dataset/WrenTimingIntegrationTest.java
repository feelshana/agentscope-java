package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in real CLI probe against an isolated copy; no source database query is executed. */
class WrenTimingIntegrationTest {
    @TempDir Path home;

    @Test
    void realIndexReuseAndExampleChangeHaveSeparateTimings() throws Exception {
        String source = System.getProperty("dataagent.test.wren.timing.project", "");
        String executable = System.getProperty("dataagent.test.wren.executable", "");
        assumeTrue(
                !source.isBlank() && !executable.isBlank(),
                "Requires an explicit test project and Wren executable");
        var props = new WrenProperties(executable, home.toString(), "test", "mysql", 60);
        var groups = mock(DatasetGroupService.class);
        var group = new DatasetGroupEntity("g", "timing-owner", "Timing", "");
        group.setMdlVersion(1);
        when(groups.queryGroup("timing-owner", "g")).thenReturn(group);
        Path published = props.groupRoot("g").resolve("published");
        Path sourceRoot = Path.of(source).toAbsolutePath().normalize();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = sourceRoot.relativize(file);
                String first = relative.getName(0).toString();
                if (!List.of(
                                "models",
                                "views",
                                "cubes",
                                "knowledge",
                                "target",
                                "relationships.yml",
                                "wren_project.yml")
                        .contains(first)) continue;
                Path target = published.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        var cli = new ProcessWrenCli(props);
        var projects = new WrenPublishedProject(props, groups, cli);
        Path runtime = projects.indexed("timing-owner", "g");
        String marker = Files.readString(runtime.resolve(".indexed-source"));
        projects.indexed("timing-owner", "g");
        assertThat(Files.readString(runtime.resolve(".indexed-source"))).isEqualTo(marker);
        Path liked = projects.likedProject("timing-owner", "g").resolve("knowledge/sql");
        Files.createDirectories(liked);
        Files.writeString(
                liked.resolve("timing.md"),
                "---\nnl: timing diagnostic\nsql: SELECT 1 AS n\nsource: user\n---\n");
        projects.indexed("timing-owner", "g");
        assertThat(Files.readString(runtime.resolve(".indexed-source"))).isNotEqualTo(marker);
        var recall =
                cli.run(
                        runtime,
                        Duration.ofSeconds(30),
                        List.of("memory", "recall", "-q", "timing diagnostic", "-o", "json"));
        assertThat(recall.ok()).as(recall.output()).isTrue();
        assertThat(recall.output()).contains("timing diagnostic");
    }
}
