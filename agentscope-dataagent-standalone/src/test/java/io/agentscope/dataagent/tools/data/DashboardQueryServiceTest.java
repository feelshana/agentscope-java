package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.FactQueryWindowPolicy;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import org.junit.jupiter.api.Test;

class DashboardQueryServiceTest {
    @Test
    void revokedAfterQueueingNeverReachesDataAndReadonlyCannotBeBypassed() {
        var dashboards = mock(DashboardService.class);
        var access = mock(WrenQueryAccess.class);
        var gateway = mock(WrenQueryGateway.class);
        var windows = mock(FactQueryWindowPolicy.class);
        var service = new DashboardQueryService(dashboards, access, gateway, windows);
        when(dashboards.queryGroup("bob", "id", false, "rev")).thenReturn("group");
        assertThatThrownBy(() -> service.query("bob", "id", false, "rev", "DELETE FROM sales"))
                .isInstanceOf(DatasetException.class);
        verifyNoInteractions(gateway);
        when(gateway.callAuthorized(eq("group"), eq("run_sql"), anyMap(), any()))
                .thenAnswer(
                        call -> {
                            when(dashboards.queryGroup("bob", "id", false, "rev"))
                                    .thenThrow(new DatasetException("已撤权", 403));
                            ((Runnable) call.getArgument(3)).run();
                            throw new AssertionError("Unauthorized database execution");
                        });
        assertThatThrownBy(
                        () ->
                                service.query(
                                        "bob", "id", false, "rev", "SELECT COUNT(*) FROM sales"))
                .hasMessageContaining("撤权");
    }

    @Test
    void windowGateAndOfficialFailureAreVisibleRatherThanFakeZeros() {
        var dashboards = mock(DashboardService.class);
        var access = mock(WrenQueryAccess.class);
        var gateway = mock(WrenQueryGateway.class);
        var windows = mock(FactQueryWindowPolicy.class);
        var service = new DashboardQueryService(dashboards, access, gateway, windows);
        when(dashboards.queryGroup("alice", "id", true, "rev")).thenReturn("group");
        String sql = "SELECT COUNT(*) FROM sales";
        doThrow(new DatasetException("需要日期范围", 422))
                .when(windows)
                .requireAllowed(any(), eq("group"), eq(sql), eq("run_sql"));
        assertThatThrownBy(() -> service.query("alice", "id", true, "rev", sql))
                .hasMessageContaining("日期范围");
        verifyNoInteractions(gateway);
        doNothing().when(windows).requireAllowed(any(), eq("group"), eq(sql), eq("run_sql"));
        when(gateway.callAuthorized(eq("group"), eq("run_sql"), anyMap(), any()))
                .thenReturn(new WrenQueryGateway.WrenCallResult(false, "unknown model"));
        assertThatThrownBy(() -> service.query("alice", "id", true, "rev", sql))
                .hasMessageContaining("unknown model");
        when(gateway.callAuthorized(
                        eq("group"),
                        eq("run_sql"),
                        argThat(m -> m.get("limit").equals(1000)),
                        any()))
                .thenReturn(new WrenQueryGateway.WrenCallResult(true, "{\"rows\":[[10]]}"));
        assertThat(service.query("alice", "id", true, "rev", sql)).contains("10");
    }
}
