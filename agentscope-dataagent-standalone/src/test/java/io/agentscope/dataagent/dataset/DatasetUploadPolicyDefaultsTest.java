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

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.FileSystemResource;

class DatasetUploadPolicyDefaultsTest {
    @Test
    void omittingTheUploadSectionDoesNotChangeTheShippedLimits() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        var shipped = yaml.getObject();
        var defaults = new DatasetUploadProperties();
        assertThat(defaults.getMaxRows())
                .isEqualTo(Long.parseLong(shipped.getProperty("dataagent.upload.max-rows")));
        assertThat(defaults.getMaxCells())
                .isEqualTo(Long.parseLong(shipped.getProperty("dataagent.upload.max-cells")));
    }

    @Test
    void deploymentCanStillOverrideBothLimits() {
        var properties = new DatasetUploadProperties();
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "dataagent.upload.max-rows", "200000",
                                "dataagent.upload.max-cells", "2000000"));
        new Binder(source).bind("dataagent.upload", Bindable.ofInstance(properties));
        assertThat(properties.getMaxRows()).isEqualTo(200000);
        assertThat(properties.getMaxCells()).isEqualTo(2000000);
    }
}
