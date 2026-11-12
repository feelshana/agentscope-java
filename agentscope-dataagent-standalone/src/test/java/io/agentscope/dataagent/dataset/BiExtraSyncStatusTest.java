package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BiExtraSyncStatusTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-12T02:00:00Z"), ZoneOffset.UTC);

    Map<String, String> fixture() {
        return new HashMap<>(
                Map.of(
                        "owner",
                        "\"dataagent-bi-extra-v1\"",
                        "paused",
                        "false",
                        "last_run",
                        """
                        {"action":"sync","outcome":"SUCCESS_WITH_GAPS","started_at":"2026-11-12T02:15:00+08:00","finished_at":"2026-11-12T03:15:00+08:00","message":"SECRET","tables":["bi_page_click_log"]}
                        """,
                        "table:bi_page_click_log",
                        """
                        {"requested_from":"2026-01-01","available_from":"2026-10-01","coverage_from":"2026-10-01","success_until":"2026-11-12","gaps":[{"from":"2026-01-01","until":"2026-10-01","reason":"SOURCE_HISTORY_UNAVAILABLE"}]}
                        """,
                        "table:bi_iam_user",
                        """
                        {"kind":"current_snapshot","refreshed_at":"2026-11-12T03:00:00+08:00","count":42,"digest":"SECRET"}
                        """));
    }

    @Test
    void partialSuccessShowsPerTableCoverageAndGapsWithoutLeakingPrivateState() throws Exception {
        var view = BiExtraSyncStatus.project(fixture(), false, mapper, clock);
        assertEquals("SUCCESS_WITH_GAPS", view.state());
        assertTrue(view.hasGaps());
        assertEquals(6, view.tables().size());
        var click = view.tables().get(0);
        assertEquals("2026-11-11", click.processedThrough());
        assertEquals("2026-09-30", click.gaps().get(0).through());
        assertEquals("GAPS", click.state());
        assertEquals("CURRENT", view.tables().get(1).state());
        assertEquals(42L, view.tables().get(1).rowCount());
        assertEquals("NOT_STARTED", view.tables().get(2).state());
        assertEquals(3600000L, view.lastRun().durationMs());
        assertFalse(mapper.writeValueAsString(view).contains("SECRET"));
    }

    @Test
    void verifyIsSeparateAndRunningLockWinsWithInterruptedAndPausedStates() throws Exception {
        var values = fixture();
        values.put("last_run", "{\"action\":\"verify\",\"outcome\":\"RUNNING\"}");
        assertEquals("VERIFY", BiExtraSyncStatus.project(values, true, mapper, clock).action());
        assertEquals("RUNNING", BiExtraSyncStatus.project(values, true, mapper, clock).state());
        assertEquals(
                "INTERRUPTED", BiExtraSyncStatus.project(values, false, mapper, clock).state());
        values.put("paused", "true");
        assertEquals("PAUSING", BiExtraSyncStatus.project(values, true, mapper, clock).state());
        assertEquals("PAUSED", BiExtraSyncStatus.project(values, false, mapper, clock).state());
    }

    @Test
    void onlyThreeReadQueriesAndOwnerMustMatchExtraTask() throws Exception {
        var con = mock(Connection.class);
        var statement = mock(Statement.class);
        when(con.createStatement()).thenReturn(statement);
        List<String> sqls = new ArrayList<>();
        var control = fixture();
        when(statement.executeQuery(anyString()))
                .thenAnswer(
                        inv -> {
                            String sql = inv.getArgument(0);
                            sqls.add(sql);
                            var rs = mock(ResultSet.class);
                            if (sql.startsWith("SELECT name")) {
                                var entries = new ArrayList<>(control.entrySet());
                                var pos = new java.util.concurrent.atomic.AtomicInteger(-1);
                                when(rs.next())
                                        .thenAnswer(x -> pos.incrementAndGet() < entries.size());
                                when(rs.getString(1))
                                        .thenAnswer(x -> entries.get(pos.get()).getKey());
                                when(rs.getString(2))
                                        .thenAnswer(x -> entries.get(pos.get()).getValue());
                            } else {
                                when(rs.next()).thenReturn(true, false);
                                when(rs.getInt(1)).thenReturn(1);
                            }
                            return rs;
                        });
        assertEquals("SUCCESS_WITH_GAPS", BiExtraSyncStatus.read(con, mapper, clock).state());
        verify(con).setReadOnly(true);
        verify(statement).setQueryTimeout(5);
        assertEquals(3, sqls.size());
        assertTrue(sqls.stream().allMatch(s -> s.startsWith("SELECT ")));
        assertTrue(
                sqls.stream()
                        .noneMatch(
                                s ->
                                        s.contains("FROM bi_sync_control")
                                                || s.contains("FROM bi_page_click_log")));
        control.put("owner", "\"dataagent-bi-sync-v2-basic\"");
        assertEquals("UNSUPPORTED", BiExtraSyncStatus.read(con, mapper, clock).state());
        var absent = mock(ResultSet.class);
        when(absent.next()).thenReturn(true);
        when(absent.getInt(1)).thenReturn(0);
        when(statement.executeQuery(anyString())).thenReturn(absent);
        assertEquals("NOT_INITIALIZED", BiExtraSyncStatus.read(con, mapper, clock).state());
    }

    @Test
    void failuresAndMalformedRecordsNeverExposeDriverOrJsonSecrets() throws Exception {
        var service =
                new BiSyncStatusService(
                        () -> {
                            throw new SQLException("password=SECRET");
                        },
                        mapper,
                        clock);
        assertEquals("UNAVAILABLE", service.extraView().state());
        assertFalse(mapper.writeValueAsString(service.extraView()).contains("SECRET"));
        var values = fixture();
        values.put("table:bi_page_click_log", "invalid SECRET");
        assertThrows(
                Exception.class, () -> BiExtraSyncStatus.project(values, false, mapper, clock));
        values.put("table:bi_page_click_log", "{\"success_until\":\"2026-12-31\"}");
        assertThrows(
                IllegalArgumentException.class,
                () -> BiExtraSyncStatus.project(values, false, mapper, clock));
    }
}
