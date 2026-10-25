package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlWindowRulesTest {
    private static final List<SqlWindowGate.Rule> RULES =
            List.of(
                    new SqlWindowGate.Rule("report_log", List.of("log_time"), 31),
                    new SqlWindowGate.Rule("dashboard_log", List.of("log_time"), 31));

    private static SqlWindowGate.Decision decide(String sql) {
        return SqlWindowRules.evaluate(sql, RULES).orElseThrow();
    }

    /** The 2026-10-25 query log: a join with an OR-filter that the model gate once timed out on. */
    @Test
    void decidesTheGeneratedMonthlyJoinWithoutTheModel() {
        var decision =
                decide(
                        "SELECT o.department, COUNT(DISTINCT r.operator_id) AS user_count\n"
                                + "FROM report_log r\n"
                                + "LEFT JOIN sys_operator o ON r.operator_id = o.id\n"
                                + "WHERE r.log_time >= '2026-10-01' AND r.log_time < '2026-11-01'\n"
                                + "  AND (o.department NOT LIKE '%数智化部%' OR o.department IS NULL)\n"
                                + "GROUP BY o.department ORDER BY user_count DESC LIMIT 10");
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.tables())
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.table()).isEqualTo("report_log");
                            assertThat(t.windowDays()).isEqualTo(31.0);
                            assertThat(t.hasCondition()).isTrue();
                        });
        assertThat(decision.reason()).startsWith("规则判断：");
    }

    @Test
    void windowsFollowTheGateConventions() {
        // Left-closed right-open: 19 days; a closed date-only end covers the whole last day.
        assertThat(
                        decide(
                                        "SELECT 1 FROM report_log WHERE log_time >= '2025-01-01'"
                                                + " AND log_time < '2025-01-20'")
                                .tables()
                                .get(0)
                                .windowDays())
                .isEqualTo(19.0);
        assertThat(
                        decide(
                                        "SELECT 1 FROM report_log WHERE log_time BETWEEN"
                                                + " '2026-10-01' AND '2026-10-31'")
                                .allowed())
                .isTrue();
        assertThat(
                        decide(
                                        "SELECT 1 FROM report_log WHERE log_time BETWEEN"
                                                + " '2026-10-01' AND '2026-11-01'")
                                .allowed())
                .isFalse();
        assertThat(
                        decide(
                                        "SELECT 1 FROM report_log WHERE log_time >= '2026-10-01"
                                            + " 00:00:00' AND log_time <= '2026-10-31 23:59:59'")
                                .allowed())
                .isTrue();
        // Reversed operands, typed literals, platform parsing wrappers and numeric date keys.
        assertThat(
                        decide(
                                        "SELECT 1 FROM report_log WHERE '2026-10-01' <= log_time"
                                                + " AND log_time < DATE '2026-10-15'")
                                .tables()
                                .get(0)
                                .windowDays())
                .isEqualTo(14.0);
        assertThat(
                        decide(
                                        "SELECT 1 FROM `report_log` r WHERE STR_TO_DATE(r.log_time,"
                                            + " '%Y/%c/%e') >= '2026-10-01' AND"
                                            + " STR_TO_DATE(r.log_time, '%Y/%c/%e') < '2026-11-01'")
                                .allowed())
                .isTrue();
        assertThat(
                        decide(
                                        "SELECT 1 FROM report_log WHERE log_time >= 20261001 AND"
                                                + " log_time < 20261201")
                                .allowed())
                .isFalse();
    }

    @Test
    void missingOrOneSidedRangesAreRefusedPerTable() {
        var none = decide("SELECT COUNT(*) FROM dashboard_log");
        assertThat(none.allowed()).isFalse();
        assertThat(none.tables().get(0).hasCondition()).isFalse();
        assertThat(
                        decide("SELECT COUNT(*) FROM dashboard_log WHERE log_time >= '2026-10-01'")
                                .allowed())
                .isFalse();
        // Each registered table needs its own range; the other table's dates do not count.
        var join =
                decide(
                        "SELECT 1 FROM report_log r JOIN dashboard_log d ON r.operator_id ="
                                + " d.operator_id WHERE r.log_time >= '2026-10-01' AND r.log_time <"
                                + " '2026-10-08'");
        assertThat(join.allowed()).isFalse();
        assertThat(join.tables())
                .extracting(SqlWindowGate.TableCheck::hasCondition)
                .containsExactly(true, false);
    }

    @Test
    void unregisteredTablesNeedNoRange() {
        var decision = decide("SELECT company FROM sys_operator");
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.tables()).isEmpty();
    }

    /** Shapes whose window needs interpretation stay with the model gate. */
    @Test
    void branchesSubqueriesAndRelativeDatesAreLeftToTheModel() {
        for (String sql :
                List.of(
                        "SELECT 1 FROM report_log WHERE log_time >= '2026-10-01' OR log_time <"
                                + " '2026-10-08'",
                        "SELECT 1 FROM report_log WHERE (log_time >= '2026-10-01' OR id = 1) AND"
                                + " log_time < '2026-10-08'",
                        "SELECT 1 FROM report_log WHERE log_time >= NOW() - INTERVAL 7 DAY",
                        "SELECT 1 FROM report_log WHERE id IN (SELECT id FROM dashboard_log)",
                        "WITH x AS (SELECT * FROM report_log) SELECT * FROM x",
                        "SELECT 1 FROM report_log a JOIN report_log b ON a.id = b.id",
                        "SELECT 1 FROM report_log WHERE NOT (log_time < '2026-10-01') AND log_time"
                                + " < '2026-10-08'",
                        "SELECT 1 FROM report_log r JOIN sys_operator s ON r.log_time >="
                                + " '2026-10-01' WHERE r.log_time < '2026-10-08'"))
            assertThat(SqlWindowRules.evaluate(sql, RULES)).as(sql).isEmpty();
    }

    @Test
    void literalsAndCommentsCannotFakeARange() {
        // A range inside a string or a comment is not a condition.
        assertThat(
                        decide(
                                        "SELECT 'log_time >= ''2026-10-01''' FROM report_log --"
                                                + " WHERE log_time < '2026-10-02'")
                                .allowed())
                .isFalse();
        // A range on another table's same-named column does not bound the registered table.
        assertThat(
                        decide(
                                        "SELECT 1 FROM report_log r JOIN sys_operator s ON"
                                                + " r.operator_id = s.id WHERE s.log_time >="
                                                + " '2026-10-01' AND s.log_time < '2026-10-08'")
                                .allowed())
                .isFalse();
        // A range only in the SELECT list filters nothing.
        assertThat(
                        decide(
                                        "SELECT SUM(CASE WHEN log_time >= '2026-10-01' THEN 1 END)"
                                                + " FROM report_log")
                                .allowed())
                .isFalse();
    }
}
