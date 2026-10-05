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

import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the generated {@code CREATE TABLE} DDL of the dataset store (specs/010 M3, ADR 0018
 * D7(e)): every new {@code ds_*} table must pin {@code COLLATE=utf8mb4_0900_ai_ci}, because a
 * mixed collation (e.g. {@code utf8mb4_unicode_ci} vs {@code utf8mb4_0900_ai_ci}) makes cross-table
 * joins fail with {@code Illegal mix of collations} — which the wren semantic layer would hit on
 * every modeled relation. Existing tables are not migrated; only new DDL is pinned.
 */
class TableProvisionerTest {

    @Test
    void buildCreateTableDdlPinsCollationAndRendersNullability() {
        String ddl =
                TableProvisioner.buildCreateTableDdl(
                        "ds_bob0000_11111111_orders",
                        List.of(
                                new ColumnSchema("city", "城市", "VARCHAR(255)", true, "开通城市"),
                                new ColumnSchema("amount", "金额", "DECIMAL(18,6)", false, null)));

        assertThat(ddl)
                .isEqualTo(
                        "CREATE TABLE IF NOT EXISTS `ds_bob0000_11111111_orders` ("
                                + "`city` VARCHAR(255) NULL, `amount` DECIMAL(18,6) NOT NULL"
                                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                                + " COLLATE=utf8mb4_0900_ai_ci");
        assertThat(ddl).contains("COLLATE=utf8mb4_0900_ai_ci");
    }

    @Test
    void buildCreateTableDdlHandlesSingleColumn() {
        String ddl =
                TableProvisioner.buildCreateTableDdl(
                        "ds_t", List.of(new ColumnSchema("n", "n", "BIGINT", false, null)));

        assertThat(ddl).contains("`n` BIGINT NOT NULL").doesNotContain("NOT NULL, ");
        assertThat(ddl).endsWith("COLLATE=utf8mb4_0900_ai_ci");
    }
}
