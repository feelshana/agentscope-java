package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.tools.data.DashboardQueryService;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

class DashboardControllerTest {
    @Test
    void httpPayloadKeepsTheActualTabularDataAcrossJacksonVersions() {
        var service = mock(DashboardService.class);
        var queries = mock(DashboardQueryService.class);
        var guard = mock(AgentAccessGuard.class);
        var api =
                new DashboardController(
                        service,
                        queries,
                        guard,
                        mock(io.agentscope.dataagent.web.session.DashboardDeletionService.class));
        var auth = new TestingAuthenticationToken("alice", "unused");
        when(queries.query("alice", "d", true, "r", "SELECT 1"))
                .thenReturn(
                        "{\"columns\":[{\"name\":\"total\"}],\"rows\":[[182]],\"truncated\":false}");
        var body =
                api.query(auth, "d", new DashboardController.Query(true, "r", "SELECT 1")).block();
        var mapper = JsonMapper.builder().build();
        var actual = mapper.readTree(mapper.writeValueAsString(body));
        assertThat(actual.path("rows").get(0).get(0).asInt()).isEqualTo(182);
        assertThat(actual.path("columns").get(0).path("name").asString()).isEqualTo("total");
        when(queries.query("alice", "d", true, "r", "SELECT 1"))
                .thenReturn(
                        "{\"columns\":[\"total\",\"date\"],\"rows\":[{\"date\":null,\"total\":182}],\"truncated\":false}");
        var objectRows =
                api.query(auth, "d", new DashboardController.Query(true, "r", "SELECT 1")).block();
        assertThat(mapper.readTree(mapper.writeValueAsString(objectRows)).path("rows").toString())
                .isEqualTo("[[182,null]]");
        when(queries.query("alice", "d", true, "r", "SELECT 1"))
                .thenReturn("{\"columns\":[],\"rows\":[[1]],\"truncated\":true}");
        assertThatThrownBy(
                        () ->
                                api.query(
                                                auth,
                                                "d",
                                                new DashboardController.Query(
                                                        true, "r", "SELECT 1"))
                                        .block())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("不完整统计");
    }

    @Test
    void agentRunGuardPrecedesEveryAssetOperation() {
        var service = mock(DashboardService.class);
        var queries = mock(DashboardQueryService.class);
        var guard = mock(AgentAccessGuard.class);
        var api =
                new DashboardController(
                        service,
                        queries,
                        guard,
                        mock(io.agentscope.dataagent.web.session.DashboardDeletionService.class));
        doThrow(new DatasetException("未授权", 403))
                .when(guard)
                .require(anyString(), eq(DashboardService.AGENT_ID), eq(Tier.RUN));
        assertThatThrownBy(() -> api.list(new TestingAuthenticationToken("bob", "unused")).block())
                .hasMessageContaining("未授权");
        verifyNoInteractions(service, queries);
    }
}
