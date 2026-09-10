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
package io.agentscope.dataagent.dataset.parser;

import io.agentscope.dataagent.dataset.Identifiers;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * Parses the first sheet of an uploaded {@code .xlsx} / {@code .xls} into a {@link ParsedTable}.
 * The first row is treated as the header; cell values are canonicalised to strings (dates to ISO)
 * so type inference and SQL insertion share a single code path. Rows beyond {@code rowLimit} are
 * counted but not retained, bounding memory for large uploads.
 */
@Component
public class ExcelParser implements FileParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public boolean supports(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        return n.endsWith(".xlsx") || n.endsWith(".xls");
    }

    @Override
    public ParsedTable parse(InputStream input, String fileName, int rowLimit) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IOException("Excel file has no worksheet: " + fileName);
            }
            Iterator<Row> it = sheet.rowIterator();
            if (!it.hasNext()) {
                throw new IOException("Excel worksheet is empty: " + fileName);
            }
            Row headerRow = it.next();
            int columnCount = Math.max(headerRow.getLastCellNum(), 0);
            if (columnCount == 0) {
                throw new IOException("Excel header row has no cells: " + fileName);
            }

            List<String> originals = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                originals.add(cellText(headerRow.getCell(i)));
            }
            List<String> names = Identifiers.dedupe(sanitizeAll(originals));

            List<List<String>> rows = new ArrayList<>();
            long total = 0;
            while (it.hasNext()) {
                Row row = it.next();
                total++;
                if (rows.size() >= rowLimit) {
                    continue;
                }
                List<String> values = new ArrayList<>(columnCount);
                for (int i = 0; i < columnCount; i++) {
                    values.add(canonical(row.getCell(i)));
                }
                rows.add(values);
            }

            return new ParsedTable(
                    buildColumns(names, originals, rows), rows, total, total > rowLimit);
        }
    }

    private List<String> sanitizeAll(List<String> originals) {
        List<String> out = new ArrayList<>(originals.size());
        for (int i = 0; i < originals.size(); i++) {
            out.add(Identifiers.sanitize(originals.get(i), "col_" + (i + 1)));
        }
        return out;
    }

    private List<ColumnSchema> buildColumns(
            List<String> names, List<String> originals, List<List<String>> rows) {
        List<ColumnSchema> columns = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            List<String> sample = new ArrayList<>();
            for (List<String> row : rows) {
                sample.add(i < row.size() ? row.get(i) : null);
            }
            columns.add(
                    new ColumnSchema(
                            names.get(i),
                            originals.get(i),
                            TypeInferrer.infer(sample),
                            true,
                            originals.get(i)));
        }
        return columns;
    }

    private String cellText(Cell cell) {
        String v = canonical(cell);
        return v == null ? "" : v;
    }

    private String canonical(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        switch (cell.getCellType()) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime ldt = cell.getLocalDateTimeCellValue();
                    if (ldt == null) return null;
                    boolean midnight =
                            ldt.getHour() == 0 && ldt.getMinute() == 0 && ldt.getSecond() == 0;
                    return midnight ? ldt.format(DATE) : ldt.format(DATETIME);
                }
                double d = cell.getNumericCellValue();
                if (!Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
                    return String.valueOf((long) d);
                }
                return String.valueOf(d);
            case STRING:
                String s = cell.getStringCellValue();
                return s == null || s.isBlank() ? null : s.trim();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    String f = cell.getStringCellValue();
                    return f == null || f.isBlank() ? null : f.trim();
                } catch (RuntimeException e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return null;
        }
    }
}
