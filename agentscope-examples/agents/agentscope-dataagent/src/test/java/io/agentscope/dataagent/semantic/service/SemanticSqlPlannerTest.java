package io.agentscope.dataagent.semantic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SemanticSqlPlannerTest {
    private final SemanticSqlPlanner planner = new SemanticSqlPlanner();
    private final Map<String, SemanticSqlPlanner.TableBinding> bindings =
            Map.of(
                    "visits",
                            new SemanticSqlPlanner.TableBinding(
                                    "PUBLIC", "visits", Set.of("account", "pv", "day")),
                    "users",
                            new SemanticSqlPlanner.TableBinding(
                                    "PUBLIC", "users", Set.of("account", "company")));

    private SemanticModel model() throws Exception {
        return new ObjectMapper()
                .readValue(
                        """
                        {"models":[
                          {"name":"visits","tableName":"visits","columns":[
                            {"name":"account","type":"VARCHAR"},{"name":"pv","type":"INTEGER"},{"name":"day","type":"TIMESTAMP"},
                            {"name":"visitor","type":"users","relationship":"performedBy"},
                            {"name":"company","type":"VARCHAR","isCalculated":true,"expression":"visitor.company"},
                            {"name":"double_pv","type":"INTEGER","isCalculated":true,"expression":"pv * 2"}]},
                          {"name":"users","tableName":"users","columns":[{"name":"account","type":"VARCHAR"},{"name":"company","type":"VARCHAR"}]}],
                          "relationships":[{"name":"performedBy","models":["visits","users"],"joinType":"MANY_TO_ONE","condition":"visits.account = users.account"}]}
                        """,
                        SemanticModel.class);
    }

    @Test
    void compilesAndExecutesLeftJoinWithSamePhysicalName() throws Exception {
        var mdl = model();
        assertThat(mdl.findModel("visits").getColumns().get(4).isCalculated()).isTrue();
        var plan =
                planner.compile(
                        "SELECT company, SUM(pv) AS total FROM visits GROUP BY company ORDER BY"
                                + " total DESC",
                        mdl,
                        bindings);
        assertThat(plan.compiledSql())
                .contains("LEFT JOIN", "`PUBLIC`.`visits`", "`PUBLIC`.`users`");
        assertThat(plan.models()).containsExactlyInAnyOrder("users", "visits");
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:planner;MODE=MySQL")) {
            var st = connection.createStatement();
            st.execute("CREATE TABLE visits(account VARCHAR, pv INT, `day` TIMESTAMP)");
            st.execute("CREATE TABLE users(account VARCHAR, company VARCHAR)");
            st.execute("INSERT INTO visits VALUES('a',5,NULL),('missing',2,NULL)");
            st.execute("INSERT INTO users VALUES('a','Alpha')");
            var rs = st.executeQuery(plan.compiledSql());
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Alpha");
            assertThat(rs.getInt(2)).isEqualTo(5);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNull();
            assertThat(rs.getInt(2)).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT SUM(pv) AS total FROM visits ORDER BY total DESC LIMIT 1",
                "SELECT COUNT(*) FROM visits",
                "SELECT COUNT(DISTINCT account) FROM visits",
                "SELECT 'visits' AS text, pv FROM visits WHERE pv > 1",
                "SELECT v.pv, u.company FROM visits v JOIN users u ON v.account = u.account",
                "SELECT * FROM visits",
                "SELECT v.* FROM visits v",
                "SELECT double_pv FROM visits",
                "WITH x AS (SELECT account, pv FROM visits) SELECT SUM(pv) FROM x",
                "SELECT x.pv FROM (SELECT pv FROM visits) x",
                "SELECT pv FROM visits UNION ALL SELECT pv FROM visits",
                "SELECT pv FROM visits v WHERE EXISTS (SELECT 1 FROM users u WHERE u.account ="
                        + " v.account)",
                "SELECT pv FROM visits WHERE day >= TIMESTAMP '2026-09-06 00:00:00'",
                "SELECT CASE WHEN pv > 0 THEN pv ELSE 0 END AS p FROM visits"
            })
    void supportsSemanticQueries(String sql) throws Exception {
        var plan = planner.compile(sql, model(), bindings);
        assertThat(plan.compiledSql()).startsWith("WITH").contains("`PUBLIC`.`visits`");
        if (sql.contains("'visits'")) assertThat(plan.compiledSql()).contains("'visits'");
        try (var connection =
                DriverManager.getConnection("jdbc:h2:mem:queries;MODE=MySQL;NON_KEYWORDS=DAY")) {
            var st = connection.createStatement();
            st.execute("CREATE TABLE visits(account VARCHAR, pv INT, `day` TIMESTAMP)");
            st.execute("CREATE TABLE users(account VARCHAR, company VARCHAR)");
            st.execute("INSERT INTO visits VALUES('a',5,TIMESTAMP '2026-09-06 12:00:00')");
            st.execute("INSERT INTO users VALUES('a','Alpha')");
            try (var result = st.executeQuery(plan.compiledSql())) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DELETE FROM visits",
                "SELECT pv FROM visits; DELETE FROM visits",
                "SELECT visitor FROM visits",
                "SELECT missing FROM visits",
                "SELECT * FROM other_tenant",
                "SELECT pv FROM PUBLIC.visits",
                "SELECT SLEEP(5) FROM visits",
                "SELECT LOAD_FILE('/file') FROM visits",
                "SELECT pv INTO OUTFILE '/file' FROM visits",
                "SELECT pv FROM visits FOR UPDATE",
                "SELECT @secret FROM visits",
                "SELECT @secret := pv FROM visits",
                "SELECT pv FROM visits WHERE EXISTS(SELECT * FROM secret)",
                "WITH x AS (SELECT * FROM secret) SELECT * FROM x",
                "SELECT account FROM visits v JOIN users u ON v.account = u.account",
                "SELECT visitor.company FROM visits",
                "SELECT * FROM visits NATURAL JOIN users"
            })
    void rejectsUnsafeOrUnknownQueries(String sql) throws Exception {
        var mdl = model();
        assertThatThrownBy(() -> planner.compile(sql, mdl, bindings))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnboundTablesAndMaliciousExpressions() throws Exception {
        var mdl = model();
        assertThatThrownBy(() -> planner.compile("SELECT pv FROM visits", mdl, Map.of()))
                .hasMessageContaining("未绑定");
        mdl.findModel("visits").getColumns().get(4).setExpression("LOAD_FILE('/file')");
        assertThatThrownBy(() -> planner.compile("SELECT company FROM visits", mdl, bindings))
                .hasMessageContaining("不支持");
        mdl.findModel("visits").getColumns().get(4).setExpression("company");
        assertThatThrownBy(() -> planner.compile("SELECT company FROM visits", mdl, bindings))
                .hasMessageContaining("循环");
    }
}
