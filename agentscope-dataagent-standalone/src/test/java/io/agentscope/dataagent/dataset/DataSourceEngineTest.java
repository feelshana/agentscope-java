package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import org.junit.jupiter.api.Test;

class DataSourceEngineTest {
    @Test
    void mysqlEightAndDorisAreSupportedButOlderMysqlIsNot() {
        assertThat(DataSourceIntrospector.engineError("8.0.35", "MySQL Community Server - GPL"))
                .isNull();
        assertThat(DataSourceIntrospector.engineError("9.1.0", "MySQL Community Server - GPL"))
                .isNull();
        // Doris reports a 5.7-compatible version; its comment identifies it.
        assertThat(DataSourceIntrospector.engineError("5.7.99", "Doris version doris-2.1.7-rc03"))
                .isNull();
        assertThat(DataSourceIntrospector.engineError("5.7.44-log", "MySQL Community Server (GPL)"))
                .contains("5.7.44-log", "低于 8.0", "MySQL 8.0 及以上或 Doris");
        assertThat(DataSourceIntrospector.engineError("unknown", null)).contains("无法识别数据库版本");
    }

    @Test
    void nonMysqlKindsAreRefusedWithoutConnecting() {
        var introspector = new DataSourceIntrospector(new ExternalDataSourcePolicy(""));
        var postgres =
                new ExternalDataSourceEntity(
                        "s", "alice", "pg", "postgresql", "jdbc:postgresql://db/x", "u", "p", true);
        assertThat(introspector.connectionError(postgres))
                .isEqualTo(DataSourceIntrospector.UNSUPPORTED);
    }
}
