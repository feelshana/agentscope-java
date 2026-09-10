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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Infers a MySQL column type from a sample of canonical string values. Deliberately conservative:
 * anything ambiguous falls back to {@code VARCHAR(1024)} so ingestion never fails on odd data.
 */
public final class TypeInferrer {

    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,18}");
    private static final int SAMPLE = 200;

    private static final List<DateTimeFormatter> DATES =
            List.of(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                    DateTimeFormatter.ofPattern("yyyy-M-d"),
                    DateTimeFormatter.ofPattern("yyyy/M/d"));

    private static final List<DateTimeFormatter> DATETIMES =
            List.of(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    private TypeInferrer() {}

    public static String infer(List<String> samples) {
        List<String> vals =
                samples.stream().filter(s -> s != null && !s.isBlank()).limit(SAMPLE).toList();
        if (vals.isEmpty()) {
            return "VARCHAR(1024)";
        }
        if (vals.stream().allMatch(s -> INTEGER.matcher(s).matches())) {
            return "BIGINT";
        }
        if (vals.stream().allMatch(TypeInferrer::isDecimal)) {
            return "DECIMAL(18,6)";
        }
        if (vals.stream().allMatch(TypeInferrer::isDate)) {
            return "DATE";
        }
        if (vals.stream().allMatch(TypeInferrer::isDateTime)) {
            return "DATETIME";
        }
        return "VARCHAR(1024)";
    }

    private static boolean isDecimal(String s) {
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDate(String s) {
        for (DateTimeFormatter f : DATES) {
            try {
                LocalDate.parse(s, f);
                return true;
            } catch (RuntimeException ignored) {
                // try next pattern
            }
        }
        return false;
    }

    private static boolean isDateTime(String s) {
        for (DateTimeFormatter f : DATETIMES) {
            try {
                LocalDateTime.parse(s, f);
                return true;
            } catch (RuntimeException ignored) {
                // try next pattern
            }
        }
        return false;
    }
}
