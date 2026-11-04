/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.dataagent.dataset.parser.ExcelParser;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import org.junit.jupiter.api.Test;

/** Bundled files are served by fixed name and independently of the optional source. */
class DemoDataServiceTest {
    private final ExternalDataSourceRepository sources = mock(ExternalDataSourceRepository.class);

    private DemoDataService service(boolean enabled, String password) {
        DemoDataProperties properties = new DemoDataProperties();
        properties.setEnabled(enabled);
        properties.setSamplesEnabled(enabled);
        properties.setPassword(password);
        return new DemoDataService(
                properties,
                mock(DatasetStoreProperties.class),
                sources,
                mock(DataSourceIntrospector.class),
                mock(ExcelParser.class));
    }

    @Test
    void onlyKnownBundledFilesAreOffered() {
        var service = service(true, "pw");
        assertThat(service.samples()).containsExactly("示例订单.xlsx", "示例客户.xlsx");
        assertThat(service.sample("示例客户.xlsx")).isNotEmpty();
        assertThatThrownBy(() -> service.sample("private.xlsx"))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> service.sample("../secret.xlsx"))
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void legacyFilesAreNotOfferedAndOldSchemaIsNotAccepted() {
        assertThatThrownBy(() -> service(true, "pw").sample("演示订单.xlsx"))
                .isInstanceOf(DatasetException.class);
        assertThat(DemoDataService.usesCurrentSchema("jdbc:mysql://localhost:3306/demo_data"))
                .isFalse();
        assertThat(
                        DemoDataService.usesCurrentSchema(
                                "jdbc:mysql://localhost:3306/"
                                        + DemoDataService.SCHEMA
                                        + "?useSSL=false"))
                .isTrue();
        assertThatThrownBy(() -> service(true, "pw").sample("用户列表.xlsx"))
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void legacyJdbcConfigurationDoesNotExposeOrChangeOldDemoSource() {
        DemoDataProperties properties = new DemoDataProperties();
        properties.setEnabled(true);
        properties.setPassword("reader-password");
        properties.setJdbcUrl("jdbc:mysql://localhost:3306/demo_data");
        var service =
                new DemoDataService(
                        properties,
                        mock(DatasetStoreProperties.class),
                        sources,
                        mock(DataSourceIntrospector.class),
                        mock(ExcelParser.class));
        service.prepare();
        assertThat(service.sourceAvailable()).isFalse();
        verifyNoInteractions(sources);
    }

    @Test
    void nothingIsOfferedWhenDisabled() {
        var service = service(false, "pw");
        assertThat(service.samples()).isEmpty();
        assertThatThrownBy(() -> service.sample("示例订单.xlsx")).isInstanceOf(DatasetException.class);
        assertThat(service.sourceAvailable()).isFalse();
    }

    @Test
    void withoutAReaderPasswordTheSourceStaysUnavailable() {
        var service = service(true, " ");
        service.prepare();
        assertThat(service.sourceAvailable()).isFalse();
        verifyNoInteractions(sources);
    }
}
