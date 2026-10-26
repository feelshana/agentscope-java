package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowEntity;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FactQueryWindowPolicyTest {
    private final FactQueryWindowRepository windows = mock(FactQueryWindowRepository.class);
    private final DatasetRepository datasets = mock(DatasetRepository.class);
    private final SqlWindowGate gate = mock(SqlWindowGate.class);
    private final FactQueryWindowPolicy policy = new FactQueryWindowPolicy(windows, datasets, gate);

    private FactQueryWindowEntity entry(String group, String id, String mode, Integer days) {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(id);
        dataset.setGroupId(group);
        dataset.setName("逻辑" + id);
        dataset.setTableName("physical_" + id);
        when(datasets.findById(id)).thenReturn(Optional.of(dataset));
        return new FactQueryWindowEntity(
                id, group, id, "physical_" + id, "[\"时间\"]", mode, days, "alice");
    }

    private RuntimeContext context() {
        return RuntimeContext.builder().userId("alice").sessionId("s").build();
    }

    @Test
    void resolvesDatabaseLogicalNameAndSkipsUnlimitedAndForeignDatasets() {
        var rows =
                List.of(
                        entry("g", "a", "LIMITED", 30),
                        entry("g", "b", "UNLIMITED", null),
                        entry("other", "c", "LIMITED", 7));
        when(windows.findByGroupId("g")).thenReturn(rows);
        assertThat(policy.rules("g"))
                .containsExactly(new SqlWindowGate.Rule("逻辑a", List.of("时间"), 30));
    }

    @Test
    void noConfiguredLimitDoesNotCallModel() {
        var row = entry("g", "a", "UNLIMITED", null);
        when(windows.findByGroupId("g")).thenReturn(List.of(row));
        policy.requireAllowed(context(), "g", "SELECT COUNT(*) FROM 逻辑a");
        verifyNoInteractions(gate);
    }

    @Test
    void refusalStopsSameTurnButNotNextTurnOrOtherKnowledgeBase() {
        var row = entry("g", "a", "LIMITED", 30);
        when(windows.findByGroupId("g")).thenReturn(List.of(row));
        when(windows.findByGroupId("other")).thenReturn(List.of());
        when(gate.check(anyString(), anyList(), any()))
                .thenReturn(new SqlWindowGate.Decision(false, List.of(), "请指定日期"));
        var ctx = context();
        assertThatThrownBy(() -> policy.requireAllowed(ctx, "g", "SELECT COUNT(*) FROM 逻辑a"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("SQL_TIME_WINDOW_REJECTED");
        assertThatThrownBy(() -> policy.requireAllowed(ctx, "g", "SELECT SUM(x) FROM 逻辑a"))
                .isInstanceOf(DatasetException.class);
        verify(gate, times(1)).check(anyString(), anyList(), any());
        policy.requireAllowed(ctx, "other", "SELECT 1");
        when(gate.check(anyString(), anyList(), any()))
                .thenReturn(new SqlWindowGate.Decision(true, List.of(), "符合"));
        policy.requireAllowed(context(), "g", "SELECT COUNT(*) FROM 逻辑a");
        verify(gate, times(2)).check(anyString(), anyList(), any());
    }

    @Test
    void sameSqlApprovalIsReusedOnlyUntilConfigurationChanges() {
        var row = entry("g", "a", "LIMITED", 30);
        when(windows.findByGroupId("g")).thenReturn(List.of(row));
        when(gate.check(anyString(), anyList(), any()))
                .thenReturn(new SqlWindowGate.Decision(true, List.of(), "符合"));
        var ctx = context();
        policy.requireAllowed(ctx, "g", "SELECT 1");
        policy.requireAllowed(ctx, "g", "SELECT 1");
        verify(gate, times(1)).check(anyString(), anyList(), any());
        row.update("[\"时间\"]", "LIMITED", 7, "alice");
        policy.requireAllowed(ctx, "g", "SELECT 1");
        verify(gate, times(2)).check(anyString(), anyList(), any());
    }

    @Test
    void approvalIsSharedAcrossRequestsButRefusalsAreNot() {
        // 2026-10-22 log: the same SQL was ALLOWED on trial run, DENIED when set as an example
        // and ALLOWED again on retry, because every request asked the gate model anew.
        var row = entry("g", "a", "LIMITED", 30);
        when(windows.findByGroupId("g")).thenReturn(List.of(row));
        when(gate.check(anyString(), anyList(), any()))
                .thenReturn(new SqlWindowGate.Decision(true, List.of(), "符合"))
                .thenReturn(new SqlWindowGate.Decision(false, List.of(), "不应再被调用"));
        policy.requireAllowed(context(), "g", "SELECT 1", "question validate");
        policy.requireAllowed(context(), "g", "SELECT 1", "question confirm");
        verify(gate, times(1)).check(anyString(), anyList(), any());
        // A refusal is never remembered across requests.
        assertThatThrownBy(() -> policy.requireAllowed(context(), "g", "SELECT 2"))
                .isInstanceOf(DatasetException.class);
        when(gate.check(anyString(), anyList(), any()))
                .thenReturn(new SqlWindowGate.Decision(true, List.of(), "符合"));
        policy.requireAllowed(context(), "g", "SELECT 2");
        verify(gate, times(3)).check(anyString(), anyList(), any());
    }

    @Test
    void existingWindowDoesNotApplyAfterTableIsConfirmedAsDimension() {
        var row = entry("g", "a", "LIMITED", 30);
        datasets.findById("a")
                .orElseThrow()
                .setSampleSummaryJson("{\"tableRole\":\"DIMENSION\",\"columns\":[]}");
        when(windows.findByGroupId("g")).thenReturn(List.of(row));
        policy.requireAllowed(context(), "g", "SELECT COUNT(*) FROM 逻辑a");
        verifyNoInteractions(gate);
    }
}
