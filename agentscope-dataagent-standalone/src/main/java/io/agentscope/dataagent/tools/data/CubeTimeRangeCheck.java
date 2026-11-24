package io.agentscope.dataagent.tools.data;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Wren expands a {@code query_cube} time range {@code name:granularity:start,end} to {@code >=
 * start AND < end}, so the end is exclusive and a range whose end is not after its start selects
 * nothing. Such a call is refused with the correct range; arguments are never rewritten.
 */
final class CubeTimeRangeCheck {
    private CubeTimeRangeCheck() {}

    /** Returns the refusal message, or null when the range is absent, unparsable or non-empty. */
    static String check(Map<String, Object> args) {
        Object value = args == null ? null : args.get("time_dimension");
        if (!(value instanceof String spec)) return null;
        String[] parts = spec.strip().split(":", 3);
        if (parts.length < 3) return null;
        String[] range = parts[2].split(",", -1);
        if (range.length != 2) return null;
        LocalDate start = date(range[0]);
        LocalDate end = date(range[1]);
        if (start == null || end == null || end.isAfter(start)) return null;
        return "time_dimension 的时间范围按左闭右开处理：只包含开始日期、不包含结束日期，"
                + spec.strip()
                + " 选不到任何数据。结束日期要写下一个周期的第一天，例如查 "
                + start
                + " 所在的这一"
                + unitName(parts[1])
                + "应写 "
                + parts[0]
                + ":"
                + parts[1]
                + ":"
                + start
                + ","
                + next(start, parts[1])
                + "。";
    }

    private static LocalDate date(String text) {
        String s = text.strip();
        if (s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate next(LocalDate start, String granularity) {
        return switch (granularity.strip().toLowerCase()) {
            case "year" -> start.plusYears(1);
            case "quarter" -> start.plusMonths(3);
            case "month" -> start.plusMonths(1);
            case "week" -> start.plusWeeks(1);
            default -> start.plusDays(1);
        };
    }

    private static String unitName(String granularity) {
        return switch (granularity.strip().toLowerCase()) {
            case "year" -> "年";
            case "quarter" -> "季度";
            case "month" -> "个月";
            case "week" -> "周";
            default -> "天";
        };
    }
}
