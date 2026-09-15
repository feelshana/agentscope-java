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

import static io.agentscope.dataagent.ontology.source.ManifestImportService.rewriteTableNames;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests the word-boundary table-name rewriting in {@link ManifestImportService#rewriteTableNames}.
 * Covers: prefix swallowing (user vs user_identity), backtick-wrapped names, multi-table mix,
 * derived-referencing-derived chains, and null/empty edge cases.
 */
class TableNameRewriteTest {

    @Test
    void basicBareNameReplacement() {
        Map<String, ManifestImportService.TableRef> map =
                mapOf("app_user", "ds_owner01_id01_app_user");
        String result = rewriteTableNames("SELECT * FROM app_user", map);
        assertThat(result).isEqualTo("SELECT * FROM ds_owner01_id01_app_user");
    }

    @Test
    void preventsPrefixSwallowing() {
        Map<String, ManifestImportService.TableRef> map = new LinkedHashMap<>();
        map.put("user", ref("d1", "ds_o_d_user"));
        map.put("user_identity", ref("d2", "ds_o_d_user_identity"));

        String sql = "SELECT * FROM user_identity JOIN user ON user.id = user_identity.uid";
        String result = rewriteTableNames(sql, map);

        assertThat(result)
                .contains("ds_o_d_user_identity")
                .contains("ds_o_d_user")
                .doesNotContain("ds_o_d_user_identity_identity");
    }

    @Test
    void handlesBacktickWrappedNames() {
        Map<String, ManifestImportService.TableRef> map = mapOf("app_user", "ds_o_d_app_user");
        String sql = "SELECT * FROM `app_user` WHERE `app_user`.id = 1";
        String result = rewriteTableNames(sql, map);

        assertThat(result)
                .contains("ds_o_d_app_user")
                .doesNotContain("`app_user`")
                .doesNotContain("`ds_o_d_app_user`");
    }

    @Test
    void replacesTableQualifiedColumnRefs() {
        Map<String, ManifestImportService.TableRef> map = mapOf("click_data", "ds_o_d_click_data");
        String sql = "SELECT click_data.id FROM click_data";
        String result = rewriteTableNames(sql, map);
        // Both bare references are replaced (dot is a word boundary)
        assertThat(result).isEqualTo("SELECT ds_o_d_click_data.id FROM ds_o_d_click_data");
    }

    @Test
    void multiTableMixedReplacement() {
        Map<String, ManifestImportService.TableRef> map = new LinkedHashMap<>();
        map.put("app_user", ref("d1", "ds_o_d1_app_user"));
        map.put("click_observation", ref("d2", "ds_o_d2_click_obs"));
        map.put("project", ref("d3", "ds_o_d3_project"));

        String sql =
                "SELECT u.user_id FROM app_user u "
                        + "JOIN click_observation c ON c.user_id = u.user_id "
                        + "JOIN project p ON p.project_id = u.project_id";
        String result = rewriteTableNames(sql, map);

        assertThat(result)
                .contains("ds_o_d1_app_user")
                .contains("ds_o_d2_click_obs")
                .contains("ds_o_d3_project")
                .doesNotContain("FROM app_user")
                .doesNotContain("JOIN click_observation")
                .doesNotContain("JOIN project ");
    }

    @Test
    void derivedReferencingDerivedTable() {
        Map<String, ManifestImportService.TableRef> map = new LinkedHashMap<>();
        map.put("app_user", ref("d1", "ds_o_d1_app_user"));
        map.put("user_identity", ref("d2", "ds_o_d2_user_identity"));

        String sql =
                "SELECT i.user_id FROM user_identity i "
                        + "LEFT JOIN app_user u ON u.account = i.account";
        String result = rewriteTableNames(sql, map);

        assertThat(result).contains("ds_o_d2_user_identity").contains("ds_o_d1_app_user");
    }

    @Test
    void nullSqlReturnsNull() {
        assertThat(rewriteTableNames(null, Map.of())).isNull();
    }

    @Test
    void emptyMapReturnsOriginalSql() {
        String sql = "SELECT * FROM app_user";
        assertThat(rewriteTableNames(sql, Map.of())).isEqualTo(sql);
    }

    @Test
    void preservesColumnAliases() {
        Map<String, ManifestImportService.TableRef> map = mapOf("app_user", "ds_o_d_app_user");
        String sql = "SELECT user_id AS uid FROM app_user";
        String result = rewriteTableNames(sql, map);
        assertThat(result).isEqualTo("SELECT user_id AS uid FROM ds_o_d_app_user");
    }

    // ---------- helpers ----------

    private static ManifestImportService.TableRef ref(String datasetId, String physical) {
        return new ManifestImportService.TableRef(datasetId, physical);
    }

    private static Map<String, ManifestImportService.TableRef> mapOf(
            String logical, String physical) {
        Map<String, ManifestImportService.TableRef> m = new LinkedHashMap<>();
        m.put(logical, ref("d1", physical));
        return m;
    }
}
