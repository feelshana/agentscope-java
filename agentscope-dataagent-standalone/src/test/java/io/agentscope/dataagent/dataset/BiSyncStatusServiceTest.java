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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BiSyncStatusServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T01:00:00Z"), ZoneOffset.UTC);

    private Map<String, String> values(String outcome) throws Exception {
        var values = new HashMap<String, String>();
        values.put("owner", "dataagent-bi-sync-v2-basic");
        values.put("success_until", "2026-11-03");
        values.put("bi_report_log", "2026-11-03");
        values.put("bi_dashboard_log", "2026-11-03");
        values.put(
                "last_run",
                mapper.writeValueAsString(
                        Map.of(
                                "outcome",
                                outcome,
                                "started_at",
                                "2026-11-02T18:00:00+00:00",
                                "failure_phase",
                                "SOURCE_CONNECT",
                                "duration_ms",
                                1234,
                                "message",
                                "password=SHOULD_NOT_APPEAR")));
        return values;
    }

    private BiSyncStatusService service() {
        return new BiSyncStatusService(
                () -> {
                    throw new SQLException();
                },
                mapper,
                clock);
    }

    @Test
    void datesAreInclusiveForHumansAndFailuresExposeOnlyKnownMessages() throws Exception {
        var view = service().project(values("FAILED"), false);
        assertEquals("2026-11-02", view.completedThrough());
        assertEquals("CURRENT", view.freshness());
        assertEquals("FAILED", view.state());
        assertEquals(1234L, view.lastRun().durationMs());
        assertTrue(view.message().contains("源数据库连接失败"));
        assertFalse(view.toString().contains("SHOULD_NOT_APPEAR"));
    }

    @Test
    void lockDistinguishesRunningInterruptedAndPausing() throws Exception {
        var values = values("RUNNING");
        assertEquals("RUNNING", service().project(values, true).state());
        assertEquals("INTERRUPTED", service().project(values, false).state());
        values.put("paused", "true");
        assertEquals("PAUSING", service().project(values, true).state());
        assertEquals("PAUSED", service().project(values, false).state());
    }

    @Test
    void staleDataAndEarlyMorningGraceDoNotPretendSchedulerWasInstalled() throws Exception {
        var values = values("SUCCESS");
        values.put("success_until", "2026-11-02");
        assertEquals("STALE", service().project(values, false).freshness());
        var early =
                new BiSyncStatusService(
                        () -> null,
                        mapper,
                        Clock.fixed(Instant.parse("2026-11-02T18:30:00Z"), ZoneOffset.UTC));
        assertEquals("WAITING", early.project(values, false).freshness());
        values.put("success_until", "2026-11-04");
        assertThrows(IllegalArgumentException.class, () -> service().project(values, false));
    }

    @Test
    void sqlFailuresNeverReturnDriverConnectionOrCredentialText() {
        var service =
                new BiSyncStatusService(
                        () -> {
                            throw new SQLException(
                                    "jdbc:mysql://private-host/db password=NEVER_RETURN");
                        },
                        mapper,
                        clock);
        var view = service.view();
        assertEquals("UNAVAILABLE", view.state());
        assertFalse(view.toString().contains("NEVER_RETURN"));
        assertFalse(view.toString().contains("private-host"));
    }

    @Test
    void fixedReadOnlyProjectionOmitsPrivateControlKeysAndHandlesUninitialized() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        List<String> queries = new ArrayList<>();
        var values = values("SUCCESS");
        when(statement.executeQuery(anyString()))
                .thenAnswer(
                        call -> {
                            String sql = call.getArgument(0);
                            queries.add(sql);
                            var rows = mock(ResultSet.class);
                            if (sql.startsWith("SELECT COUNT")) {
                                when(rows.next()).thenReturn(true, false);
                                when(rows.getInt(1)).thenReturn(1);
                            } else if (sql.startsWith("SELECT name")) {
                                var entries = new ArrayList<>(values.entrySet());
                                var position = new AtomicInteger(-1);
                                when(rows.next())
                                        .thenAnswer(
                                                ignored ->
                                                        position.incrementAndGet()
                                                                < entries.size());
                                when(rows.getString(1))
                                        .thenAnswer(
                                                ignored -> entries.get(position.get()).getKey());
                                when(rows.getString(2))
                                        .thenAnswer(
                                                ignored -> entries.get(position.get()).getValue());
                            } else {
                                when(rows.next()).thenReturn(true, false);
                            }
                            return rows;
                        });
        var service = new BiSyncStatusService(() -> connection, mapper, clock);
        assertEquals("SUCCESS", service.view().state());
        verify(connection).setReadOnly(true);
        assertEquals(3, queries.size());
        assertTrue(queries.stream().allMatch(sql -> sql.startsWith("SELECT ")));
        assertTrue(
                queries.stream()
                        .noneMatch(
                                sql ->
                                        sql.contains("source")
                                                || sql.contains("pr:")
                                                || sql.contains("password")));
        var empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(true);
        when(empty.getInt(1)).thenReturn(0);
        when(statement.executeQuery(anyString())).thenReturn(empty);
        assertEquals("NOT_INITIALIZED", service.view().state());
    }
}
