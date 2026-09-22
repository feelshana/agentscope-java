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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.dataset.parser.ExcelParser;
import io.agentscope.dataagent.dataset.parser.ParsedTable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelParserTest {

    private byte[] sampleXlsx() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("customer");
            header.createCell(1).setCellValue("amount");
            for (int i = 1; i <= 3; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue("c" + i);
                r.createCell(1).setCellValue(i * 10.5);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void parsesFirstSheetWithTypes() throws IOException {
        ParsedTable t =
                new ExcelParser().parse(new ByteArrayInputStream(sampleXlsx()), "orders.xlsx", 100);
        assertEquals(2, t.columns().size());
        assertEquals("customer", t.columns().get(0).name());
        assertEquals("VARCHAR(1024)", t.columns().get(0).sqlType());
        assertEquals("amount", t.columns().get(1).name());
        assertEquals("DECIMAL(18,6)", t.columns().get(1).sqlType());
        assertEquals(3, t.rows().size());
        assertEquals("c1", t.rows().get(0).get(0));
        assertEquals("10.5", t.rows().get(0).get(1));
        assertEquals(3, t.totalRows());
        assertTrue(!t.truncated());
    }

    @Test
    void supportsOnlyExcelExtensions() {
        ExcelParser p = new ExcelParser();
        assertTrue(p.supports("a.xlsx"));
        assertTrue(p.supports("A.XLSX"));
        assertTrue(!p.supports("a.csv"));
    }

    @Test
    void columnSchemaFromExcelKeepsHeader() throws IOException {
        ParsedTable t =
                new ExcelParser().parse(new ByteArrayInputStream(sampleXlsx()), "orders.xlsx", 100);
        ColumnSchema c = t.columns().get(1);
        assertEquals("amount", c.originalName());
    }
}
