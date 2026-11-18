package io.agentscope.dataagent.web.config;

import static org.springframework.web.reactive.function.server.RequestPredicates.PATCH;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

class DashboardCorsTest {
    @Test
    void patchIsAllowedForTheConfiguredOriginWithoutExpandingOtherOrigins() {
        var config = new SecurityConfig();
        ReflectionTestUtils.setField(
                config, "allowedOrigins", java.util.List.of("http://localhost:5173"));
        var client =
                WebTestClient.bindToRouterFunction(
                                RouterFunctions.route(
                                        PATCH("/api/dashboards/d"),
                                        request -> ServerResponse.ok().bodyValue("saved")))
                        .webFilter(new CorsWebFilter(config.corsConfigurationSource()))
                        .build();
        client.options()
                .uri("http://localhost:8080/api/dashboards/d")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value(
                        "Access-Control-Allow-Methods",
                        value ->
                                org.assertj.core.api.Assertions.assertThat(value)
                                        .contains("PATCH"));
        client.patch()
                .uri("http://localhost:8080/api/dashboards/d")
                .header("Origin", "http://localhost:5173")
                .exchange()
                .expectStatus()
                .isOk();
        client.options()
                .uri("http://localhost:8080/api/dashboards/d")
                .header("Origin", "https://other.example")
                .header("Access-Control-Request-Method", "PATCH")
                .exchange()
                .expectStatus()
                .isForbidden();
    }
}
