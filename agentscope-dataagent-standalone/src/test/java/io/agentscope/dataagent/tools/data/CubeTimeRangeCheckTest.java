package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Wren's query_cube time range excludes its end, so an end not after the start selects nothing. */
class CubeTimeRangeCheckTest {
    @Test
    void emptyRangesAreRefusedWithTheNextPeriodAsEnd() {
        assertThat(check("period:month:2026-10-01,2026-10-01"))
                .contains("不包含结束日期", "period:month:2026-10-01,2026-11-01");
        assertThat(check("period:day:2026-10-05,2026-10-05"))
                .contains("period:day:2026-10-05,2026-10-06");
        assertThat(check("period:quarter:2026-10-01,2026-07-01"))
                .contains("period:quarter:2026-10-01,2027-01-01");
        assertThat(check("period:year:2026-01-01T00:00:00,2026-01-01"))
                .contains("period:year:2026-01-01,2027-01-01");
    }

    @Test
    void nonEmptyOrAbsentRangesPass() {
        assertThat(check("period:month:2026-10-01,2026-11-01")).isNull();
        assertThat(check("period:month")).isNull();
        assertThat(check("period:month:last month")).isNull();
        assertThat(CubeTimeRangeCheck.check(Map.of("cube", "c"))).isNull();
        assertThat(CubeTimeRangeCheck.check(null)).isNull();
    }

    private static String check(String timeDimension) {
        return CubeTimeRangeCheck.check(Map.of("time_dimension", timeDimension));
    }
}
