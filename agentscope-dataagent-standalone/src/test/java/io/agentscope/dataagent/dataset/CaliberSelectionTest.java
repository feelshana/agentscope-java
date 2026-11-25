package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * spec149 P5: tables are chosen by business x audience, a question naming only the audience uses
 * the default measure, and scope dimensions (province, channel) stay columns that every query
 * filters or groups.
 */
class CaliberSelectionTest {
    @TempDir Path root;

    static final String VIP =
            """
            model: vip_m
            name: vip
            scenario:
              subject: 咪咕视频会员规模
              business: {name: 咪咕视频, synonyms: [视频]}
              audience: {name: 会员, synonyms: [会员数, 订购用户]}
              time: {column: period_id, grain: month, format: yyyymm}
              measures:
                - {column: cnt_zd, name: members, label: 主动订购数, synonyms: [会员数, 用户数], default: true}
                - {column: cnt_xz, name: new_members, label: 新增数}
              scope:
                - {column: province_name, name: province, label: 省份, total: 全国, default: 全国, values: [全国, 浙江, 广东]}
                - {column: channel_type, name: channel, label: 渠道, total: 全部, default: 全部}
              fixed_scope: {pay_type1: 全部}
              use_for: [会员数]
              not_for: [活跃用户]
            calibers:
              - {name: 篮球通, where: {vip_type_6: 篮球通会员}}
              - {name: 足球通, where: {vip_type_6: 足球通会员}}
            """;

    static final String ACTIVE =
            """
            model: active_d
            name: active
            scenario:
              subject: 咪咕视频活跃用户
              business: 咪咕视频
              audience: {name: 活跃用户, synonyms: [日活]}
              time: {column: stat_date, grain: day, format: yyyymmdd}
              measures:
                - {column: dau, name: dau_total, label: 日活跃用户数, default: true}
            calibers:
              - {name: 全部用户, where: {user_type: 全部}}
            """;

    @BeforeEach
    void models() throws Exception {
        write(
                "models/vip_m/metadata.yml",
                """
                name: vip_m
                columns:
                  - {name: period_id, type: INTEGER}
                  - {name: cnt_zd, type: BIGINT}
                  - {name: cnt_xz, type: BIGINT}
                  - {name: province_name, type: VARCHAR}
                  - {name: channel_type, type: VARCHAR}
                  - {name: pay_type1, type: VARCHAR}
                  - {name: vip_type_6, type: VARCHAR}
                """);
        write(
                "models/active_d/metadata.yml",
                """
                name: active_d
                columns:
                  - {name: stat_date, type: INTEGER}
                  - {name: dau, type: BIGINT}
                  - {name: user_type, type: VARCHAR}
                """);
    }

    @Test
    void scopeDimensionsStayColumnsAndBecomeCubeDimensions() {
        var compiled = CaliberCompiler.compile(root, "calibers/vip.yml", VIP);
        assertThat(compiled.errors()).isEmpty();
        assertThat(compiled.view())
                .contains("province_name AS province_name", "channel_type AS channel_type")
                .contains("pay_type1 = '全部'")
                .doesNotContain("province_name = ", "channel_type = ");
        assertThat(compiled.cube().replaceAll("\\s+", " "))
                .contains("name: province", "expression: province_name")
                .contains("全国 为汇总行", "province:eq:全国", "可选取值：全国、浙江、广东")
                .contains("name: channel", "用户没说时取 全部")
                .contains("默认指标：用户只说统计对象", "常见说法：会员数、用户数")
                .contains("业务：咪咕视频、视频；统计对象：会员、会员数、订购用户");
        assertThat(CaliberCompiler.defaultFilters(compiled.list()))
                .containsExactly("province:eq:全国", "channel:eq:全部");
    }

