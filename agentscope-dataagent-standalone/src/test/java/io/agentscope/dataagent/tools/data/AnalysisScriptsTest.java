package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * spec102: runs the shipped analysis scripts with a local Python. Conditional: skipped when no
 * {@code python} with numpy and matplotlib is on the PATH (the sandbox image always has them).
 */
class AnalysisScriptsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int[] DAILY = {
        620, 1820, 1900, 1860, 1940, 1880, 720, 660, 2000, 2040, 3360, 980, 860, 890, 1020, 1100,
        1450, 2550
    };
    private static String python;
    @TempDir Path dir;

    @BeforeAll
    static void findPython() {
        for (String candidate : List.of("python3", "python")) {
            try {
                Process p =
                        new ProcessBuilder(candidate, "-c", "import numpy, matplotlib")
                                .redirectErrorStream(true)
                                .start();
                if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    python = candidate;
                    return;
                }
            } catch (Exception ignored) {
                // try the next name
            }
        }
    }

    private JsonNode run(String id, String csv, String params) throws Exception {
        Assumptions.assumeTrue(python != null, "no local python with numpy and matplotlib");
        Path root = AnalysisSkills.materialize(dir.resolve("skills"));
        Path input = dir.resolve("data.csv");
        Files.writeString(input, csv, StandardCharsets.UTF_8);
        // Windows command lines keep inner double quotes only when escaped; the sandbox is Linux.
        String arg =
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? params.replace("\"", "\\\"")
                        : params;
        ProcessBuilder builder =
                new ProcessBuilder(
                                python,
                                root.resolve(id + "/" + id + ".py").toString(),
                                "--input",
                                input.toString(),
                                "--params",
                                arg,
                                "--outdir",
                                dir.resolve("outputs").toString())
                        .redirectErrorStream(false);
        builder.environment().put("PYTHONUTF8", "1");
        Process p = builder.start();
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        String text = new String(out, StandardCharsets.UTF_8).strip();
        return JSON.readTree(text.substring(text.lastIndexOf("\n") + 1));
    }

    private static String daily(int days) {
        StringBuilder csv = new StringBuilder("日期,访问次数\n");
        LocalDate d = LocalDate.of(2026, 9, 20);
        for (int i = 0; i < days; i++)
            csv.append(d.plusDays(i)).append(',').append(DAILY[i]).append('\n');
        return csv.toString();
    }

    @Test
    void attributionSplitsTheChangeByTheDimensionWhoseMixShiftedMost() throws Exception {
        String csv =
                """
                期间,部门,项目,访问次数
                对比期,市场经营部,经营分析,2000
                对比期,市场经营部,渠道,1210
                对比期,省专协同运营中心,经营分析,2450
                对比期,平台运营中心,经营分析,1880
                对比期,其他部门,经营分析,4420
                当前期,市场经营部,经营分析,950
                当前期,市场经营部,渠道,640
                当前期,省专协同运营中心,经营分析,1470
                当前期,平台运营中心,经营分析,1370
                当前期,其他部门,经营分析,3982
                """;
        JsonNode r =
                run(
                        "attribution",
                        csv,
                        "{\"metric\":\"访问次数\",\"period\":\"期间\",\"current\":\"当前期\",\"base\":\"对比期\","
                            + "\"dimensions\":[\"部门\",\"项目\"]}");
        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(r.path("total").path("delta").asDouble()).isEqualTo(-3548.0);
        assertThat(r.path("dimension").asText()).isEqualTo("部门");
        assertThat(r.path("items").get(0).path("value").asText()).isEqualTo("市场经营部");
        assertThat(r.path("items").get(0).path("contribution").asDouble()).isEqualTo(0.4566);
        assertThat(r.path("checks").path("items_sum_to_total_delta").asBoolean()).isTrue();
        assertThat(r.path("drilldown").path("dimension").asText()).isEqualTo("项目");
        assertThat(dir.resolve(r.path("files").path("chart").asText())).isRegularFile();
    }

    /** Distinct counts are compared per dimension value and never split additively. */
    @Test
    void distinctMetricsAreComparedPerValueWithoutAnAdditiveSplit() throws Exception {
        String csv =
                """
                期间,维度,维度值,活跃用户数
                9月,合计,合计,1000
                10月,合计,合计,900
                9月,部门,市场经营部,400
                10月,部门,市场经营部,300
                9月,部门,平台运营中心,350
                10月,部门,平台运营中心,340
                9月,项目,经营分析,700
                10月,项目,经营分析,690
                9月,项目,渠道,500
                10月,项目,渠道,420
                """;
        String params =
                "{\"metric\":\"活跃用户数\",\"period\":\"期间\",\"current\":\"10月\",\"base\":\"9月\",\"additive\":false}";
        JsonNode r = run("attribution", csv, params);
        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(r.path("additive").asBoolean(true)).isFalse();
        // The total comes from the de-duplicated "合计" rows, not from adding departments.
        assertThat(r.path("total").path("delta").asDouble()).isEqualTo(-100.0);
        assertThat(r.path("dimension").asText()).isEqualTo("部门");
        assertThat(r.path("drilldown").isNull()).isTrue();
        assertThat(r.path("notes").toString()).contains("各项变化之和不等于总变化");

        JsonNode noTotal = run("attribution", csv.replace("合计", "全部"), params);
        assertThat(noTotal.path("ok").asBoolean()).isFalse();
        assertThat(noTotal.path("reason").asText()).contains("合计");
    }

    @Test
    void anomalyFindsTheSpikeAndTheHolidayDipsDeterministically() throws Exception {
        String params = "{\"date\":\"日期\",\"metric\":\"访问次数\"}";
        JsonNode first = run("anomaly", daily(18), params);
        List<String> found = new ArrayList<>();
        first.path("anomalies").forEach(a -> found.add(a.path("time").asText()));
        assertThat(found)
                .containsExactly(
                        "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-05", "2026-10-07");
        assertThat(run("anomaly", daily(18), params).path("anomalies"))
                .isEqualTo(first.path("anomalies"));
    }

    @Test
    void forecastRefusesShortHistoryAndOtherwiseGivesIntervals() throws Exception {
        String params = "{\"date\":\"日期\",\"metric\":\"访问次数\",\"horizon\":7}";
        JsonNode shortHistory = run("forecast", daily(9), params);
        assertThat(shortHistory.path("ok").asBoolean()).isFalse();
        assertThat(shortHistory.path("required").asInt()).isEqualTo(14);

        JsonNode r = run("forecast", daily(18), params);
        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(r.path("forecast")).hasSize(7);
        for (JsonNode p : r.path("forecast")) {
            assertThat(p.path("lo95").asDouble()).isLessThanOrEqualTo(p.path("lo80").asDouble());
            assertThat(p.path("hi95").asDouble()).isGreaterThanOrEqualTo(p.path("hi80").asDouble());
        }
        assertThat(run("forecast", daily(18), params).path("forecast"))
                .isEqualTo(r.path("forecast"));
    }
}
