package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.parser.ExcelParser;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in, disposable MySQL only. Never use an existing developer or production database. */
@EnabledIfEnvironmentVariable(
        named = "DATAAGENT_SYNTHETIC_TEST_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:.*")
class SyntheticDemoMysqlTest {
    @Test
    void seedsOnceKeepsLegacyDataAndReaderCannotQueryOtherSchemas() throws Exception {
        String base = System.getenv("DATAAGENT_SYNTHETIC_TEST_URL");
        String password = System.getenv("DATAAGENT_SYNTHETIC_TEST_ROOT_PASSWORD");
        String readerPassword = System.getenv("DATAAGENT_SYNTHETIC_TEST_READER_PASSWORD");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true";
        var store = mock(DatasetStoreProperties.class);
        when(store.url()).thenReturn(base + "/mysql" + options);
        when(store.username()).thenReturn("root");
        when(store.password()).thenReturn(password);
        try (var c = DriverManager.getConnection(store.url(), "root", password);
                var st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS demo_data");
            st.execute("CREATE TABLE IF NOT EXISTS demo_data.legacy_sentinel (v INT PRIMARY KEY)");
            st.execute("INSERT IGNORE INTO demo_data.legacy_sentinel VALUES (7)");
        }
        var properties = new DemoDataProperties();
        properties.setEnabled(true);
        properties.setPassword(readerPassword);
        properties.setJdbcUrl(base + "/" + DemoDataService.SCHEMA + options);
        var sources = mock(ExternalDataSourceRepository.class);
        when(sources.findById(any())).thenReturn(Optional.empty());
        when(sources.save(any())).thenAnswer(i -> i.getArgument(0));
        var service =
                new DemoDataService(
                        properties,
                        store,
                        sources,
                        new DataSourceIntrospector(new ExternalDataSourcePolicy("")),
                        new ExcelParser());
        service.prepare();
        assertThat(service.sourceAvailable()).isTrue();
        service.prepare();
        assertThat(service.sourceAvailable()).isTrue();
        try (var c =
                        DriverManager.getConnection(
                                properties.getJdbcUrl(), properties.getUsername(), readerPassword);
                var st = c.createStatement()) {
            try (var rs = st.executeQuery("SELECT COUNT(*), SUM(net_revenue) FROM demo_orders")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1000);
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("202014.50");
            }
            try (var rs =
                    st.executeQuery(
                            "SELECT COUNT(*), COUNT(DISTINCT customer_name) FROM demo_customers")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(20);
                assertThat(rs.getInt(2)).isEqualTo(19);
            }
            assertThatThrownBy(() -> st.executeQuery("SELECT * FROM demo_data.legacy_sentinel"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    st.executeUpdate(
                                            "DELETE FROM demo_orders WHERE order_id='nonexistent'"))
                    .isInstanceOf(SQLException.class);
        }
        try (var c = DriverManager.getConnection(store.url(), "root", password);
                var st = c.createStatement();
                var rs = st.executeQuery("SELECT v FROM demo_data.legacy_sentinel")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(7);
        }
    }
}
