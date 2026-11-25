package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaliberCompilerTest {
    @TempDir Path root;

    static final String LIST =
            """
            model: vip_m
            name: vip_scale
            scenario:
              subject: 咪咕视频会员规模
              time: {column: period_id, grain: month, format: yyyymm}
              measures:
                - {column: cnt_zd, name: members, label: 会员数, description: 预统计订购人数}
              fixed_scope: {province_name: 全国, channel_type: 全部}
              use_for: [会员数, 会员月趋势]
              not_for: [收入]
            calibers:
              - name: 足球通
                group: 体育会员
                synonyms: [足球通会员]
                where: {vip_type_2: 不限, vip_type_3: 体育赛事, vip_type_6: 足球通会员}
                source: {kind: document, document: 业务规则-月.md}
              - name: 视频钻石会员
                group: 视频钻石会员
                where: {vip_type_3: [钻石会员, 黄金会员], channel_type: 公网}
                pending: 钻石与黄金的合并方式
                combine: sum
            """;

    @BeforeEach
    void model() throws Exception {
        write(
                "models/vip_m/metadata.yml",
                """
                name: vip_m
                columns:
                  - {name: period_id, type: INTEGER}
                  - {name: cnt_zd, type: BIGINT}
                  - {name: province_name, type: VARCHAR}
                  - {name: channel_type, type: VARCHAR}
                  - {name: vip_type_2, type: VARCHAR}
                  - {name: vip_type_3, type: VARCHAR}
                  - {name: vip_type_6, type: VARCHAR}
                """);
    }

    @Test
    void compilesEachCaliberIntoOneSelectOfTheGeneratedView() {
        var compiled = CaliberCompiler.compile(root, "calibers/vip_m.yml", LIST);

        assertThat(compiled.errors()).isEmpty();
        assertThat(compiled.viewPath()).isEqualTo("views/vip_scale_caliber/metadata.yml");
        assertThat(compiled.cubePath()).isEqualTo("cubes/vip_scale_metrics/metadata.yml");
        String view = compiled.view();
        // "不限" adds no condition; fixed scope applies; candidates become IN; caliber overrides
        // scope.
        assertThat(view)
                .contains("'足球通' AS caliber_name")
                .contains("vip_type_3 = '体育赛事'")
                .doesNotContain("vip_type_2 =")
                .contains("province_name = '全国'")
                .contains("vip_type_3 IN ('钻石会员', '黄金会员')")
                .contains("channel_type = '公网'")
                .contains("待业务确认：钻石与黄金的合并方式")
                .contains("数值为 vip_type_3 取 钻石会员、黄金会员 各行相加")
                .contains("SUBSTR(CAST(period_id AS VARCHAR), 5, 2)")
                .contains("UNION ALL")
                .contains("generated_by: calibers/vip_m.yml");
        assertThat(view.split("channel_type = '全部'", -1)).hasSize(2);
        assertThat(compiled.cube())
                .contains("base_object: vip_scale_caliber")
                .contains("SUM(cnt_zd)")
                .contains("non_additive_dimension: caliber")
                .contains("足球通会员 指 足球通")
                .contains("不适用：收入");
    }

    @Test
    void generatedCubeMembersNeverReuseTheirColumnName() {
        var compiled = CaliberCompiler.compile(root, "calibers/vip_m.yml", LIST);
        var cube =
                new MdlWorkspaceReader.WorkspaceCube(
                        "vip_scale_metrics",
                        "vip_scale_caliber",
                        "",
                        java.util.List.of(
                                new MdlWorkspaceReader.WorkspaceMember(
                                        "members", "SUM(cnt_zd)", "BIGINT", "")),
                        java.util.List.of(
                                new MdlWorkspaceReader.WorkspaceMember(
                                        "caliber", "caliber_name", "VARCHAR", ""),
                                new MdlWorkspaceReader.WorkspaceMember(
                                        "caliber_system", "caliber_group", "VARCHAR", ""),
                                new MdlWorkspaceReader.WorkspaceMember(
                                        "caliber_rule", "caliber_condition", "VARCHAR", "")),
                        java.util.List.of(
                                new MdlWorkspaceReader.WorkspaceMember(
                                        "period", "period_date", "DATE", "")),
                        compiled.cubePath());
        assertThat(MdlBuildChecks.selfReferences(cube)).isEmpty();
        assertThat(compiled.cube()).contains("name: caliber_system", "expression: caliber_group");
    }

    @Test
    void unknownColumnsMissingModelsAndBadStructureAreReported() {
        assertThat(
                        CaliberCompiler.compile(
                                        root,
                                        "calibers/x.yml",
                                        LIST.replace("vip_type_6", "vip_type_9"))
                                .errors())
                .anyMatch(e -> e.contains("vip_type_9") && e.contains("不在模型 vip_m"));
        assertThat(
                        CaliberCompiler.compile(
                                        root,
                                        "calibers/x.yml",
                                        LIST.replace("model: vip_m", "model: nope"))
                                .errors())
                .anyMatch(e -> e.contains("模型 nope 不存在"));
        assertThat(
                        CaliberCompiler.compile(
                                        root,
                                        "calibers/x.yml",
                                        LIST.replace("name: members", "name: cnt_zd"))
                                .errors())
                .anyMatch(e -> e.contains("不能与列名相同"));
        assertThat(
                        CaliberCompiler.compile(
                                        root,
                                        "calibers/x.yml",
                                        LIST.replace("- name: 视频钻石会员", "- name: 足球通"))
                                .errors())
                .anyMatch(e -> e.contains("口径名重复"));
        assertThat(
                        CaliberCompiler.compile(
                                        root, "calibers/x.yml", LIST.replace("combine: sum", ""))
                                .errors())
                .anyMatch(e -> e.contains("会把这些行的数值相加") && e.contains("combine: sum"));
        // 2026-11-25: fields the format lacks are reported, not skipped (a scope "format" and a
        // measure "pending" were written in a confirmation turn and silently lost).
        assertThat(
                        CaliberCompiler.compile(
                                        root,
                                        "calibers/x.yml",
                                        LIST.replace(
                                                "name: members", "name: members, pending: 待确认"))
                                .errors())
                .anyMatch(
                        e ->
                                e.contains("度量 cnt_zd 不支持字段 pending")
                                        && e.contains("scenario.description"));
        assertThat(CaliberCompiler.compile(root, "calibers/x.yml", "model: [").errors())
                .anyMatch(e -> e.contains("不是合法的 YAML"));
        assertThat(CaliberCompiler.compile(root, "calibers/x.yml", "model: vip_m\n").errors())
                .anyMatch(e -> e.contains("scenario"))
                .anyMatch(e -> e.contains("calibers"));
    }

    @Test
    void syncWritesGeneratedFilesCheckDetectsHandEditsAndRemovalCleansUp() throws Exception {
        write("calibers/vip_m.yml", LIST);

        assertThat(CaliberCompiler.sync(root)).isEmpty();
        assertThat(CaliberCompiler.check(root)).isEmpty();
        Path view = root.resolve("views/vip_scale_caliber/metadata.yml");
        assertThat(view).exists();
        assertThat(CaliberCompiler.isGeneratedPath(root, "views/vip_scale_caliber/metadata.yml"))
                .isTrue();
        assertThat(CaliberCompiler.isGeneratedPath(root, "models/vip_m/metadata.yml")).isFalse();

        Files.writeString(view, Files.readString(view).replace("足球通", "篮球通"));
        assertThat(CaliberCompiler.check(root))
                .anyMatch(i -> i.message().contains("请修改口径清单而不是生成文件"));
        assertThat(CaliberCompiler.sync(root)).isEmpty();
        assertThat(CaliberCompiler.check(root)).isEmpty();

        Files.delete(root.resolve("calibers/vip_m.yml"));
        assertThat(CaliberCompiler.check(root)).anyMatch(i -> i.message().contains("已删除的口径清单"));
        CaliberCompiler.sync(root);
        assertThat(root.resolve("views/vip_scale_caliber")).doesNotExist();
        assertThat(root.resolve("cubes/vip_scale_metrics")).doesNotExist();
        assertThat(CaliberCompiler.check(root)).isEmpty();
    }

    @Test
    void handWrittenAssetsWithTheSameNameAreNeverOverwritten() throws Exception {
        write(
                "views/vip_scale_caliber/metadata.yml",
                "name: vip_scale_caliber\nstatement: SELECT 1\n");
        write("calibers/vip_m.yml", LIST);

        assertThat(CaliberCompiler.sync(root)).anyMatch(i -> i.message().contains("不是由口径清单生成"));
        assertThat(Files.readString(root.resolve("views/vip_scale_caliber/metadata.yml")))
                .contains("SELECT 1");
    }

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
