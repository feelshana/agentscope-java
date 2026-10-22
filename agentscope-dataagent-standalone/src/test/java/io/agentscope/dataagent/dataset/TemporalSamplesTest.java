package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporalSamplesTest {
    @TempDir Path temp;

    @Test
    void strictDatesCountEverySampleAndDoNotTreatNumericIdsAsDates() {
        var dates = new TemporalSamples();
        for (int i = 0; i < 61; i++) dates.add("2026-10-01");
        for (int i = 0; i < 39; i++) dates.add("unknown");
        assertThat(dates.format("VARCHAR")).isEqualTo("uuuu-MM-dd");
        assertThat(dates.format("BIGINT")).isNull();
        var invalid = new TemporalSamples();
        invalid.add("2026-02-30");
        invalid.add(null);
        assertThat(invalid.format("TEXT")).isNull();
        assertThat(invalid.format("DATETIME")).isEqualTo("NATIVE");
    }

    @Test
    void unpaddedTextDatesAreRecognizedAndPaddedOnesKeepTheirExactFormat() {
        // 2026-10-22 log: visit_time stored as "2026/10/7 9:05" was not seen as a time column,
        // so the fact-table window dialog never appeared.
        var visits = new TemporalSamples();
        for (String v : new String[] {"2026/10/7 9:05", "2026/10/12 18:30", "2026/9/30 0:01"})
            visits.add(v);
        assertThat(visits.format("VARCHAR")).isEqualTo("uuuu/M/d H:mm");
        var days = new TemporalSamples();
        days.add("2026-1-5");
        days.add("2026-10-17");
        assertThat(days.format("TEXT")).isEqualTo("uuuu-M-d");
        var padded = new TemporalSamples();
        padded.add("2026/10/07");
        assertThat(padded.format("VARCHAR")).isEqualTo("uuuu/MM/dd");
        var names = new TemporalSamples();
        names.add("车载用户规模月累计日报");
        names.add("2026/13/40");
        assertThat(names.format("VARCHAR")).isNull();
    }

    @Test
    void compactDatesAndDateTimesWithoutSeparatorsAreRecognized() {
        var dt = new TemporalSamples();
        for (String v : new String[] {"20261007", "20260930", "20261101"}) dt.add(v);
        assertThat(dt.format("VARCHAR")).isEqualTo("uuuuMMdd");
        // A numeric partition key such as INT dt = 20261007 is a date too.
        assertThat(dt.format("INT")).isEqualTo("uuuuMMdd");
        assertThat(dt.format("BIGINT")).isEqualTo("uuuuMMdd");
        var stamps = new TemporalSamples();
        stamps.add("20261007093000");
        stamps.add("20261007235959");
        assertThat(stamps.format("VARCHAR")).isEqualTo("uuuuMMddHHmmss");
        assertThat(stamps.format("BIGINT")).isEqualTo("uuuuMMddHHmmss");
        var minutes = new TemporalSamples();
        minutes.add("202610070930");
        assertThat(minutes.format("CHAR(12)")).isEqualTo("uuuuMMddHHmm");
        var spaced = new TemporalSamples();
        spaced.add("20261007 093000");
        spaced.add("20261007 09:30:00");
        spaced.add("20261008 101500");
        assertThat(spaced.format("TEXT")).isEqualTo("uuuuMMdd HHmmss");
        // Ordinary ids fail the strict calendar check; spaced forms never apply to numbers.
        var ids = new TemporalSamples();
        for (String v : new String[] {"1001", "99999999", "20261340"}) ids.add(v);
        assertThat(ids.format("BIGINT")).isNull();
        assertThat(spaced.format("BIGINT")).isNull();
    }

    @Test
    void metadataEnrichmentPreservesConfirmedPropertiesAndIsIdempotent() throws Exception {
        Path file = temp.resolve("metadata.yml");
        Files.writeString(
                file,
                "name: users\n"
                        + "columns:\n"
                        + "- name: created\n"
                        + "  type: TIMESTAMP\n"
                        + "properties:\n"
                        + "  table_role: DIMENSION\n"
                        + "  table_role_confirmed: true\n"
                        + "  analysis_date_columns: [created]\n");
        assertThat(ModelTemporalMetadata.enrich(file, null)).isTrue();
        String after = Files.readString(file);
        assertThat(after)
                .contains(
                        "DIMENSION",
                        "table_role_confirmed: true",
                        "NATIVE",
                        "analysis_date_columns");
        assertThat(ModelTemporalMetadata.enrich(file, null)).isFalse();
        assertThat(Files.readString(file)).isEqualTo(after);
    }
}
