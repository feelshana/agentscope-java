package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a confirmed question depends on, and how its changes read (spec108). */
class QuestionDependenciesTest {
    @TempDir Path root;

    private void write(String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void physicalTableNamesViewsAndRelationshipsCountAsDependencies() throws Exception {
        write(
                "models/访问明细/metadata.yml",
                "name: 访问明细\ntable_reference:\n  schema: data_agent\n  table: ds_a_data\n");
        write("models/用户/metadata.yml", "name: 用户\ntable_reference:\n  table: ds_b_data\n");
        write("models/订单/metadata.yml", "name: 订单\n");
        write(
                "views/访问宽表/metadata.yml",
                "name: 访问宽表\nstatement: SELECT * FROM 访问明细 JOIN 用户 ON 1=1\n");
        write(
                "relationships.yml",
                "relationships:\n  - name: 访问_用户\n    models: [访问明细, 用户]\n    condition: x\n");
        var project = QuestionDependencies.read(root);

        // A physical name in pasted SQL maps to its model; the view built on it is an asset.
        var physical =
                QuestionDependencies.snapshot(project, "SELECT COUNT(*) FROM data_agent.ds_a_data");
        assertThat(physical).containsKeys("node:访问明细", "asset:访问宽表", "relation:访问_用户");
        assertThat(physical.keySet()).noneMatch(k -> k.contains("订单"));

        // Reading the view also depends on the models the view reads.
        var view = QuestionDependencies.snapshot(project, "SELECT * FROM 访问宽表");
        assertThat(view).containsKeys("node:访问宽表", "node:访问明细", "node:用户");
    }

    @Test
    void changesAreReadableAndUnchangedSnapshotsGiveNone() throws Exception {
        write("models/访问明细/metadata.yml", "name: 访问明细\n");
        write("knowledge/rules/general.md", "## Default filters\n- 访问明细 保留全部记录\n");
        var before =
                QuestionDependencies.snapshot(
                        QuestionDependencies.read(root), "SELECT * FROM 访问明细");
        assertThat(QuestionDependencies.changes(before, before)).isEmpty();

        write("knowledge/rules/general.md", "## Default filters\n- 访问明细 默认排除测试账号\n");
        write("models/访问明细/metadata.yml", "name: 访问明细\ndescription: 改了\n");
        var after =
                QuestionDependencies.snapshot(
                        QuestionDependencies.read(root), "SELECT * FROM 访问明细");
        assertThat(QuestionDependencies.changes(before, after))
                .containsExactlyInAnyOrder(
                        "模型「访问明细」的定义已修改", "新增规则：访问明细 默认排除测试账号", "删除规则：访问明细 保留全部记录");
    }
}
