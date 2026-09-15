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
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Parses the first sheet of an uploaded {@code .xlsx} / {@code .xls} into a {@link ParsedTable}.
 *
 * <p>For {@code .xlsx} files, a SAX-based streaming parser is used that processes rows one at a
 * time, keeping memory usage proportional to a single row rather than the entire workbook. For
 * {@code .xls} (BIFF8 binary format), the classic DOM parser ({@link WorkbookFactory}) is used as
 * a fallback.
 *
 * <p>The first row is treated as the header; cell values are canonicalised to strings (dates to
 * ISO) so type inference and SQL insertion share a single code path. Rows beyond {@code rowLimit}
 * are counted but not retained, bounding memory for large uploads.
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
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".xlsx")) {
            return parseXlsxStreaming(input, fileName, rowLimit);
        }
        // .xls (BIFF8) fallback: DOM mode
        IOUtils.setByteArrayMaxOverride(512_000_000);
        return parseDom(input, fileName, rowLimit);
    }

    // -------------------------------------------------------------------------
    //  SAX streaming parser for .xlsx (OOXML)
    // -------------------------------------------------------------------------

    private ParsedTable parseXlsxStreaming(InputStream input, String fileName, int rowLimit)
            throws IOException {
        IOUtils.setByteArrayMaxOverride(1_000_000_000);
        try (OPCPackage pkg = OPCPackage.open(input)) {
            ReadOnlySharedStringsTable sst = new ReadOnlySharedStringsTable(pkg);
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable stylesTable = reader.getStylesTable();

            StreamingHandler handler = new StreamingHandler(sst, stylesTable, rowLimit);

            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            SAXParser saxParser = factory.newSAXParser();

            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            if (!sheets.hasNext()) {
                throw new IOException("Excel file has no worksheet: " + fileName);
            }
            try (InputStream sheetStream = sheets.next()) {
                saxParser.parse(sheetStream, handler);
            }

            if (handler.headerRow == null || handler.headerRow.isEmpty()) {
                throw new IOException("Excel header row is empty: " + fileName);
            }

            List<String> originals = new ArrayList<>(handler.headerRow);
            List<String> names = Identifiers.dedupe(sanitizeAll(originals));
            int columnCount = names.size();

            return new ParsedTable(
                    buildColumns(names, originals, handler.dataRows, columnCount),
                    handler.dataRows,
                    handler.totalRows,
                    handler.totalRows > rowLimit);
        } catch (SAXException e) {
            throw new IOException("Failed to parse Excel (streaming): " + fileName, e);
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Failed to open Excel package: " + fileName, e);
        }
    }

    /**
     * SAX handler that processes one sheet's XML stream row by row. Only the current row is kept
     * in memory at any time.
     */
    private static class StreamingHandler extends DefaultHandler {

        private final ReadOnlySharedStringsTable sst;
        private final StylesTable stylesTable;
        private final int rowLimit;

        List<String> headerRow;
        final List<List<String>> dataRows = new ArrayList<>();
        long totalRows = 0;

        // current-row state
        private List<String> currentRowValues;
        private int currentColumnCount = -1;
        private boolean inCellValue;
        private StringBuilder cellValueBuf = new StringBuilder();
        private String cellType;
        private int cellStyleIdx;
        private int cellColumnIdx;

        StreamingHandler(ReadOnlySharedStringsTable sst, StylesTable stylesTable, int rowLimit) {
            this.sst = sst;
            this.stylesTable = stylesTable;
            this.rowLimit = rowLimit;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs)
                throws SAXException {
            switch (localName) {
                case "row" -> {
                    currentRowValues = new ArrayList<>();
                    if (currentColumnCount < 0) {
                        // Will be determined after header is fully parsed
                    }
                }
                case "c" -> {
                    cellType = attrs.getValue("t");
                    cellColumnIdx = parseColumnIndex(attrs.getValue("r"));
                    String sAttr = attrs.getValue("s");
                    cellStyleIdx =
                            (sAttr != null && !sAttr.isEmpty()) ? Integer.parseInt(sAttr) : 0;
                    cellValueBuf.setLength(0);
                    inCellValue = false;
                }
                case "v", "t" -> {
                    inCellValue = true;
                    cellValueBuf.setLength(0);
                }
                default -> {
                    // ignore
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            switch (localName) {
                case "v", "t" -> inCellValue = false;
                case "c" -> {
                    if (currentRowValues != null) {
                        String value =
                                resolveCellValue(
                                        cellType,
                                        cellValueBuf.toString(),
                                        cellStyleIdx,
                                        sst,
                                        stylesTable);
                        // Pad with nulls for any skipped columns
                        while (currentRowValues.size() <= cellColumnIdx) {
                            currentRowValues.add(null);
                        }
                        currentRowValues.set(cellColumnIdx, value);
                    }
                }
                case "row" -> {
                    if (currentRowValues != null) {
                        if (headerRow == null) {
                            headerRow = currentRowValues;
                            currentColumnCount = headerRow.size();
                        } else {
                            totalRows++;
                            if (dataRows.size() < rowLimit) {
                                // Pad or trim to match header column count
                                while (currentRowValues.size() < currentColumnCount) {
                                    currentRowValues.add(null);
                                }
                                if (currentRowValues.size() > currentColumnCount) {
                                    currentRowValues =
                                            new ArrayList<>(
                                                    currentRowValues.subList(
                                                            0, currentColumnCount));
                                }
                                dataRows.add(currentRowValues);
                            }
                        }
                    }
                    currentRowValues = null;
                }
                default -> {
                    // ignore
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (inCellValue) {
                cellValueBuf.append(ch, start, length);
            }
        }
    }

    // -------------------------------------------------------------------------
    //  DOM parser fallback for .xls (BIFF8)
    // -------------------------------------------------------------------------

    private ParsedTable parseDom(InputStream input, String fileName, int rowLimit)
            throws IOException {
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
                originals.add(domCellText(headerRow.getCell(i)));
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
                    values.add(domCanonical(row.getCell(i)));
                }
                rows.add(values);
            }

            return new ParsedTable(
                    buildColumns(names, originals, rows, columnCount),
                    rows,
                    total,
                    total > rowLimit);
        }
    }

    // -------------------------------------------------------------------------
    //  Shared utilities
    // -------------------------------------------------------------------------

    private List<String> sanitizeAll(List<String> originals) {
        List<String> out = new ArrayList<>(originals.size());
        for (int i = 0; i < originals.size(); i++) {
            out.add(Identifiers.sanitize(originals.get(i), "col_" + (i + 1)));
        }
        return out;
    }

    private List<ColumnSchema> buildColumns(
            List<String> names, List<String> originals, List<List<String>> rows, int columnCount) {
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

    // -------------------------------------------------------------------------
    //  Cell value resolution (shared between streaming and DOM)
    // -------------------------------------------------------------------------

    /**
     * Resolve a cell's text value in SAX streaming mode. Handles shared strings, inline strings,
     * numeric values (with date-format detection), booleans, and best-effort formula results.
     */
    private static String resolveCellValue(
            String type,
            String rawValue,
            int styleIdx,
            ReadOnlySharedStringsTable sst,
            StylesTable stylesTable) {
        if (rawValue == null || rawValue.isEmpty()) {
            return null;
        }
        try {
            if ("s".equals(type)) {
                // Shared string
                int idx = Integer.parseInt(rawValue);
                String s = sst.getItemAt(idx).getString();
                return (s == null || s.isBlank()) ? null : s.trim();
            }
            if ("str".equals(type) || "inlineStr".equals(type)) {
                return rawValue.isBlank() ? null : rawValue.trim();
            }
            if ("b".equals(type)) {
                return "1".equals(rawValue) ? "true" : "false";
            }
            if ("e".equals(type)) {
                return null; // error cell
            }
            // Numeric (or formula pre-calculated value)
            double d = Double.parseDouble(rawValue);
            // Check if the cell style indicates a date format
            boolean isDate = false;
            if (stylesTable != null && styleIdx > 0) {
                CellStyle cs = stylesTable.getStyleAt(styleIdx);
                String fmtStr = cs.getDataFormatString();
                int fmtIdx = cs.getDataFormat();
                isDate = DateUtil.isADateFormat(fmtIdx, fmtStr);
            } else if (styleIdx > 0) {
                // Built-in date formats (14-22, 27-36, 45-47, 50-58)
                isDate = DateUtil.isADateFormat(styleIdx, null);
            }
            if (isDate) {
                LocalDateTime ldt = DateUtil.getLocalDateTime(d);
                if (ldt == null) return null;
                boolean midnight =
                        ldt.getHour() == 0 && ldt.getMinute() == 0 && ldt.getSecond() == 0;
                return midnight ? ldt.format(DATE) : ldt.format(DATETIME);
            }
            if (!Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        } catch (NumberFormatException e) {
            return rawValue.isBlank() ? null : rawValue.trim();
        }
    }

    // -------------------------------------------------------------------------
    //  DOM-mode cell text helpers (for .xls fallback)
    // -------------------------------------------------------------------------

    private String domCellText(Cell cell) {
        String v = domCanonical(cell);
        return v == null ? "" : v;
    }

    private String domCanonical(Cell cell) {
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

    // -------------------------------------------------------------------------
    //  Column reference utilities
    // -------------------------------------------------------------------------

    /**
     * Parse an Excel cell reference like "AB12" and return the zero-based column index. Returns
     * -1 if the reference is null or cannot be parsed.
     */
    private static int parseColumnIndex(String cellRef) {
        if (cellRef == null || cellRef.isEmpty()) {
            return -1;
        }
        int idx = 0;
        for (int i = 0; i < cellRef.length(); i++) {
            char c = cellRef.charAt(i);
            if (Character.isLetter(c)) {
                idx = idx * 26 + (Character.toUpperCase(c) - 'A' + 1);
            } else {
                break;
            }
        }
        return idx - 1;
    }
}
