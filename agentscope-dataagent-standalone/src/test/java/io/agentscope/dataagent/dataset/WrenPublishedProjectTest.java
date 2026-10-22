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
    void officialLanceSuccessIsDistinctFromGrepAndUnknownOutput() {
        assertThat(
                        WrenPublishedProject.confirmsLanceIndex(
                                "Indexed 13 schema items.\n"
                                        + "Indexed 2 pair(s) from knowledge/sql/.\n"))
                .isTrue();
        assertThat(
                        WrenPublishedProject.confirmsLanceIndex(
                                "Indexed 13 schema items, 6 seed queries.\r\n"))
                .isTrue();
        assertThat(WrenPublishedProject.confirmsLanceIndex("grep backend: no index build needed."))
                .isFalse();
        assertThat(WrenPublishedProject.confirmsLanceIndex("indexed")).isFalse();
    }

    @Test
    void simultaneousPreparationBuildsOnceAndWarmQueryReusesIt() throws Exception {
        var props = new WrenProperties("wren", home.toString(), "test", "mysql", 30);
        var groups = mock(DatasetGroupService.class);
        var group = new DatasetGroupEntity("g", "alice", "测试", "");
        group.setMdlVersion(1);
        when(groups.queryGroup("alice", "g")).thenReturn(group);
        var builds = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var service =
                new WrenPublishedProject(
                        props,
                        groups,
                        (cwd, timeout, args) -> {
                            if (args.get(1).equals("index")) {
                                builds.incrementAndGet();
                                entered.countDown();
                                try {
                                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                        throw new IllegalStateException("timeout");
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                            }
                            return new WrenCli.Result(0, "Backend: lancedb");
                        });
        Path published = props.groupRoot("g").resolve("published/target");
        Files.createDirectories(published);
        Files.writeString(published.resolve("mdl.json"), "{}");
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> service.indexed("alice", "g"));
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> service.indexed("alice", "g"));
            release.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            service.indexed("alice", "g");
            assertThat(builds.get()).isEqualTo(1);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

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
                            return new WrenCli.Result(0, "Indexed 13 schema items.");
                        });
        Path published = props.groupRoot("g").resolve("published");
        Files.createDirectories(published.resolve("target"));
        String mdl = "{\"models\":[{\"name\":\"derived\",\"refSql\":\"SELECT * FROM orders\"}]}";
        Files.writeString(published.resolve("target/mdl.json"), mdl);
        Files.createDirectories(published.resolve("knowledge"));
        Files.writeString(published.resolve("knowledge/metrics.md"), "正式口径");
        Files.createDirectories(published.resolve("knowledge/rules"));
        Files.writeString(published.resolve("knowledge/rules/data-sampling.md"), "已发布样本 PAID");
        Path draft = props.groupRoot("g").resolve("workspace/knowledge");
        Files.createDirectories(draft);
        Files.writeString(draft.resolve("metrics.md"), "未发布口径");
        Files.createDirectories(draft.resolve("rules"));
        Files.writeString(draft.resolve("rules/data-sampling.md"), "未发布样本 PRIVATE");
        Path runtime = service.indexed("alice", "g");
        assertThat(calls.stream().filter(args -> "status".equals(args.get(1)))).isEmpty();
        assertThat(Files.readString(runtime.resolve("target/mdl.json"))).isEqualTo(mdl);
        assertThat(Files.readString(runtime.resolve("knowledge/metrics.md"))).isEqualTo("正式口径");
        assertThat(Files.readString(runtime.resolve("knowledge/rules/data-sampling.md")))
                .isEqualTo("已发布样本 PAID");
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
