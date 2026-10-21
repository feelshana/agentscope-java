package io.agentscope.dataagent.web.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.runtime.model.ModelServiceRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class AdminModelServiceControllerTest {
    @Test
    void unauthenticatedAndNonGlobalAdminsAreDeniedEveryEndpointBeforeRegistryAccess() {
        var registry = mock(ModelServiceRegistry.class);
        var controller = new AdminModelServiceController(registry);
        for (String role : List.of("anonymous", "ROLE_USER", "ROLE_KNOWLEDGE_ADMIN")) {
            var clientBuilder = WebTestClient.bindToController(controller);
            if (!role.equals("anonymous"))
                clientBuilder.webFilter(
                        (exchange, chain) ->
                                chain.filter(
                                        exchange.mutate()
                                                .principal(
                                                        Mono.just(
                                                                new UsernamePasswordAuthenticationToken(
                                                                        "fixture",
                                                                        "",
                                                                        List.of(
                                                                                new SimpleGrantedAuthority(
                                                                                        role)))))
                                                .build()));
            var client = clientBuilder.build();
            int status = role.equals("anonymous") ? 401 : 403;
            client.get()
                    .uri("/api/admin/model-services")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(status);
            client.post()
                    .uri("/api/admin/model-services")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(status);
            client.put()
                    .uri("/api/admin/model-services/a")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(status);
            client.delete()
                    .uri("/api/admin/model-services/a?revision=1")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(status);
            for (String action : List.of("test", "activate"))
                client.post()
                        .uri("/api/admin/model-services/a/" + action)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .bodyValue("{\"revision\":1}")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(status);
        }
        verifyNoInteractions(registry);
    }

    @Test
    void administratorGetsNonCacheableView() {
        var registry = mock(ModelServiceRegistry.class);
        when(registry.view())
                .thenReturn(
                        new ModelServiceRegistry.View(
                                List.of(),
                                new ModelServiceRegistry.ActiveView(
                                        null, 0, "启动配置", "", null, null, false),
                                List.of()));
        var auth =
                new UsernamePasswordAuthenticationToken(
                        "admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var client =
                WebTestClient.bindToController(new AdminModelServiceController(registry))
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(
                                                exchange.mutate()
                                                        .principal(Mono.just(auth))
                                                        .build()))
                        .build();
        client.get()
                .uri("/api/admin/model-services")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.services.length()")
                .isEqualTo(0);
    }

    @Test
    void rejectedConfigurationReturnsActionableSafeReason() {
        var registry = mock(ModelServiceRegistry.class);
        when(registry.view())
                .thenThrow(
                        new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.CONFLICT, "配置版本已变化，请刷新后重试。"));
        var auth =
                new UsernamePasswordAuthenticationToken(
                        "admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var client =
                WebTestClient.bindToController(new AdminModelServiceController(registry))
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(
                                                exchange.mutate()
                                                        .principal(Mono.just(auth))
                                                        .build()))
                        .build();
        client.get()
                .uri("/api/admin/model-services")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("配置版本已变化，请刷新后重试。");
    }
}
