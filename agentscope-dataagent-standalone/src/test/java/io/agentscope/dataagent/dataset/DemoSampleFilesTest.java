package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.agentscope.dataagent.dataset.parser.ExcelParser;
import io.agentscope.dataagent.web.onboarding.OnboardingController;
import io.agentscope.dataagent.web.onboarding.OnboardingService;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Real bytes through the sample controller and the production upload parser. */
class DemoSampleFilesTest {
    @Test
    void bundledSamplesWorkWithoutADatabaseAndKeepKeysAndMoney() throws Exception {
        var properties = new DemoDataProperties();
        properties.setEnabled(true);
        properties.setSamplesDir(
                Path.of("deploy/demo-samples/synthetic-v1").toAbsolutePath().toString());
        var parser = new ExcelParser();
        var service =
                new DemoDataService(
                        properties,
                        mock(DatasetStoreProperties.class),
                        mock(ExternalDataSourceRepository.class),
                        mock(DataSourceIntrospector.class),
                        parser);
        service.prepare();
        assertThat(service.sourceAvailable()).isFalse();
        assertThat(service.samples()).containsExactly("演示订单.xlsx", "演示客户.xlsx");
        var controller = new OnboardingController(mock(OnboardingService.class), service);
        var customerBytes = controller.sample("演示客户.xlsx").block().getBody();
        var customers = parser.parse(new ByteArrayInputStream(customerBytes), "演示客户.xlsx", 3000);
        assertThat(customers.totalRows()).isEqualTo(2000);
        var keys = new HashSet<String>();
        var names = new HashSet<String>();
        for (var row : customers.rows()) {
            assertThat(keys.add(row.get(0))).isTrue();
            assertThat(row.get(1)).matches("[\\p{IsHan}]{2,3}");
            names.add(row.get(1));
        }
        assertThat(names.size()).isLessThan(keys.size());
        assertThat(customers.rows().get(0).get(1)).isEqualTo("张明");
        assertThat(customers.rows().get(1).get(1)).isEqualTo("张明");
        var bytes = controller.sample("演示订单.xlsx").block().getBody();
        var orders = parser.parse(new ByteArrayInputStream(bytes), "演示订单.xlsx", 50000);
        assertThat(orders.totalRows()).isEqualTo(48000);
        assertThat(orders.columns().get(2).sqlType()).isEqualTo("DATETIME");
        BigDecimal revenue = BigDecimal.ZERO;
        for (var row : orders.rows()) {
            assertThat(keys).contains(row.get(1));
            revenue = revenue.add(new BigDecimal(row.get(16)));
        }
        assertThat(revenue).isEqualByComparingTo("9640810.45");
        assertThatThrownBy(() -> controller.sample("用户列表.xlsx").block())
                .isInstanceOf(ResponseStatusException.class);
    }
}
