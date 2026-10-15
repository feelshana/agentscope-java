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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WrenPublishedProjectTest {
    @TempDir Path home;

    @Test
    void deploysPublishedManifestVerbatimAndRefreshesOfficialIndexOnSourceChange()
            throws Exception {
        var props = new WrenProperties("wren", home.toString(), "test", "duckdb", 30);
        var groups = mock(DatasetGroupService.class);
        var group = new DatasetGroupEntity("g", "alice", "销售", "");
        group.setMdlVersion(1);
        when(groups.queryGroup("alice", "g")).thenReturn(group);
        when(groups.queryGroup("bob", "g")).thenThrow(new DatasetException("不存在", 404));
        List<List<String>> calls = new ArrayList<>();
        var service =
                new WrenPublishedProject(
                        props,
                        groups,
                        (cwd, timeout, args) -> {
                            calls.add(args);
                            if (args.size() > 1 && "status".equals(args.get(1))) {
                                return new WrenCli.Result(0, "Backend: lancedb");
                            }
                            return new WrenCli.Result(0, "indexed");
                        });
        Path published = props.groupRoot("g").resolve("published");
        Files.createDirectories(published.resolve("target"));
        String mdl = "{\"models\":[{\"name\":\"derived\",\"refSql\":\"SELECT * FROM orders\"}]}";
        Files.writeString(published.resolve("target/mdl.json"), mdl);
        Files.createDirectories(published.resolve("knowledge"));
        Files.writeString(published.resolve("knowledge/metrics.md"), "正式口径");
        Path draft = props.groupRoot("g").resolve("workspace/knowledge");
        Files.createDirectories(draft);
        Files.writeString(draft.resolve("metrics.md"), "未发布口径");
        Path runtime = service.indexed("alice", "g");
        assertThat(Files.readString(runtime.resolve("target/mdl.json"))).isEqualTo(mdl);
        assertThat(Files.readString(runtime.resolve("knowledge/metrics.md"))).isEqualTo("正式口径");
        service.indexed("alice", "g");
        assertThat(calls.stream().filter(args -> "index".equals(args.get(1)))).hasSize(1);
        Path liked = service.likedProject("alice", "g").resolve("knowledge/sql");
        Files.createDirectories(liked);
        Files.writeString(liked.resolve("liked.md"), "点赞样例");
        service.indexed("alice", "g");
        assertThat(calls.stream().filter(args -> "index".equals(args.get(1)))).hasSize(2);
        assertThat(Files.readString(runtime.resolve("knowledge/sql/liked-liked.md")))
                .isEqualTo("点赞样例");
        assertThatThrownBy(() -> service.prepare("bob", "g")).isInstanceOf(DatasetException.class);
        Files.writeString(published.resolve("target/mdl.json"), "新版本");
        group.setMdlVersion(2);
        service.indexed("alice", "g");
        assertThat(calls.stream().filter(args -> "index".equals(args.get(1)))).hasSize(3);
        assertThat(Files.readString(runtime.resolve("knowledge/sql/liked-liked.md")))
                .isEqualTo("点赞样例");
    }

    @Test
    void sameOfficialFilenamesNeverOverwritePublishedExamplesOrLeakDrafts() throws Exception {
        var props = new WrenProperties("wren", home.toString(), "test", "duckdb", 30);
        var groups = mock(DatasetGroupService.class);
        var group = new DatasetGroupEntity("g", "alice", "销售", "");
        group.setMdlVersion(1);
        when(groups.queryGroup("alice", "g")).thenReturn(group);
        when(groups.queryGroup("bob", "g")).thenThrow(new DatasetException("不存在", 404));
        var service = new WrenPublishedProject(props, groups);
        Path published = props.groupRoot("g").resolve("published");
        Files.createDirectories(published.resolve("target"));
        Files.writeString(published.resolve("target/mdl.json"), "{}");
        Files.createDirectories(published.resolve("knowledge/sql"));
        Files.writeString(published.resolve("knowledge/sql/query.md"), "建模推荐问答");
        Files.writeString(published.resolve("knowledge/sql/liked-query.md"), "另一条建模问答");
        Path liked = service.likedProject("alice", "g").resolve("knowledge/sql");
        Files.createDirectories(liked);
        Files.writeString(liked.resolve("query.md"), "点赞问答");
        Path draft = props.groupRoot("g").resolve("workspace/knowledge/sql");
        Files.createDirectories(draft);
        Files.writeString(draft.resolve("private.md"), "未发布问答");
        Path runtime = service.prepare("alice", "g").resolve("knowledge/sql");
        assertThat(Files.readString(runtime.resolve("query.md"))).isEqualTo("建模推荐问答");
        assertThat(Files.readString(runtime.resolve("liked-query.md"))).isEqualTo("另一条建模问答");
        assertThat(Files.readString(runtime.resolve("liked-liked-query.md"))).isEqualTo("点赞问答");
        assertThat(runtime.resolve("private.md")).doesNotExist();
        assertThatThrownBy(() -> service.prepare("bob", "g")).isInstanceOf(DatasetException.class);
        assertThat(Files.readString(published.resolve("knowledge/sql/query.md")))
                .isEqualTo("建模推荐问答");
        Files.delete(published.resolve("knowledge/sql/liked-query.md"));
        group.setMdlVersion(2);
        service.prepare("alice", "g");
        assertThat(Files.readString(runtime.resolve("liked-query.md"))).isEqualTo("点赞问答");
        assertThat(runtime.resolve("liked-liked-query.md")).doesNotExist();
        assertThat(Files.readString(runtime.resolve("query.md"))).isEqualTo("建模推荐问答");
    }
}
