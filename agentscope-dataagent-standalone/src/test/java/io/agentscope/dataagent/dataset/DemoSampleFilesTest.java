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
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Real bytes through the sample controller and the production upload parser. */
class DemoSampleFilesTest {
    @Test
    void bundledSamplesWorkWithoutADatabaseAndKeepKeysAndMoney() throws Exception {
        var properties = new DemoDataProperties();
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
        assertThat(service.samples()).containsExactly("示例订单.xlsx", "示例客户.xlsx");
        var controller = new OnboardingController(mock(OnboardingService.class), service);
        var customerBytes = controller.sample("示例客户.xlsx").block().getBody();
        var customers = parser.parse(new ByteArrayInputStream(customerBytes), "示例客户.xlsx", 21);
        assertThat(customers.totalRows()).isEqualTo(20);
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
        var bytes = controller.sample("示例订单.xlsx").block().getBody();
        var orders = parser.parse(new ByteArrayInputStream(bytes), "示例订单.xlsx", 1001);
        assertThat(orders.totalRows()).isEqualTo(1000);
        assertThat(orders.columns().get(2).sqlType()).isEqualTo("DATETIME");
        var orderIds = new HashSet<String>();
        var buyers = new HashSet<String>();
        BigDecimal revenue = BigDecimal.ZERO;
        for (var row : orders.rows()) {
            assertThat(orderIds.add(row.get(0))).isTrue();
            assertThat(keys).contains(row.get(1));
            buyers.add(row.get(1));
            revenue = revenue.add(new BigDecimal(row.get(16)));
        }
        assertThat(buyers).containsExactlyInAnyOrderElementsOf(keys);
        assertThat(revenue).isEqualByComparingTo("202014.50");
        assertThatThrownBy(() -> controller.sample("用户列表.xlsx").block())
                .isInstanceOf(ResponseStatusException.class);
    }
}
