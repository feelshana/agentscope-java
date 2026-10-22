package io.agentscope.dataagent.dataset;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Counts all nonempty sampled values, rather than only distinct display examples. */
public final class TemporalSamples {
    private static final List<String> FORMATS =
            List.of(
                    "uuuu-MM-dd",
                    "uuuu/MM/dd",
                    "uuuuMMdd",
                    "uuuu-MM-dd HH:mm:ss",
                    "uuuu-MM-dd HH:mm",
                    "uuuu-MM-dd'T'HH:mm:ss",
                    "uuuu-MM",
                    // Text dates without zero padding, e.g. 2026/10/7 9:05 (exported reports).
                    // Listed after the padded forms, so padded values keep their exact format.
                    "uuuu/M/d H:mm:ss",
                    "uuuu/M/d H:mm",
                    "uuuu/M/d",
                    "uuuu-M-d H:mm:ss",
                    "uuuu-M-d H:mm",
                    "uuuu-M-d",
                    // Compact date-times without separators, e.g. 20261007093000.
                    "uuuuMMddHHmmss",
                    "uuuuMMddHHmm",
                    "uuuuMMdd HHmmss",
                    "uuuuMMdd HH:mm:ss");

    /** Numeric columns (e.g. INT dt = 20261007) may only hold separator-free forms. */
    private static final java.util.Set<String> NUMERIC_FORMATS =
            java.util.Set.of("uuuuMMdd", "uuuuMMddHHmmss", "uuuuMMddHHmm");

    private int nonempty;
    private final int[] matches = new int[FORMATS.size()];

    public void add(String value) {
        if (value == null || value.isBlank()) return;
        nonempty++;
        for (int i = 0; i < FORMATS.size(); i++) {
            try {
                var f =
                        DateTimeFormatter.ofPattern(FORMATS.get(i))
                                .withResolverStyle(ResolverStyle.STRICT);
                if (FORMATS.get(i).equals("uuuu-MM")) YearMonth.parse(value.trim(), f);
                else if (FORMATS.get(i).contains("HH")) LocalDateTime.parse(value.trim(), f);
                else LocalDate.parse(value.trim(), f);
                matches[i]++;
            } catch (DateTimeException ignored) {
            }
        }
    }

    public String format(String type) {
        String t = Objects.toString(type, "").toLowerCase(Locale.ROOT);
        if (t.matches(".*(timestamp|datetime|date).*")) return "NATIVE";
        boolean text = t.matches(".*(char|text|string).*");
        // Whole numbers only: a strictly valid 8/12/14-digit date in most samples is a date key,
        // while ordinary ids fail the strict calendar check.
        boolean number = t.matches(".*(int|number|numeric|long).*");
        if (!text && !number) return null;
        for (int i = 0; i < matches.length; i++)
            if (nonempty > 0
                    && matches[i] * 100 > nonempty * 60
                    && (text || NUMERIC_FORMATS.contains(FORMATS.get(i)))) return FORMATS.get(i);
        return null;
    }
}
