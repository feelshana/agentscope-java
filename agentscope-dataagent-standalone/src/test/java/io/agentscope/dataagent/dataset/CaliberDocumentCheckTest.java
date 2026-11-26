package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** spec149 P7-3: a document draft copies its documents and uses every column they name. */
class CaliberDocumentCheckTest {

    private static final String DOC =
            """
            适用模型：`vip_m`
            | 列名 | 指标名 | 用户常见说法 |
            | `cnt_zd` | 主动订购数 | 会员数 |
            全国、全渠道的范围对应 `province_name='全国'`、`channel_type='全部'`。
            业务：咪咕视频。统计对象：会员。
            """;

    private static final Set<String> COLUMNS =
            Set.of("cnt_zd", "province_name", "channel_type", "vip_type_6", "period_id");

    private static CaliberList list(String measure, String scope) {
        return CaliberList.parse(
                        "calibers/vip.yml",
                        """
                        model: vip_m
                        name: vip
                        scenario:
                          subject: 会员
                          business: 咪咕视频
                          audience: 会员
                          time: {column: period_id, grain: month, format: yyyymm}
                          measures:
                            - %s
                          scope:
                        %s
                        calibers:
                          - {name: 足球通, where: {vip_type_6: 足球通会员}}
                        """
                                .formatted(measure, scope))
                .list();
    }

    @Test
    void aDraftThatCopiesTheDocumentPasses() {
        var copied =
                list(
                        "{column: cnt_zd, name: members, label: 主动订购数, synonyms: [会员数]}",
                        "    - {column: province_name, name: province, total: 全国, default: 全国}\n"
                                + "    - {column: channel_type, name: channel, total: 全部, default:"
                                + " 全部}");
        assertThat(CaliberDocumentCheck.problems(copied, DOC, COLUMNS)).isEmpty();
    }

    @Test
    void inventedWordsAndDroppedColumnsAreReported() {
        var invented =
                list(
                        "{column: cnt_zd, name: members, label: 总订单数, unit: 笔, synonyms: [用户规模]}",
                        "    - {column: province_name, name: province, total: 全国, default: 全国}");
        assertThat(CaliberDocumentCheck.problems(invented, DOC, COLUMNS))
                .anyMatch(
                        p ->
                                p.contains("label「总订单数」")
                                        && p.contains("unit「笔」")
                                        && p.contains("说法「用户规模」"))
                .anyMatch(
                        p ->
                                p.contains("channel_type")
                                        && p.contains("没有出现在口径条件、scope 或 fixed_scope 中"));
        // A label equal to the column is how an undefined metric is written, so it passes.
        var undefined =
                list(
                        "{column: cnt_zd, name: members, label: cnt_zd}",
                        "    - {column: province_name, name: province, total: 全国, default: 全国}\n"
                                + "    - {column: channel_type, name: channel, total: 全部, default:"
                                + " 全部}");
        assertThat(CaliberDocumentCheck.problems(undefined, DOC, COLUMNS)).isEmpty();
    }
}
