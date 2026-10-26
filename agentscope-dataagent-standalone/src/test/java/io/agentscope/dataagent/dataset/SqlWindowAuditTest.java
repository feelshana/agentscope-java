package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowEntity;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SqlWindowAuditTest {
    @Test
    void auditDistinguishesChecksCacheSkipsAndBlockedRetryWithoutLoggingSql() throws Exception {
        var repository = mock(FactQueryWindowRepository.class);
        var datasets = mock(DatasetRepository.class);
        var gate = mock(SqlWindowGate.class);
        var entity = new DatasetEntity();
        entity.setId("a");
        entity.setName("a");
        entity.setGroupId("g");
        when(datasets.findById("a")).thenReturn(Optional.of(entity));
        when(repository.findByGroupId("g"))
                .thenReturn(
                        List.of(
                                new FactQueryWindowEntity(
                                        "w", "g", "a", "a", "[\"t\"]", "LIMITED", 30, "alice")));
        when(repository.findByGroupId("empty")).thenReturn(List.of());
        when(gate.check(anyString(), anyList(), any()))
                .thenReturn(
                        new SqlWindowGate.Decision(
                                true,
                                List.of(new SqlWindowGate.TableCheck("a", "t", true, 19d, 30)),
                                "符合"));
        var policy = new FactQueryWindowPolicy(repository, datasets, gate);
        var logger = (Logger) LoggerFactory.getLogger("SQL_WINDOW_GATE");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var ctx = RuntimeContext.builder().userId("alice").sessionId("session").build();
            String sql =
                    "SELECT 'PRIVATE_VALUE' FROM a WHERE t >= '2025-01-01' AND t < '2025-01-20'";
            policy.requireAllowed(ctx, "g", sql, "run_sql");
            policy.requireAllowed(ctx, "g", sql, "wren_cli memory store");
            policy.requireAllowed(ctx, "empty", "SELECT 1", "run_sql");
            when(gate.check(anyString(), anyList(), any()))
                    .thenReturn(
                            new SqlWindowGate.Decision(
                                    false,
                                    List.of(
                                            new SqlWindowGate.TableCheck(
                                                    "a", "t", false, null, 30)),
                                    "缺少日期"));
            assertThatThrownBy(
                            () ->
                                    policy.requireAllowed(
                                            ctx, "g", "SELECT count(*) FROM a", "run_sql"))
                    .isInstanceOf(DatasetException.class);
            assertThatThrownBy(() -> policy.requireAllowed(ctx, "g", sql, "run_sql"))
                    .isInstanceOf(DatasetException.class);
            assertThat(appender.list)
                    .extracting(
                            e ->
                                    new ObjectMapper()
                                            .readTree(e.getFormattedMessage())
                                            .path("status")
                                            .asText())
                    .containsExactly(
                            "ALLOWED",
                            "CACHE_HIT",
                            "SKIPPED_NO_LIMIT",
                            "DENIED",
                            "BLOCKED_AFTER_REFUSAL");
            String first = appender.list.get(0).getFormattedMessage();
            assertThat(first)
                    .contains("rules", "decision", "maxWindowDays", "windowDays", "sqlHash")
                    .doesNotContain("PRIVATE_VALUE", "SELECT");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
