package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporalConditionTest {
    @TempDir Path project;

    @Test
    void unpaddedTextIsParsedWithTheSourcesOwnFunction() {
        // 2026-10-22: visit_time "2026/10/7 9:05" compared as a string mixes in 10th–19th.
        assertThat(TemporalCondition.of("visit_time", "uuuu/M/d H:mm", "VARCHAR", "mysql"))
                .isEqualTo(
                        "STR_TO_DATE(visit_time, '%Y/%c/%e %k:%i') >= '2026-10-01 00:00:00' AND"
                            + " STR_TO_DATE(visit_time, '%Y/%c/%e %k:%i') < '2026-11-01 00:00:00'");
        assertThat(TemporalCondition.of("d", "uuuu-M-d", "TEXT", "postgres"))
                .isEqualTo(
                        "TO_TIMESTAMP(d, 'YYYY-FMMM-FMDD') >= '2026-10-01' AND"
                                + " TO_TIMESTAMP(d, 'YYYY-FMMM-FMDD') < '2026-11-01'");
        assertThat(TemporalCondition.of("d", "uuuu-M-d", "TEXT", "bigquery")).isNull();
    }

    @Test
    void paddedNativeAndNumericColumnsCompareDirectlyAndNeverUseLike() {
        assertThat(TemporalCondition.of("t", "uuuu-MM-dd HH:mm:ss", "VARCHAR", "mysql"))
                .isEqualTo("t >= '2026-10-01 00:00:00' AND t < '2026-11-01 00:00:00'");
        assertThat(TemporalCondition.of("t", "NATIVE", "DATETIME", "mysql"))
                .isEqualTo("t >= '2026-10-01' AND t < '2026-11-01'");
        assertThat(TemporalCondition.of("dt", "uuuuMMdd", "INT", "mysql"))
                .isEqualTo("dt >= 20261001 AND dt < 20261101");
        assertThat(TemporalCondition.of("dt", "uuuuMMdd", "VARCHAR", "mysql"))
                .isEqualTo("dt >= '20261001' AND dt < '20261101'");
        assertThat(TemporalCondition.of("x", "not a pattern {", "VARCHAR", "mysql")).isNull();
        assertThat(TemporalCondition.padded("uuuu/M/d")).isFalse();
        assertThat(TemporalCondition.padded("uuuu-MM-dd'T'HH:mm")).isTrue();
    }

    @Test
    void describeUsesTheSampledFormatOfRegisteredTimeColumnsOnly() throws Exception {
        Files.writeString(project.resolve("wren_project.yml"), "name: kb\ndata_source: mysql\n");
        Path model = project.resolve("models/report_visit/metadata.yml");
        Files.createDirectories(model.getParent());
        Files.writeString(
                model,
                "name: report_visit\ncolumns:\n- name: visit_time\n  type: VARCHAR\n"
                        + "- name: created\n  type: VARCHAR\nproperties:\n  temporal_columns:\n"
                        + "  - column: visit_time\n    format: uuuu/M/d H:mm\n"
                        + "  - column: created\n    format: uuuu-M-d\n");
        String text =
                TemporalCondition.describe(
                        project,
                        List.of(new SqlWindowGate.Rule("report_visit", List.of("visit_time"), 31)));
        assertThat(text)
                .contains("report_visit.visit_time（格式 uuuu/M/d H:mm）", "STR_TO_DATE(visit_time")
                .doesNotContain("created");
        assertThat(TemporalCondition.describe(project, List.of())).isEmpty();
        assertThat(
                        TemporalCondition.describe(
                                project,
                                List.of(new SqlWindowGate.Rule("other", List.of("t"), 31))))
                .isEmpty();
    }
}
