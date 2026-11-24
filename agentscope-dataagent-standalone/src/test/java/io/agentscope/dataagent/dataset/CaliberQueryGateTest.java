package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** spec149 P4: caliber Cubes refuse queries that would add overlapping calibers or guess names. */
class CaliberQueryGateTest {
    @TempDir Path published;

    @BeforeEach
    void publish() throws Exception {
        write(
                "models/vip_m/metadata.yml",
                "name: vip_m\n"
                        + "columns:\n"
                        + "  - {name: cnt_zd, type: BIGINT}\n"
                        + "  - {name: vip_type_6, type: VARCHAR}\n");
        write(
                "calibers/vip.yml",
                """
                model: vip_m
                name: vip
                scenario:
                  subject: 会员规模
                  use_for: [会员数]
                  not_for: [收入]
                  measures:
                    - {column: cnt_zd, name: members}
                calibers:
                  - {name: 体育会员, where: {vip_type_6: 全部}}
                  - {name: 足球通, synonyms: [足球通会员], where: {vip_type_6: 足球通会员}}
                  - {name: 篮球通, where: {vip_type_6: 篮球通会员}}
                """);
        assertThat(CaliberCompiler.sync(published)).isEmpty();
        write("cubes/manual/metadata.yml", "name: manual\nbase_object: vip_m\n");
    }

    @Test
    void namedOrGroupedCaliberQueriesPass() {
        assertThat(CaliberQueryGate.check(published, args(List.of("caliber:in:足球通"), List.of())))
                .isNull();
        assertThat(
                        CaliberQueryGate.check(
                                published, args(List.of("caliber:in:足球通,篮球通"), List.of("caliber"))))
                .isNull();
        assertThat(CaliberQueryGate.check(published, args(List.of(), List.of("caliber")))).isNull();
        assertThat(
                        CaliberQueryGate.check(
                                published, Map.of("cube", "manual", "measures", List.of("x"))))
                .isNull();
    }

    @Test
    void addingAcrossCalibersIsRefused() {
        assertThat(CaliberQueryGate.check(published, args(List.of(), List.of())))
                .contains("不能跨口径相加", "caliber:in:口径名", "体育会员、足球通、篮球通");
        assertThat(
                        CaliberQueryGate.check(
                                published, args(List.of("caliber:not_in:足球通"), List.of())))
                .contains("不能用 not_in");
    }

    @Test
    void unknownNamesAreRefusedWithTheNearestCalibersAndAnAskFirstRule() {
        assertThat(CaliberQueryGate.check(published, args(List.of("caliber:in:足球会员"), List.of())))
                .contains("没有口径 足球会员", "可能对应：足球通、体育会员", "先向用户确认");
        assertThat(CaliberQueryGate.check(published, args(List.of("caliber:in:足球通会员"), List.of())))
                .contains("「足球通会员」是口径「足球通」的说法");
        assertThat(CaliberQueryGate.check(published, args(List.of("caliber:in:橄榄"), List.of())))
                .contains("没有相近的口径");
    }

    @Test
    void generatedSelectionRulesFollowTheListsAndRefuseHandEdits() throws Exception {
        Path rules = published.resolve(CaliberCompiler.RULES_FILE);
        assertThat(Files.readString(rules))
                .contains(
                        "## Canonical tables",
                        "| 未写 | 会员规模 | vip_metrics |",
                        "- vip_metrics：会员规模；适用于会员数；不适用于收入。",
                        "先向用户确认");
        assertThat(CaliberCompiler.check(published)).isEmpty();
        Files.writeString(rules, "改过了");
        assertThat(CaliberCompiler.check(published))
                .anyMatch(i -> i.message().contains(CaliberCompiler.RULES_FILE));
        Files.delete(published.resolve("calibers/vip.yml"));
        CaliberCompiler.sync(published);
        assertThat(rules).doesNotExist();
    }

    private static Map<String, Object> args(List<String> filters, List<String> dimensions) {
        return Map.of(
                "cube",
                "vip_metrics",
                "measures",
                List.of("members"),
                "filters",
                filters,
                "dimensions",
                dimensions);
    }

    private void write(String relative, String content) throws Exception {
        Path file = published.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
