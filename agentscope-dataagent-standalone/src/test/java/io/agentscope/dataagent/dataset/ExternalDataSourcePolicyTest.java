package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExternalDataSourcePolicyTest {
    private final ExternalDataSourcePolicy policy =
            new ExternalDataSourcePolicy("db.internal:3306,pg.internal:5432,[::1]:3306");

    @Test
    void structuredMysqlIsCanonicalAndRevalidatedWithoutChanges() {
        String url = policy.build("mysql", "db.internal", 3306, "sales", "REQUIRED");
        assertThat(url)
                .contains(
                        "connectTimeout=10000",
                        "socketTimeout=30000",
                        "allowLoadLocalInfile=false");
        assertThat(policy.normalize("mysql", url)).isEqualTo(url);
        assertThat(policy.build("postgresql", "pg.internal", 5432, "sales", "require"))
                .contains("connectTimeout=10", "socketTimeout=30", "sslmode=require");
        assertThat(policy.build("mysql", "[::1]", 3306, "", "REQUIRED")).contains("[::1]:3306/");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:h2:mem:db;INIT=RUNSCRIPT FROM 'file.sql'",
                "jdbc:sqlite:/tmp/private.db",
                "jdbc:mysql://db.internal.evil:3306/sales",
                "jdbc:mysql://db.internal:3307/sales",
                "jdbc:mysql://user:pass@db.internal:3306/sales",
                "jdbc:mysql://db.internal:3306/sales?socketFactory=example.Factory",
                "jdbc:mysql://db.internal:3306/sales?allowLoadLocalInfile=true",
                "jdbc:mysql://db.internal:3306/sales?autoDeserialize=true",
                "jdbc:mysql://db.internal:3306/sales?sslMode=REQUIRED&sslMode=DISABLED",
                "jdbc:mysql://db.internal:3306/sales?socketTimeout=0",
                "jdbc:mysql://db.internal:3306/sales?%73ocketFactory=bad",
                "jdbc:mysql://db.internal:3306/sales?user=admin",
                "jdbc:mysql://db.internal:3306/sales#fragment",
                "jdbc:mysql://db.internal:3306/sales%3FsocketFactory=bad"
            })
    void rejectsUntrustedConnectionInstructions(String url) {
        assertThatThrownBy(() -> policy.normalize("mysql", url))
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void emptyAllowlistAllowsTargetAndLegacySafeOptionsRemainSupported() {
        assertThat(
                        new ExternalDataSourcePolicy("")
                                .normalize("mysql", "jdbc:mysql://db.internal/sales"))
                .contains("jdbc:mysql://db.internal:3306/sales");
        assertThat(
                        policy.normalize(
                                "mysql",
                                "jdbc:mysql://db.internal/sales?useSSL=false&serverTimezone=Asia/Shanghai"))
                .contains("serverTimezone=Asia%2FShanghai", "useSSL=false");
    }
}