    @Test
    void badScopeAndDefaultsAreReported() {
        String bad =
                VIP.replace("name: province,", "name: province_name,")
                        .replace(", default: 全部}", "}")
                        .replace(
                                "name: new_members, label: 新增数}",
                                "name: new_members, default: true}")
                        .replace("fixed_scope: {pay_type1: 全部}", "fixed_scope: {channel_type: 全部}")
                        .replace("values: [全国, 浙江, 广东]", "values: [浙江]");
        assertThat(CaliberList.parse("calibers/vip.yml", bad).errors())
                .anyMatch(e -> e.contains("name 不能与列名相同"))
                .anyMatch(e -> e.contains("要写 default"))
                .anyMatch(e -> e.contains("只能有一个 default"))
                .anyMatch(e -> e.contains("不能同时写在 fixed_scope 和 scope"))
                .anyMatch(e -> e.contains("default 不在 values"));
        var onScope =
                CaliberCompiler.compile(
                        root,
                        "calibers/vip.yml",
                        VIP.replace(
                                "{vip_type_6: 篮球通会员}", "{vip_type_6: 篮球通会员, channel_type: 公网}"));
        assertThat(onScope.errors()).anyMatch(e -> e.contains("已是范围维度"));
    }

    @Test
    void selectionIndexListsBusinessAudienceDefaultsAndForbidsRawSql() throws Exception {
        write("calibers/vip.yml", VIP);
        write("calibers/active.yml", ACTIVE);
        assertThat(CaliberCompiler.sync(root)).isEmpty();
        String rules = Files.readString(root.resolve(CaliberCompiler.RULES_FILE));
        assertThat(rules)
                .contains("| 咪咕视频（视频） | 会员（会员数、订购用户） | vip_metrics | members（主动订购数） | 月 |")
                .contains("pay_type1=全部（固定）；province:eq:全国；channel:eq:全部")
                .contains("| 咪咕视频 | 活跃用户（日活） | active_metrics | dau_total（日活跃用户数） | 日 |")
                .contains("例如只说“用户数”），先问是哪一个", "说明当前没有这类数据", "分别列出各口径的数值并说明不能相加，不给出合计")
                .contains("不要对这些指标的原表或生成的口径视图（active_d、active_caliber、vip_m、vip_caliber）用 run_sql")
                .contains("- vip_metrics：咪咕视频会员规模；适用于会员数；不适用于活跃用户。");
        assertThat(CaliberCompiler.check(root)).isEmpty();
        assertThat(CaliberCompiler.indexWarnings(root)).isEmpty();
    }

    @Test
    void indistinguishableTablesAndCollidingWordsAreWarned() throws Exception {
        write("calibers/vip.yml", VIP);
        write(
                "calibers/active.yml",
                ACTIVE.replace("audience: {name: 活跃用户, synonyms: [日活]}", "audience: 订购用户")
                        .replace("grain: day, format: yyyymmdd", "grain: month, format: yyyymm")
                        .replace("{name: 全部用户,", "{name: 视频,"));
        CaliberCompiler.sync(root);
        assertThat(CaliberCompiler.ruleWarnings(root))
                .anyMatch(i -> i.message().contains("业务和统计对象相同"))
                .anyMatch(i -> i.message().contains("选表说法「视频」也是 active 的口径「视频」"));
        write("calibers/active.yml", ACTIVE.replace("  business: 咪咕视频\n", ""));
        CaliberCompiler.sync(root);
        assertThat(CaliberCompiler.indexWarnings(root))
                .anyMatch(i -> i.message().contains("没写 scenario.business"));
    }

    @Test
    void queriesFilterOrGroupEveryScopeDimensionWithListedValues() throws Exception {
        write("calibers/vip.yml", VIP);
        CaliberCompiler.sync(root);
        List<String> scope = List.of("province:eq:全国", "channel:eq:全部");
        assertThat(
                        CaliberQueryGate.check(
                                root, args(List.of("caliber:in:篮球通", "province:eq:浙江"), List.of())))
                .contains("每个都要指定取值或分组", "channel:eq:全部")
                .doesNotContain("province:eq:全国");
        assertThat(CaliberQueryGate.check(root, args(concat("caliber:in:篮球通", scope), List.of())))
                .isNull();
        assertThat(
                        CaliberQueryGate.check(
                                root,
                                args(
                                        List.of("caliber:in:篮球通", "channel:eq:全部"),
                                        List.of("province"))))
                .isNull();
        assertThat(
                        CaliberQueryGate.check(
                                root,
                                args(
                                        List.of(
                                                "caliber:in:篮球通",
                                                "province:eq:浙江省",
                                                "channel:eq:全部"),
                                        List.of())))
                .contains("没有取值「浙江省」", "可能对应：浙江");
        assertThat(
                        CaliberQueryGate.check(
                                root,
                                args(
                                        List.of(
                                                "caliber:in:篮球通",
                                                "province:not_in:全国",
                                                "channel:eq:全部"),
                                        List.of())))
                .contains("不能用 not_in");
        // Channel lists no values, so any channel is left to the engine.
        assertThat(
                        CaliberQueryGate.check(
                                root,
                                args(
                                        List.of(
                                                "caliber:in:篮球通",
                                                "province:eq:浙江",
                                                "channel:eq:渠道1"),
                                        List.of())))
                .isNull();
    }

