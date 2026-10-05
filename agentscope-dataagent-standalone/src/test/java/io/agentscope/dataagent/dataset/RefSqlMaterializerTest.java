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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks the lexical materialiser for ref_sql derived models (specs/035, ADR 0043): the engine
 * expands a ref_sql body verbatim, so bare FROM/JOIN references must be physically qualified
 * before publishing. References are collected in first-appearance order, quoted text and
 * comments can never produce references, CTEs and dotted names are excluded, and replacements
 * run from the tail so earlier source offsets stay valid.
 */
class RefSqlMaterializerTest {

    // ------------------------------------------------------------------ extractTableRefs

    @Test
    void collectsFromAndJoinRefsInFirstAppearanceOrder() {
        assertEquals(
                List.of("orders", "customers", "regions"),
                RefSqlMaterializer.extractTableRefs(
                        "SELECT * FROM orders o JOIN customers AS c ON o.cid = c.id"
                                + " LEFT JOIN regions r ON c.rid = r.id"));
    }

    @Test
    void deduplicatesCaseInsensitivelyKeepingFirstCase() {
        assertEquals(
                List.of("orders"),
                RefSqlMaterializer.extractTableRefs(
                        "SELECT * FROM orders a JOIN ORDERS b ON a.id = b.id"));
    }

    @Test
    void collectsCommaSeparatedFromList() {
        assertEquals(
                List.of("orders", "customers"),
                RefSqlMaterializer.extractTableRefs("SELECT * FROM orders, customers WHERE 1=1"));
    }

    @Test
    void ignoresStringsAndComments() {
        assertEquals(
                List.of("orders"),
                RefSqlMaterializer.extractTableRefs(
                        "SELECT 'FROM ghost' AS s FROM orders\n"
                                + "-- JOIN ghost2\n"
                                + "# JOIN ghost3\n"
                                + "/* FROM ghost4 */\n"));
    }

    @Test
    void excludesCteNamesButKeepsTheirSources() {
        assertEquals(
                List.of("orders", "customers"),
                RefSqlMaterializer.extractTableRefs(
                        "WITH ranked AS (SELECT id FROM orders)"
                                + " SELECT * FROM ranked JOIN customers c ON ranked.id = c.id"));
    }

    @Test
    void skipsDottedQualifiedNames() {
        assertEquals(
                List.of(),
                RefSqlMaterializer.extractTableRefs("SELECT * FROM data_agent.ds_orders"));
    }

    @Test
    void decodesQuotedIdentifiers() {
        assertEquals(List.of("订单表"), RefSqlMaterializer.extractTableRefs("SELECT * FROM `订单表`"));
        assertEquals(
                List.of("order list"),
                RefSqlMaterializer.extractTableRefs("SELECT * FROM \"order list\""));
    }

    @Test
    void doesNotTreatExtractSubclauseFromAsTable() {
        assertEquals(
                List.of("orders"),
                RefSqlMaterializer.extractTableRefs(
                        "SELECT EXTRACT(YEAR FROM created_at) AS y FROM orders"));
    }

    // ------------------------------------------------------------------ materialize

    @Test
    void rewritesEveryMappedReference() {
        assertEquals(
                "SELECT * FROM data_agent.ds_o1 o JOIN data_agent.ds_c1 c ON o.cid = c.id",
                RefSqlMaterializer.materialize(
                        "SELECT * FROM orders o JOIN customers c ON o.cid = c.id",
                        Map.of("orders", "data_agent.ds_o1", "customers", "data_agent.ds_c1")));
    }

    @Test
    void tailFirstReplacementKeepsEarlierOffsets() {
        // A short reference before a long one: naive forward replacement would shift offsets.
        assertEquals(
                "SELECT a FROM very_long_physical_name JOIN p2 ON a = 1",
                RefSqlMaterializer.materialize(
                        "SELECT a FROM x JOIN yyyy ON a = 1",
                        Map.of("x", "very_long_physical_name", "yyyy", "p2")));
    }

    @Test
    void lookupIsExactFirstThenCaseInsensitive() {
        assertEquals(
                "SELECT * FROM data_agent.ds_o1",
                RefSqlMaterializer.materialize(
                        "SELECT * FROM ORDERS", Map.of("orders", "data_agent.ds_o1")));
    }

    @Test
    void keepsUnmappedReferencesUntouched() {
        assertEquals(
                "SELECT * FROM data_agent.ds_o1 JOIN ghost g ON 1=1",
                RefSqlMaterializer.materialize(
                        "SELECT * FROM orders JOIN ghost g ON 1=1",
                        Map.of("orders", "data_agent.ds_o1")));
    }

    @Test
    void replacesQuotedReferencesIncludingQuotes() {
        assertEquals(
                "SELECT * FROM data_agent.ds_o1",
                RefSqlMaterializer.materialize(
                        "SELECT * FROM `orders`", Map.of("orders", "data_agent.ds_o1")));
    }

    @Test
    void neverTouchesStringLiterals() {
        assertEquals(
                "SELECT 'orders' AS label FROM data_agent.ds_o1",
                RefSqlMaterializer.materialize(
                        "SELECT 'orders' AS label FROM orders",
                        Map.of("orders", "data_agent.ds_o1")));
    }

    @Test
    void materializeIsNoOpWithoutSqlOrMap() {
        assertEquals("SELECT 1", RefSqlMaterializer.materialize("SELECT 1", Map.of()));
        assertEquals("SELECT 1", RefSqlMaterializer.materialize("SELECT 1", null));
        assertNull(RefSqlMaterializer.materialize(null, Map.of("a", "b")));
    }

    // ------------------------------------------------------------------ resolve / physicalName

    @Test
    void resolveIsNullSafeAndCaseInsensitive() {
        assertNull(RefSqlMaterializer.resolve(null, "a"));
        assertNull(RefSqlMaterializer.resolve(Map.of("a", "p"), null));
        assertNull(RefSqlMaterializer.resolve(Map.of("a", "p"), "b"));
        assertEquals("p", RefSqlMaterializer.resolve(Map.of("a", "p"), "A"));
    }

    @Test
    void physicalNameQuotesOnlyNonPlainParts() {
        assertEquals("data_agent.ds_o1", RefSqlMaterializer.physicalName("data_agent", "ds_o1"));
        assertEquals("`my schema`.`订单表`", RefSqlMaterializer.physicalName("my schema", "订单表"));
        assertEquals("orders", RefSqlMaterializer.physicalName(null, "orders"));
        assertEquals("orders", RefSqlMaterializer.physicalName("  ", "orders"));
        assertNull(RefSqlMaterializer.physicalName("s", "  "));
        assertNull(RefSqlMaterializer.physicalName("s", null));
    }
}
