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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.dataset.parser.ParsedTable;
import io.agentscope.dataagent.dataset.parser.TypeInferrer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatasetParsingTest {

    private static ParsedTable csv(String content, int rowLimit) throws IOException {
        return new io.agentscope.dataagent.dataset.parser.CsvParser()
                .parse(
                        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                        "t.csv",
                        rowLimit);
    }

    @Test
    void infersColumnTypes() {
        assertEquals("BIGINT", TypeInferrer.infer(List.of("1", "2", "-3")));
        assertEquals("DECIMAL(18,6)", TypeInferrer.infer(List.of("1.5", "2", "3.25")));
        assertEquals("DATE", TypeInferrer.infer(List.of("2026-09-01", "2026/09/02")));
        assertEquals("DATETIME", TypeInferrer.infer(List.of("2026-09-01 10:00:00")));
        assertEquals("VARCHAR(1024)", TypeInferrer.infer(List.of("a", "1")));
        assertEquals("VARCHAR(1024)", TypeInferrer.infer(java.util.Arrays.asList("", null)));
    }

    @Test
    void parsesSimpleCsvWithHeader() throws IOException {
        ParsedTable t = csv("province,cnt,dt\nSichuan,10,2026-09-01\nBeijing,20,2026-09-02\n", 100);
        assertEquals(3, t.columns().size());
        assertEquals("province", t.columns().get(0).name());
        assertEquals("BIGINT", t.columns().get(1).sqlType());
        assertEquals("DATE", t.columns().get(2).sqlType());
        assertEquals(2, t.rows().size());
        assertEquals("Sichuan", t.rows().get(0).get(0));
    }

    @Test
    void handlesQuotedCommaAndNewline() throws IOException {
        ParsedTable t = csv("name,note\n\"A, B\",\"line1\nline2\"\nplain,ok\n", 100);
        assertEquals(2, t.rows().size());
        assertEquals("A, B", t.rows().get(0).get(0));
        assertEquals("line1\nline2", t.rows().get(0).get(1));
        assertEquals("plain", t.rows().get(1).get(0));
    }

    @Test
    void sanitizesChineseHeadersToFallbacks() throws IOException {
        ParsedTable t = csv("省份,在订用户数\n四川,10\n", 100);
        assertEquals("col_1", t.columns().get(0).name());
        assertEquals("col_2", t.columns().get(1).name());
        assertEquals("省份", t.columns().get(0).originalName());
    }

    @Test
    void truncatesBeyondRowLimit() throws IOException {
        ParsedTable t = csv("a,b\n1,2\n3,4\n5,6\n", 2);
        assertEquals(2, t.rows().size());
        assertEquals(3, t.totalRows());
        assertEquals(true, t.truncated());
    }

    @Test
    void rejectsEmptyCsv() {
        assertThrows(IOException.class, () -> csv("", 100));
    }

    @Test
    void sanitizeAndDedupeIdentifiers() {
        assertEquals("orders_2025", Identifiers.sanitize("Orders 2025!", "x"));
        assertEquals("col_3", Identifiers.sanitize("中文", "col_3"));
        assertEquals("_1abc", Identifiers.sanitize("1abc", "x"));
        assertEquals(List.of("a", "a_2", "a_3"), Identifiers.dedupe(List.of("a", "a", "a")));
    }

    @Test
    void columnSchemaRecordKeepsOriginalName() {
        ColumnSchema c = new ColumnSchema("col_1", "省份", "VARCHAR(1024)", true, "省份");
        assertEquals("省份", c.originalName());
        assertEquals("VARCHAR(1024)", c.sqlType());
        assertEquals("省份", c.description());
    }
}
