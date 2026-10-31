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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** spec105: demo files come only from the configured directory, under fixed names. */
class DemoDataServiceTest {
    private final ExternalDataSourceRepository sources = mock(ExternalDataSourceRepository.class);

    @TempDir Path dir;

    private DemoDataService service(boolean enabled, String password) {
        DemoDataProperties properties = new DemoDataProperties();
        properties.setEnabled(enabled);
        properties.setSamplesDir(dir.toString());
        properties.setPassword(password);
        return new DemoDataService(
                properties,
                mock(DatasetStoreProperties.class),
                sources,
                mock(DataSourceIntrospector.class),
                mock(ExcelParser.class));
    }

    @Test
    void onlyKnownFilesPresentInTheDirectoryAreOffered() throws Exception {
        Files.writeString(dir.resolve(DemoDataService.SAMPLE_FILES.get(1)), "x");
        Files.writeString(dir.resolve("private.xlsx"), "x");
        var service = service(true, "pw");
        assertThat(service.samples()).containsExactly(DemoDataService.SAMPLE_FILES.get(1));
        assertThat(service.sample(DemoDataService.SAMPLE_FILES.get(1))).hasSize(1);
        assertThatThrownBy(() -> service.sample("private.xlsx"))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> service.sample("../secret.xlsx"))
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void nothingIsOfferedWhenDisabled() throws Exception {
        Files.writeString(dir.resolve(DemoDataService.SAMPLE_FILES.get(0)), "x");
        var service = service(false, "pw");
        assertThat(service.samples()).isEmpty();
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
