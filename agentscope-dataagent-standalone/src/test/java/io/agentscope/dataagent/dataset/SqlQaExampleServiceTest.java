package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlQaExampleServiceTest {
    @TempDir Path home;
    WrenProperties props;
    WrenPublishedProject published;
    SqlQaExampleService service;
    Path draft;
    Path liked;
    Path deployed;
    final AtomicInteger failures = new AtomicInteger();
    final List<String> indexedSources = new ArrayList<>();
    final String original =
            "---\nnl: 原问题\nsql: >-\n  SELECT 1\ntags:\n- test\n---\n说明\n```sql\nSELECT 1\n```\n";

    @BeforeEach
    void setup() throws Exception {
        props = new WrenProperties("wren", home.toString(), "test", "mysql", 30);
        var groups = mock(DatasetGroupService.class);
        var group = new DatasetGroupEntity("g", "owner", "销售", "");
        group.setMdlVersion(1);
        when(groups.queryGroup("owner", "g")).thenReturn(group);
        WrenCli cli =
                (cwd, timeout, args) -> {
                    if (args.size() > 1 && args.get(1).equals("index")) {
                        try {
                            indexedSources.add(
                                    Files.readString(cwd.resolve("knowledge/sql/liked-query.md")));
                        } catch (Exception e) {
                            indexedSources.add("missing");
                        }
                        if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
                            return new WrenCli.Result(1, "simulated index failure");
                    }
                    return new WrenCli.Result(0, "Backend: lancedb");
                };
        published = new WrenPublishedProject(props, groups, cli);
        service = new SqlQaExampleService(new MdlWorkspaceService(props, cli), published);
        draft = props.groupRoot("g").resolve("workspace/knowledge/sql");
        liked = published.likedProject("owner", "g").resolve("knowledge/sql");
        deployed = props.groupRoot("g").resolve("runtime/query-project");
        Files.createDirectories(draft);
        Files.createDirectories(liked);
        Path snapshot = props.groupRoot("g").resolve("published");
        Files.createDirectories(snapshot.resolve("target"));
        Files.createDirectories(snapshot.resolve("knowledge/sql"));
        Files.writeString(snapshot.resolve("target/mdl.json"), "{}");
        Files.writeString(snapshot.resolve("knowledge/sql/published.md"), original);
    }

    @Test
    void modelingRepeatedEditsStayDraftAndPreserveStrings() throws Exception {
        Files.writeString(draft.resolve("query.md"), original);
        for (int i = 0; i < 3; i++) {
            String sql = "SELECT 'a  b: # 中文' AS v\n-- comment\nFROM 商品";
            var updated = service.update("owner", "g", "modeling", "query.md", "新问题" + i, sql);
            assertThat(updated.sql()).isEqualTo(sql);
            assertThat(service.list("owner", "g"))
                    .anySatisfy(
                            e -> {
                                assertThat(e.sql()).isEqualTo(sql);
                                assertThat(e.question()).startsWith("新问题");
                            });
        }
        assertThat(
                        Files.readString(
                                props.groupRoot("g")
                                        .resolve("published/knowledge/sql/published.md")))
                .isEqualTo(original);
        assertThat(indexedSources).isEmpty();
        try (var files = Files.list(draft)) {
            assertThat(files.toList()).hasSize(1);
        }
    }

    @Test
    void likedRepeatedEditsUpdateProjectionAndOfficialIndex() throws Exception {
        Files.writeString(liked.resolve("query.md"), original);
        for (int i = 0; i < 3; i++) {
            service.update("owner", "g", "liked", "query.md", "新问题" + i, "SELECT " + (i + 2));
            assertThat(
                            SqlExampleDocument.parse(
                                            Files.readString(
                                                    deployed.resolve(
                                                            "knowledge/sql/liked-query.md")))
                                    .sql())
                    .isEqualTo("SELECT " + (i + 2));
        }
        assertThat(indexedSources).hasSize(3);
    }

    @Test
    void failedIndexRestoresSourceProjectionAndPreviousIndex() throws Exception {
        Path target = liked.resolve("query.md");
        Files.writeString(target, original);
        published.indexed("owner", "g");
        failures.set(1);
        assertThatThrownBy(
                        () -> service.update("owner", "g", "liked", "query.md", "错误更新", "SELECT 2"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("已恢复");
        assertThat(Files.readString(target)).isEqualTo(original);
        assertThat(Files.readString(deployed.resolve("knowledge/sql/liked-query.md")))
                .isEqualTo(original);
        assertThat(deployed.resolve(".indexed-source")).exists();
        assertThat(indexedSources).hasSize(3);
    }

    @Test
    void failedRecoveryLeavesNoSuccessMarkerAndNextReadRebuilds() throws Exception {
        Files.writeString(liked.resolve("query.md"), original);
        published.indexed("owner", "g");
        failures.set(2);
        assertThatThrownBy(
                        () -> service.update("owner", "g", "liked", "query.md", "新问题", "SELECT 2"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("索引待恢复");
        assertThat(Files.readString(liked.resolve("query.md"))).isEqualTo(original);
        assertThat(deployed.resolve(".indexed-source")).doesNotExist();
        published.indexed("owner", "g");
        assertThat(deployed.resolve(".indexed-source")).exists();
        assertThat(indexedSources).hasSize(4);
    }

    @Test
    void failedDeleteIndexRestoresFile() throws Exception {
        Files.writeString(liked.resolve("query.md"), original);
        failures.set(1);
        assertThatThrownBy(() -> service.delete("owner", "g", "liked", "query.md"))
                .isInstanceOf(DatasetException.class);
        assertThat(Files.readString(liked.resolve("query.md"))).isEqualTo(original);
    }

    @Test
    void malformedFileIsVisibleUneditableAndUnchanged() throws Exception {
        Path target = draft.resolve("broken.md");
        String bad = "---\nnl: x\nsql: SELECT 1tags:\n- test\n---\n";
        Files.writeString(target, bad);
        var listed = service.list("owner", "g");
        assertThat(listed)
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.editable()).isFalse();
                            assertThat(e.state()).isEqualTo("INVALID");
                        });
        assertThatThrownBy(
                        () ->
                                service.update(
                                        "owner", "g", "modeling", "broken.md", "q", "SELECT 1"))
                .isInstanceOf(DatasetException.class);
        assertThat(Files.readString(target)).isEqualTo(bad);
    }

    @Test
    void invalidRequestsNeverWrite() throws Exception {
        Path target = draft.resolve("query.md");
        Files.writeString(target, original);
        for (String sql : List.of("DROP TABLE 商品", "SELECT 1; SELECT 2"))
            assertThatThrownBy(() -> service.update("owner", "g", "modeling", "query.md", "q", sql))
                    .isInstanceOf(DatasetException.class);
        assertThatThrownBy(
                        () ->
                                service.update(
                                        "owner", "g", "modeling", "../query.md", "q", "SELECT 1"))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> service.update("owner", "g", "other", "query.md", "q", "SELECT 1"))
                .isInstanceOf(DatasetException.class);
        assertThat(Files.readString(target)).isEqualTo(original);
    }

    @Test
    void concurrentLikedEditsAndIndexAreSerialized() throws Exception {
        Files.writeString(liked.resolve("query.md"), original);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks =
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(
                                    i ->
                                            (java.util.concurrent.Callable<Void>)
                                                    () -> {
                                                        service.update(
                                                                "owner",
                                                                "g",
                                                                "liked",
                                                                "query.md",
                                                                "问题" + i,
                                                                "SELECT " + i);
                                                        return null;
                                                    })
                            .toList();
            for (var future : pool.invokeAll(tasks)) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(indexedSources).hasSize(8);
        assertThat(Files.readString(liked.resolve("query.md")))
                .isEqualTo(Files.readString(deployed.resolve("knowledge/sql/liked-query.md")));
    }
}
