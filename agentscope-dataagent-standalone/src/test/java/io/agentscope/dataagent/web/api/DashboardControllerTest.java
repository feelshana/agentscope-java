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
    void visibleLibraryUsesBatchedDisplayNamesWithoutLeakingCreatorIdentityOrCredentials() {
        var service = mock(DashboardService.class);
        var guard = mock(AgentAccessGuard.class);
        var users = mock(io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository.class);
        var api =
                new DashboardController(
                        service,
                        mock(DashboardQueryService.class),
                        guard,
                        mock(io.agentscope.dataagent.web.session.DashboardDeletionService.class),
                        users);
        when(service.list("alice"))
                .thenReturn(
                        java.util.List.of(
                                new DashboardService.View(
                                        "asset", "g", "销售", "r", "p", true, false, true, null,
                                        "now", true, false, "说明", "created", "creator", null),
                                new DashboardService.View(
                                        "removed", "g", "渠道", "r", "p", true, true, true, null,
                                        "now", false, false, "", null, "gone", null)));
        var owner =
                new io.agentscope.dataagent.web.persistence.jpa.UserEntity(
                        "creator", "owner-account", "private-hash", "user");
        owner.setDisplayName("王小明");
        when(users.findAllById(java.util.List.of("creator", "gone")))
                .thenReturn(java.util.List.of(owner));
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(api)
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(
                                                exchange.mutate()
                                                        .principal(
                                                                reactor.core.publisher.Mono.just(
                                                                        new TestingAuthenticationToken(
                                                                                "alice", "unused")))
                                                        .build()))
                        .build();
        client.get()
                .uri("/api/dashboards")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].creatorName")
                .isEqualTo("王小明")
                .jsonPath("$[0].title")
                .isEqualTo("销售")
                .jsonPath("$[0].canPublish")
                .isEqualTo(false)
                .jsonPath("$[0].canEdit")
                .isEqualTo(true)
                .jsonPath("$[0].creatorId")
                .doesNotExist()
                .jsonPath("$[0].passwordHash")
                .doesNotExist()
                .jsonPath("$[0].dashboard")
                .doesNotExist()
                .jsonPath("$[1].creatorName")
                .isEqualTo("已移除用户");
        org.mockito.Mockito.verify(users).findAllById(java.util.List.of("creator", "gone"));
        org.mockito.Mockito.clearInvocations(users, service);
        doThrow(new DatasetException("禁止", 403))
                .when(guard)
                .require("alice", DashboardService.AGENT_ID, Tier.RUN);
        client.get().uri("/api/dashboards").exchange().expectStatus().isForbidden();
        verifyNoInteractions(users, service);
    }

    @Test
    void checkpointAndRestoreKeepRevisionIdleAndRunGuardsThroughHttp() {
        var service = mock(DashboardService.class);
        var queries = mock(DashboardQueryService.class);
        var guard = mock(AgentAccessGuard.class);
        var deletion = mock(io.agentscope.dataagent.web.session.DashboardDeletionService.class);
        var api =
                new DashboardController(
                        service,
                        queries,
                        guard,
                        deletion,
                        mock(
                                io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository
                                        .class));
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(api)
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(
                                                exchange.mutate()
                                                        .principal(
                                                                reactor.core.publisher.Mono.just(
                                                                        new TestingAuthenticationToken(
                                                                                "alice", "unused")))
                                                        .build()))
                        .build();
        when(service.checkpoint(
                        eq("alice"),
                        eq("d"),
                        eq("r"),
                        org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(
                        call -> {
                            ((Runnable) call.getArgument(3)).run();
                            return new DashboardService.Checkpoint("r");
                        });
        when(service.restore(
                        eq("alice"),
                        eq("d"),
                        eq("r"),
                        eq("old"),
                        org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(
                        call -> {
                            ((Runnable) call.getArgument(4)).run();
                            throw new DatasetException("草稿已变化", 409);
                        });
        client.post()
                .uri("/api/dashboards/d/checkpoint")
                .bodyValue(java.util.Map.of("revision", "r"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.revision")
                .isEqualTo("r");
        client.post()
                .uri("/api/dashboards/d/restore")
                .bodyValue(java.util.Map.of("revision", "r", "targetRevision", "old"))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        org.mockito.Mockito.verify(deletion, org.mockito.Mockito.times(2)).requireIdle("d");
        org.mockito.Mockito.clearInvocations(service, deletion);
        doThrow(new DatasetException("未授权", 403))
                .when(guard)
                .require("alice", DashboardService.AGENT_ID, Tier.RUN);
        client.post()
                .uri("/api/dashboards/d/checkpoint")
                .bodyValue(java.util.Map.of("revision", "r"))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri("/api/dashboards/d/restore")
                .bodyValue(java.util.Map.of("revision", "r", "targetRevision", "old"))
                .exchange()
                .expectStatus()
                .isForbidden();
        verifyNoInteractions(service, queries, deletion);
    }

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
                        mock(io.agentscope.dataagent.web.session.DashboardDeletionService.class),
                        mock(
                                io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository
                                        .class));
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
                        mock(io.agentscope.dataagent.web.session.DashboardDeletionService.class),
                        mock(
                                io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository
                                        .class));
        doThrow(new DatasetException("未授权", 403))
                .when(guard)
                .require(anyString(), eq(DashboardService.AGENT_ID), eq(Tier.RUN));
        assertThatThrownBy(() -> api.list(new TestingAuthenticationToken("bob", "unused")).block())
                .hasMessageContaining("未授权");
        assertThatThrownBy(
                        () ->
                                api.check(
                                                new TestingAuthenticationToken("bob", "unused"),
                                                "board",
                                                new DashboardController.Publish("r"))
                                        .block())
                .hasMessageContaining("未授权");
        verifyNoInteractions(service, queries);
    }

    @Test
    void patchRouteCarriesMetadataAndConflictWithoutBypassingRunPermission() {
        var service = mock(DashboardService.class);
        var queries = mock(DashboardQueryService.class);
        var guard = mock(AgentAccessGuard.class);
        var api =
                new DashboardController(
                        service,
                        queries,
                        guard,
                        mock(io.agentscope.dataagent.web.session.DashboardDeletionService.class),
                        mock(
                                io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository
                                        .class));
        when(service.updateMetadata("alice", "asset", "新名称", "说明", "old"))
                .thenReturn(
                        new DashboardService.View(
                                "asset", "g", "新名称", "r", "p", true, true, true, null, "next", true,
                                false, "说明", "created", "creator", null));
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(api)
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(
                                                exchange.mutate()
                                                        .principal(
                                                                reactor.core.publisher.Mono.just(
                                                                        new TestingAuthenticationToken(
                                                                                "alice", "unused")))
                                                        .build()))
                        .build();
        client.patch()
                .uri("/api/dashboards/asset")
                .bodyValue(
                        java.util.Map.of(
                                "title", "新名称", "description", "说明", "expectedUpdatedAt", "old"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("新名称")
                .jsonPath("$.description")
                .isEqualTo("说明")
                .jsonPath("$.createdAt")
                .isEqualTo("created")
                .jsonPath("$.revision")
                .isEqualTo("r");
        when(service.updateMetadata("alice", "asset", "新名称", "说明", "old"))
                .thenThrow(new DatasetException("看板信息已变化", 409));
        client.patch()
                .uri("/api/dashboards/asset")
                .bodyValue(
                        java.util.Map.of(
                                "title", "新名称", "description", "说明", "expectedUpdatedAt", "old"))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.times(2))
                .updateMetadata("alice", "asset", "新名称", "说明", "old");
        doThrow(new DatasetException("未授权", 403))
                .when(guard)
                .require("alice", DashboardService.AGENT_ID, Tier.RUN);
        client.patch()
                .uri("/api/dashboards/asset")
                .bodyValue(java.util.Map.of("title", "绕过"))
                .exchange()
                .expectStatus()
                .isForbidden();
        org.mockito.Mockito.verifyNoMoreInteractions(service);
    }

    @Test
    void creationPostPassesOptionalDescriptionInTheOriginalRequest() {
        var service = mock(DashboardService.class);
        var api =
                new DashboardController(
                        service,
                        mock(DashboardQueryService.class),
                        mock(AgentAccessGuard.class),
                        mock(io.agentscope.dataagent.web.session.DashboardDeletionService.class),
                        mock(
                                io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository
                                        .class));
        when(service.create("alice", "g", "新看板", "用途说明"))
                .thenReturn(
                        new DashboardService.View(
                                "asset", "g", "新看板", "", null, true, true, false, null, "now", true,
                                false, "用途说明", "now", "creator", null));
        var client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(api)
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(
                                                exchange.mutate()
                                                        .principal(
                                                                reactor.core.publisher.Mono.just(
                                                                        new TestingAuthenticationToken(
                                                                                "alice", "unused")))
                                                        .build()))
                        .build();
        client.post()
                .uri("/api/dashboards")
                .bodyValue(java.util.Map.of("groupId", "g", "title", "新看板", "description", "用途说明"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.description")
                .isEqualTo("用途说明");
        org.mockito.Mockito.verify(service).create("alice", "g", "新看板", "用途说明");
    }
}
