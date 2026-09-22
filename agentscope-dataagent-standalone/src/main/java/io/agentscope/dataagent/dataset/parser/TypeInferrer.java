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
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Infers a MySQL column type from a sample of canonical string values. Deliberately conservative:
 * anything ambiguous falls back to {@code VARCHAR(1024)} so ingestion never fails on odd data.
 *
 * <p>Date / datetime detection runs before numeric detection so that compact digit-only dates such
 * as {@code yyyyMMdd} (which would otherwise match the integer rule) are recognized as dates.
 * Single-letter pattern tokens ({@code M/d/H/m/s}) accept both padded and unpadded values, so
 * Excel-rendered values like {@code 2026/9/7 16:31} are detected just like
 * {@code 2026-09-07 16:31:05}.
 */
public final class TypeInferrer {

    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,18}");
    private static final int SAMPLE = 200;

    /** Compact date {@code yyyyMMdd}; built with fixed-width fields because adjacent numeric
     * pattern letters parse greedily and would otherwise fail. */
    private static final DateTimeFormatter COMPACT_DATE =
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendValue(ChronoField.MONTH_OF_YEAR, 2)
                    .appendValue(ChronoField.DAY_OF_MONTH, 2)
                    .toFormatter();

    /** Compact datetime {@code yyyyMMddHHmmss}. */
    private static final DateTimeFormatter COMPACT_DATETIME =
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendValue(ChronoField.MONTH_OF_YEAR, 2)
                    .appendValue(ChronoField.DAY_OF_MONTH, 2)
                    .appendValue(ChronoField.HOUR_OF_DAY, 2)
                    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                    .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
                    .toFormatter();

    /** Compact date + separated time {@code yyyyMMdd HH:mm:ss}. */
    private static final DateTimeFormatter COMPACT_DATETIME_SPACE =
            new DateTimeFormatterBuilder()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendValue(ChronoField.MONTH_OF_YEAR, 2)
                    .appendValue(ChronoField.DAY_OF_MONTH, 2)
                    .appendLiteral(' ')
                    .appendPattern("HH:mm:ss")
                    .toFormatter();

    private static final List<DateTimeFormatter> DATES =
            List.of(
                    DateTimeFormatter.ofPattern("yyyy-M-d"),
                    DateTimeFormatter.ofPattern("yyyy/M/d"),
                    DateTimeFormatter.ofPattern("yyyy.M.d"),
                    COMPACT_DATE);

    private static final List<DateTimeFormatter> DATETIMES =
            List.of(
                    DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
                    DateTimeFormatter.ofPattern("yyyy/M/d H:m:s"),
                    DateTimeFormatter.ofPattern("yyyy.M.d H:m:s"),
                    DateTimeFormatter.ofPattern("yyyy-M-d H:m"),
                    DateTimeFormatter.ofPattern("yyyy/M/d H:m"),
                    DateTimeFormatter.ofPattern("yyyy.M.d H:m"),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                    COMPACT_DATETIME,
                    COMPACT_DATETIME_SPACE);

    private TypeInferrer() {}

    public static String infer(List<String> samples) {
        List<String> vals =
                samples.stream().filter(s -> s != null && !s.isBlank()).limit(SAMPLE).toList();
        if (vals.isEmpty()) {
            return "VARCHAR(1024)";
        }
        if (vals.stream().allMatch(TypeInferrer::isDateTime)) {
            return "DATETIME";
        }
        if (vals.stream().allMatch(TypeInferrer::isDate)) {
            return "DATE";
        }
        if (vals.stream().allMatch(s -> INTEGER.matcher(s).matches())) {
            return "BIGINT";
        }
        if (vals.stream().allMatch(TypeInferrer::isDecimal)) {
            return "DECIMAL(18,6)";
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

    /**
     * Parses a date string against every supported date pattern. Shared with the write path so the
     * value detected as {@code DATE} during sampling can always be re-parsed at insert time.
     *
     * @throws IllegalArgumentException if no pattern matches
     */
    public static LocalDate parseDate(String v) {
        String s = v.trim();
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(s, f);
            } catch (RuntimeException ignored) {
                // try next pattern
            }
        }
        throw new IllegalArgumentException("unparseable date: " + v);
    }

    /**
     * Parses a datetime string against every supported datetime pattern, falling back to a bare
     * date at midnight. Shared with the write path so the value detected as {@code DATETIME} during
     * sampling can always be re-parsed at insert time.
     *
     * @throws IllegalArgumentException if no pattern matches
     */
    public static LocalDateTime parseDateTime(String v) {
        String s = v.trim();
        for (DateTimeFormatter f : DATETIMES) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (RuntimeException ignored) {
                // try next pattern
            }
        }
        // a bare date is a valid datetime at midnight
        return parseDate(s).atStartOfDay();
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
