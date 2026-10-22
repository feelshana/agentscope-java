package io.agentscope.dataagent.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmptyOrdersQueryFixtureTest {
    @Test
    void emptyTableAggregateHasOneRowWithZeroCountAndNullRevenue() throws Exception {
        var rows =
                EmptyOrdersQueryFixture.query(
                        "SELECT COUNT(*) AS order_cnt, SUM(amount) AS revenue FROM orders"
                                + " WHERE order_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)"
                                + " AND order_time < DATE_ADD(CURDATE(), INTERVAL 1 DAY)");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("order_cnt", 0).containsEntry("revenue", null);
    }

    @Test
    void emptyGroupedQueryHasNoRows() throws Exception {
        assertThat(
                        EmptyOrdersQueryFixture.query(
                                "SELECT DATE(order_time) AS day, SUM(amount) AS revenue FROM orders"
                                        + " WHERE order_time >= CURRENT_DATE - INTERVAL 30 DAY"
                                        + " GROUP BY DATE(order_time)"))
                .isEmpty();
    }

    @Test
    void typedDatesAndDifferentModelsKeepTheirActualResultShape() throws Exception {
        var average =
                EmptyOrdersQueryFixture.query(
                        "SELECT AVG(reading) AS value FROM observations WHERE observed_at >= DATE"
                                + " '2026-09-21'");
        assertThat(average).hasSize(1);
        assertThat(average.get(0)).containsEntry("value", null);
        assertThat(
                        EmptyOrdersQueryFixture.query(
                                "SELECT DATE(recorded_at), SUM(quantity) FROM stock_movements WHERE"
                                    + " recorded_at >= DATE_SUB(DATE '2026-10-20', INTERVAL 29 DAY)"
                                    + " AND recorded_at < DATE_ADD(DATE '2026-10-20', INTERVAL 1"
                                    + " DAY) GROUP BY DATE(recorded_at)"))
                .isEmpty();
    }
}
