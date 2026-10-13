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
        when(groups.getGroup("alice", "g")).thenReturn(group);
        when(groups.getGroup("bob", "g")).thenThrow(new DatasetException("不存在", 404));
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
        assertThat(Files.readString(runtime.resolve("knowledge/sql/liked.md"))).isEqualTo("点赞样例");
        assertThatThrownBy(() -> service.prepare("bob", "g")).isInstanceOf(DatasetException.class);
        Files.writeString(published.resolve("target/mdl.json"), "新版本");
        group.setMdlVersion(2);
        service.indexed("alice", "g");
        assertThat(calls.stream().filter(args -> "index".equals(args.get(1)))).hasSize(3);
        assertThat(Files.readString(runtime.resolve("knowledge/sql/liked.md"))).isEqualTo("点赞样例");
    }
}