    @Test
    void rawSqlOnACaliberTableOrItsGeneratedViewIsRefusedButOtherTablesAreNot() throws Exception {
        assertThat(CaliberQueryGate.checkSql(root, "SELECT * FROM vip_m")).isNull();
        write("calibers/vip.yml", VIP);
        CaliberCompiler.sync(root);
        assertThat(
                        CaliberQueryGate.checkSql(
                                root, "SELECT SUM(cnt_zd) FROM `vip_m` WHERE vip_type_6 = '足球'"))
                .contains("请用指标 vip_metrics 的 query_cube");
        assertThat(CaliberQueryGate.checkSql(root, "select * from VIP_M")).isNotNull();
        // The generated View holds overlapping calibers and summary rows as well.
        assertThat(CaliberQueryGate.checkSql(root, "SELECT caliber_name FROM vip_caliber"))
                .contains("vip_caliber", "请用指标 vip_metrics 的 query_cube");
        assertThat(CaliberQueryGate.checkSql(root, "SELECT * FROM vip_caliber_extra")).isNull();
        assertThat(CaliberQueryGate.checkSql(root, "SELECT 'vip_m' AS t FROM orders -- vip_m"))
                .isNull();
        assertThat(CaliberQueryGate.checkSql(root, "SELECT * FROM orders /* vip_m */")).isNull();
    }

    /**
     * spec149 P7: rows are finished statistics. A day table is grouped by day, never summed into
     * weeks or months, and a result that met several rows (empty measure) is refused.
     */
    @Test
    void finishedStatisticsAreReadOneRowPerResultAtTheTableGrain() throws Exception {
        write("calibers/active.yml", ACTIVE);
        CaliberCompiler.sync(root);
        java.util.function.Function<Object, Map<String, Object>> query =
                time -> {
                    Map<String, Object> a = new java.util.LinkedHashMap<>();
                    a.put("cube", "active_metrics");
                    a.put("measures", List.of("dau_total"));
                    a.put("filters", List.of("caliber:in:全部用户"));
                    if (time != null) a.put("time_dimension", time);
                    return a;
                };
        assertThat(CaliberQueryGate.check(root, query.apply(null)))
                .contains("必须写 time_dimension", "period:day:2026-10-01,2026-10-02");
        assertThat(CaliberQueryGate.check(root, query.apply("period:month:2026-10-01,2026-11-01")))
                .contains("只能写 period:day", "多日的值相加", "文档没定义时先问用户");
        assertThat(CaliberQueryGate.check(root, query.apply("period:week:2026-10-01,2026-10-08")))
                .contains("只能写 period:day");
        // A week asked for is listed day by day: seven results, each one row.
        assertThat(CaliberQueryGate.check(root, query.apply("period:day:2026-10-01,2026-10-08")))
                .isNull();

        Map<String, Object> week = query.apply("period:day:2026-10-01,2026-10-08");
        assertThat(
                        CaliberQueryGate.checkResult(
                                root,
                                week,
                                "{\"columns\":[\"period__day\",\"dau_total\"],\"rows\":[[\"2026-10-01\",120],[\"2026-10-02\",118]]}"))
                .isNull();
        assertThat(
                        CaliberQueryGate.checkResult(
                                root,
                                week,
                                "{\"columns\":[\"period__day\",\"dau_total\"],\"rows\":[[\"2026-10-01\",null],[\"2026-10-02\",118]]}"))
                .contains("1 个结果为空", "对应了多行", "不要把空值当作 0");
        // Object rows (uncompacted) and an empty result (no data) are handled alike.
        assertThat(
                        CaliberQueryGate.checkResult(
                                root,
                                week,
                                "{\"rows\":[{\"period__day\":\"2026-10-01\",\"dau_total\":null}]}"))
                .contains("1 个结果为空");
        assertThat(CaliberQueryGate.checkResult(root, week, "{\"columns\":[],\"rows\":[]}"))
                .isNull();
        // Cubes not generated from a list are not checked.
        Map<String, Object> other = new java.util.LinkedHashMap<>(week);
        other.put("cube", "hand_written");
        assertThat(
                        CaliberQueryGate.checkResult(
                                root, other, "{\"columns\":[\"dau_total\"],\"rows\":[[null]]}"))
                .isNull();
        assertThat(CaliberCompiler.rules(root))
                .contains("time_dimension 的粒度必须等于上表“时间”列", "不把每天的值相加当作周活、月活");
    }

