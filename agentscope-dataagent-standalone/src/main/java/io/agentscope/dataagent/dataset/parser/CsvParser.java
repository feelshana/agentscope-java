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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Parses an uploaded {@code .csv} (RFC 4180 subset: quoted fields with embedded commas / newlines
 * and {@code ""} escapes) into a {@link ParsedTable}. The first record is the header.
 */
@Component
public class CsvParser implements FileParser {

    private enum State {
        FIELD_START,
        UNQUOTED,
        QUOTED,
        QUOTE_IN_QUOTED
    }

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".csv");
    }

    @Override
    public ParsedTable parse(InputStream input, String fileName, int rowLimit) throws IOException {
        List<List<String>> records = readRecords(readAll(input));
        if (records.isEmpty()) {
            throw new IOException("CSV file is empty: " + fileName);
        }
        List<String> header = records.get(0);
        int columnCount = header.size();
        if (columnCount == 0) {
            throw new IOException("CSV header has no columns: " + fileName);
        }

        List<String> names = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            names.add(Identifiers.sanitize(header.get(i), "col_" + (i + 1)));
        }
        names = Identifiers.dedupe(names);

        List<List<String>> rows = new ArrayList<>();
        long total = 0;
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            if (rec.stream().allMatch(v -> v == null || v.isBlank())) {
                continue;
            }
            total++;
            if (rows.size() >= rowLimit) {
                continue;
            }
            List<String> values = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                String v = i < rec.size() ? rec.get(i) : null;
                values.add(v == null || v.isBlank() ? null : v);
            }
            rows.add(values);
        }

        List<ColumnSchema> columns = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            final int idx = i;
            List<String> sample =
                    rows.stream()
                            .map(row -> idx < row.size() ? row.get(idx) : null)
                            .collect(Collectors.toList());
            columns.add(
                    new ColumnSchema(
                            names.get(i),
                            header.get(i),
                            TypeInferrer.infer(sample),
                            true,
                            header.get(i)));
        }
        return new ParsedTable(columns, rows, total, total > rowLimit);
    }

    private String readAll(InputStream input) throws IOException {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String text = reader.lines().collect(Collectors.joining("\n"));
            return text.isEmpty() ? text : (text.charAt(0) == '﻿' ? text.substring(1) : text);
        }
    }

    private List<List<String>> readRecords(String text) {
        // Normalise line endings; quoted-field newlines become \n too, which is acceptable here.
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean fieldQuoted = false;
        State state = State.FIELD_START;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (state) {
                case FIELD_START:
                    if (ch == '"') {
                        state = State.QUOTED;
                        fieldQuoted = true;
                    } else if (ch == ',') {
                        current.add("");
                    } else if (ch == '\n') {
                        current.add("");
                        records.add(current);
                        current = new ArrayList<>();
                    } else {
                        field.append(ch);
                        state = State.UNQUOTED;
                    }
                    break;
                case UNQUOTED:
                    if (ch == ',') {
                        current.add(field.toString().trim());
                        field.setLength(0);
                        fieldQuoted = false;
                        state = State.FIELD_START;
                    } else if (ch == '\n') {
                        current.add(field.toString().trim());
                        field.setLength(0);
                        fieldQuoted = false;
                        records.add(current);
                        current = new ArrayList<>();
                        state = State.FIELD_START;
                    } else {
                        field.append(ch);
                    }
                    break;
                case QUOTED:
                    if (ch == '"') {
                        state = State.QUOTE_IN_QUOTED;
                    } else {
                        field.append(ch);
                    }
                    break;
                case QUOTE_IN_QUOTED:
                    if (ch == '"') {
                        field.append('"');
                        state = State.QUOTED;
                    } else if (ch == ',') {
                        current.add(field.toString());
                        field.setLength(0);
                        fieldQuoted = false;
                        state = State.FIELD_START;
                    } else if (ch == '\n') {
                        current.add(field.toString());
                        field.setLength(0);
                        fieldQuoted = false;
                        records.add(current);
                        current = new ArrayList<>();
                        state = State.FIELD_START;
                    } else {
                        field.append(ch);
                        state = State.UNQUOTED;
                    }
                    break;
            }
        }
        // flush trailing field / record
        if (state != State.FIELD_START || field.length() > 0 || !current.isEmpty()) {
            current.add(fieldQuoted ? field.toString() : field.toString().trim());
            records.add(current);
        }
        records.removeIf(rec -> rec.size() == 1 && (rec.get(0) == null || rec.get(0).isBlank()));
        return records;
    }
}
