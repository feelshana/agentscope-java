package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublishedAssetWindowsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<PublishedAssetWindows.Fact> FACTS =
            List.of(
                    new PublishedAssetWindows.Fact("orders", "ods_orders", 30),
                    new PublishedAssetWindows.Fact("visits", "ods_visits", 7));

    private JsonNode manifest(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void viewOverLimitedModelInheritsTheTightestWindowOfWhatItReads() throws Exception {
        var manifest =
                manifest(
                        """
                        {"models":[{"name":"orders","tableReference":{"table":"ods_orders"}},
                                   {"name":"visits","tableReference":{"table":"ods_visits"}},
                                   {"name":"regions","tableReference":{"table":"dim_region"}}],
                         "views":[{"name":"sales_valid","statement":
                           "SELECT o.day FROM orders o JOIN visits v ON o.id = v.id JOIN regions r ON 1=1"}]}
                        """);
        var derived =
                PublishedAssetWindows.derive(
                        manifest, FACTS, Map.of("sales_valid", List.of("day")));
        assertThat(derived.rules())
                .containsExactly(new SqlWindowGate.Rule("sales_valid", List.of("day"), 7));
        assertThat(derived.unconfigured()).isEmpty();
    }

    @Test
    void refSqlMatchesThePhysicalTableEvenWhenFullyQualified() throws Exception {
        var manifest =
                manifest(
                        """
                        {"models":[{"name":"orders_clean","refSql":"SELECT * FROM dw.ods_orders"}],"views":[]}
                        """);
        var derived =
                PublishedAssetWindows.derive(
                        manifest, FACTS, Map.of("orders_clean", List.of("day")));
        assertThat(derived.rules())
                .containsExactly(new SqlWindowGate.Rule("orders_clean", List.of("day"), 30));
    }

    @Test
    void viewOverRefSqlModelInheritsThroughTheModel() throws Exception {
        var manifest =
                manifest(
                        """
                        {"models":[{"name":"orders_clean","refSql":"SELECT * FROM dw.ods_visits"}],
                         "views":[{"name":"daily","statement":"SELECT * FROM orders_clean"}]}
                        """);
        var derived =
                PublishedAssetWindows.derive(
                        manifest,
                        FACTS,
                        Map.of("orders_clean", List.of("day"), "daily", List.of("day")));
        assertThat(derived.rules())
                .extracting(SqlWindowGate.Rule::table, SqlWindowGate.Rule::maxWindowDays)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("orders_clean", 7),
                        org.assertj.core.groups.Tuple.tuple("daily", 7));
    }

    @Test
    void assetWithoutConfirmedTimeColumnIsReportedNotGuessed() throws Exception {
        var manifest =
                manifest(
                        """
                        {"models":[{"name":"orders","tableReference":{"table":"ods_orders"}}],
                         "views":[{"name":"totals","statement":"SELECT COUNT(*) FROM orders"}]}
                        """);
        var derived = PublishedAssetWindows.derive(manifest, FACTS, Map.of());
        assertThat(derived.rules()).isEmpty();
        assertThat(derived.unconfigured()).containsExactly("totals");
    }

    @Test
    void assetsOutsideLimitedTablesAreLeftAlone() throws Exception {
        var manifest =
                manifest(
                        """
                        {"models":[{"name":"regions","tableReference":{"table":"dim_region"}}],
                         "views":[{"name":"r","statement":"SELECT 'orders' FROM regions -- orders"}]}
                        """);
        var derived = PublishedAssetWindows.derive(manifest, FACTS, Map.of());
        assertThat(derived.rules()).isEmpty();
        assertThat(derived.unconfigured()).isEmpty();
    }
}