    @Test
    void verificationQueriesReadTheDefaultMeasureAtDefaultScope() {
        CaliberList list = CaliberList.parse("calibers/vip.yml", VIP).list();
        CaliberList.Caliber basketball = list.calibers().get(0);
        assertThat(CaliberService.query(list, basketball))
                .containsSubsequence("--measures", "members")
                .containsSubsequence("--filter", "province:eq:全国")
                .containsSubsequence("--filter", "channel:eq:全部")
                .containsSubsequence("--filter", "caliber:in:篮球通");
        assertThat(CaliberService.question(list, basketball)).isEqualTo("篮球通各月的主动订购数（省份全国、渠道全部）");
        assertThat(
                        CaliberQueryGate.check(
                                root,
                                Map.of(
                                        "cube",
                                        "vip_metrics",
                                        "measures",
                                        List.of("members"),
                                        "filters",
                                        List.of(
                                                "province:eq:全国",
                                                "channel:eq:全部",
                                                "caliber:in:篮球通"))))
                .isNull();
    }

    /** Lists written before P5 keep the hash their reviewers signed. */
    @Test
    void scenarioHashOfAListWithoutP5FieldsIsUnchanged() throws Exception {
        String old =
                """
                model: vip_m
                name: vip
                scenario:
                  subject: 会员规模
                  time: {column: period_id, grain: month, format: yyyymm}
                  measures:
                    - {column: cnt_zd, name: members, label: 会员数}
                  fixed_scope: {province_name: 全国}
                  use_for: [会员数]
                calibers:
                  - {name: 足球通, where: {vip_type_6: 足球通会员}}
                """;
        CaliberList list = CaliberList.parse("calibers/vip.yml", old).list();
        record OldMeasure(String column, String name, String label, String description) {}
        record OldScenario(
                String subject,
                String description,
                CaliberList.Time time,
                List<OldMeasure> measures,
                Map<String, String> fixedScope,
                List<String> useFor,
                List<String> notFor) {}
        var s = list.scenario();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("model", list.model());
        before.put("name", list.name());
        before.put(
                "scenario",
                new OldScenario(
                        s.subject(),
                        s.description(),
                        s.time(),
                        s.measures().stream()
                                .map(
                                        m ->
                                                new OldMeasure(
                                                        m.column(),
                                                        m.name(),
                                                        m.label(),
                                                        m.description()))
                                .toList(),
                        s.fixedScope(),
                        s.useFor(),
                        s.notFor()));
        before.put("unrestricted", list.unrestricted());
        assertThat(CaliberReviews.scenarioHash(list))
                .isEqualTo(CaliberSources.sha(new ObjectMapper().writeValueAsString(before)));
        assertThat(CaliberReviews.scenarioHash(CaliberList.parse("v", VIP).list()))
                .isNotEqualTo(CaliberReviews.scenarioHash(list));
    }

    private static List<String> concat(String first, List<String> rest) {
        List<String> out = new java.util.ArrayList<>(List.of(first));
        out.addAll(rest);
        return out;
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
                dimensions,
                "time_dimension",
                "period:month:2026-10-01,2026-11-01");
    }

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
