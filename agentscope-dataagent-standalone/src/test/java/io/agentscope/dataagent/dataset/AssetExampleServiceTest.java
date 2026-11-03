package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Examples on the asset page stay inside the knowledge base's own model (spec107). */
class AssetExampleServiceTest {
    private static final List<MdlWorkspaceReader.WorkspaceModel> MODELS =
            List.of(model("报表访问明细", "ds_a_data"), model("用户列表", "ds_b_data"));
    private static final List<MdlWorkspaceReader.WorkspaceRelation> RELATIONS =
            List.of(
                    new MdlWorkspaceReader.WorkspaceRelation(
                            "r",
                            "报表访问明细",
                            "用户列表",
                            "MANY_TO_ONE",
                            "报表访问明细.user_account = 用户列表.account",
                            List.of(),
                            List.of(),
                            ""));

    private static MdlWorkspaceReader.WorkspaceModel model(String name, String table) {
        return new MdlWorkspaceReader.WorkspaceModel(
                name, "d", name, "data_agent", table, "", List.of(), "", null, null, null, null);
    }

    @Test
    void examplesNamingTheKnowledgeBaseModelsAreKept() {
        String raw =
                "{\"filter\":\"统计「报表访问明细」时默认只算资源类型为报表的记录，明确要求时才包含仪表盘。\","
                    + "\"join\":\"把「报表访问明细」和「用户列表」拼在一起，按公司、部门分析访问时直接用。\",\"sql\":\"SELECT"
                    + " u.company, COUNT(*) FROM data_agent.ds_a_data a JOIN data_agent.ds_b_data u"
                    + " ON a.user_account = u.account GROUP BY u.company\"}";
        var examples = AssetExampleService.accept(raw, MODELS, RELATIONS);
        // ADR0126: a default filter is a rule, never an asset example.
        assertThat(examples)
                .extracting(AssetExampleService.Example::kind)
                .containsExactly("查询模板", "已有 SQL");
        assertThat(examples.get(1).text()).startsWith(AssetExampleService.SQL_LEAD);
    }

    /** The user's complaint: examples about orders and refunds on a report-visit knowledge base. */
    @Test
    void examplesAboutOtherTablesAreDropped() {
        String raw =
                "{\"filter\":\"统计订单时默认排除测试账号和已退款的订单。\","
                        + "\"join\":\"把订单明细和客户的区域拼在一起。\","
                        + "\"sql\":\"SELECT COUNT(*) FROM shop.orders\"}";
        var examples = AssetExampleService.accept(raw, MODELS, RELATIONS);
        // Only the platform's join over the declared relationship remains.
        assertThat(examples).hasSize(1);
        assertThat(examples.get(0).kind()).isEqualTo("查询模板");
        assertThat(examples.get(0).text()).contains("「报表访问明细」", "「用户列表」");
        // Without a relationship and without a model answer there is nothing to show.
        assertThat(AssetExampleService.accept(null, MODELS, List.of())).isEmpty();
    }

    @Test
    void sqlMayReadOnlyKnowledgeBaseTablesAndItsOwnCtes() {
        assertThat(
                        AssetExampleService.readsOnly(
                                "WITH v AS (SELECT * FROM `data_agent`.`ds_a_data`) SELECT * FROM"
                                        + " v",
                                MODELS))
                .isTrue();
        assertThat(AssetExampleService.readsOnly("SELECT * FROM ds_b_data", MODELS)).isTrue();
        assertThat(
                        AssetExampleService.readsOnly(
                                "SELECT * FROM data_agent.ds_a_data JOIN other.t ON 1=1", MODELS))
                .isFalse();
        assertThat(AssetExampleService.readsOnly("DELETE FROM data_agent.ds_a_data", MODELS))
                .isFalse();
        assertThat(AssetExampleService.readsOnly("SELECT 1", MODELS)).isFalse();
    }
}
