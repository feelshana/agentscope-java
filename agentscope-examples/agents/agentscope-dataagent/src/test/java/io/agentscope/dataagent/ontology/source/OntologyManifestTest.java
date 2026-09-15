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
package io.agentscope.dataagent.ontology.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.dataagent.ontology.source.model.ColumnMapping;
import io.agentscope.dataagent.ontology.source.model.DerivedTable;
import io.agentscope.dataagent.ontology.source.model.SourceBatch;
import io.agentscope.dataagent.ontology.source.model.SourceManifest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Validates that sources.json is correctly parsed by {@link SourceParser}, and that column-type
 * mapping via {@link ColumnMapping#toMysqlType()} produces the expected DDL types.
 */
class OntologyManifestTest {

    private final SourceParser parser = new SourceParser();

    private String loadTestJson() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/ontology/test-sources.json")) {
            assertThat(is).as("test-sources.json must exist on classpath").isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesExtendedFieldsFromTestJson() throws Exception {
        SourceManifest manifest = parser.parse(loadTestJson());

        assertThat(manifest.getBatches()).hasSize(2);

        // First batch
        SourceBatch users = manifest.getBatches().get(0);
        assertThat(users.getId()).isEqualTo("user-list");
        assertThat(users.getTable()).isEqualTo("app_user");
        assertThat(users.getColumns()).hasSize(2);

        ColumnMapping userIdCol = users.getColumns().get(0);
        assertThat(userIdCol.getName()).isEqualTo("user_id");
        assertThat(userIdCol.getType()).isEqualTo("varchar(200)");
        assertThat(userIdCol.getComment()).isEqualTo("用户ID");
        assertThat(userIdCol.toMysqlType()).isEqualTo("varchar(200)");

        // Second batch with time_field
        SourceBatch clicks = manifest.getBatches().get(1);
        assertThat(clicks.getTime_field()).isEqualTo("click_time");
        assertThat(clicks.getColumns()).hasSize(3);

        // Derived
        assertThat(manifest.getDerived()).hasSize(1);
        DerivedTable dt = manifest.getDerived().get(0);
        assertThat(dt.getTable()).isEqualTo("user_identity");
        assertThat(dt.getSql()).contains("app_user");

        // Relationships
        assertThat(manifest.getRelationships()).hasSize(1);
        SourceManifest.ManifestRelationship rel = manifest.getRelationships().get(0);
        assertThat(rel.getSource()).isEqualTo("app_user");
        assertThat(rel.getSourceColumn()).isEqualTo("user_id");
        assertThat(rel.getTarget()).isEqualTo("click_observation");
        assertThat(rel.getTargetColumn()).isEqualTo("user_id");
        assertThat(rel.getJoinType()).isEqualTo("one_to_many");
        assertThat(rel.getLabel()).isEqualTo("用户产生点击");
    }

    @Test
    void columnTypeMappingProducesCorrectDdl() {
        assertThat(typeOf("varchar(200)")).isEqualTo("varchar(200)");
        assertThat(typeOf("bigint")).isEqualTo("BIGINT");
        assertThat(typeOf("double")).isEqualTo("DOUBLE");
        assertThat(typeOf("timestamp")).isEqualTo("DATETIME");
        assertThat(typeOf("date_compact")).isEqualTo("DATE");
        assertThat(typeOf("percent")).isEqualTo("DECIMAL(18,6)");
        assertThat(typeOf("yesno")).isEqualTo("TINYINT(1)");
        assertThat(typeOf(null)).isEqualTo("VARCHAR(255)");
        assertThat(typeOf("")).isEqualTo("VARCHAR(255)");
    }

    @Test
    void rejectsBlankJson() {
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{invalid json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void emptyBatchesProducesEmptyManifest() {
        SourceManifest m = parser.parse("{\"batches\":[]}");
        assertThat(m.getBatches()).isEmpty();
        assertThat(m.getDerived()).isEmpty();
        assertThat(m.getRelationships()).isEmpty();
    }

    private String typeOf(String type) {
        ColumnMapping cm = new ColumnMapping();
        cm.setType(type);
        return cm.toMysqlType();
    }
}
