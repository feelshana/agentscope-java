package io.agentscope.dataagent.web.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.BiSyncStatusService;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class AdminBiSyncControllerTest {
    private WebTestClient client(BiSyncStatusService service, String role) {
        var builder = WebTestClient.bindToController(new AdminBiSyncController(service));
        if (role != null)
            builder.webFilter(
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
        return builder.build();
    }

    @Test
    void onlyGlobalAdminCanReadAndDeniedRequestsNeverAccessDatabase() {
        var service = mock(BiSyncStatusService.class);
        client(service, null)
                .get()
                .uri("/api/admin/bi-sync")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        for (String role : List.of("ROLE_USER", "ROLE_KNOWLEDGE_ADMIN"))
            client(service, role)
                    .get()
                    .uri("/api/admin/bi-sync")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        verifyNoInteractions(service);
    }

    @Test
    void extraTaskRequiresGlobalAdminAndRunsReadOnlyOffEventLoop() {
        var service = mock(BiSyncStatusService.class);
        client(service, null)
                .get()
                .uri("/api/admin/bi-sync/extra")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        for (String role : List.of("ROLE_USER", "ROLE_KNOWLEDGE_ADMIN"))
            client(service, role)
                    .get()
                    .uri("/api/admin/bi-sync/extra")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        verifyNoInteractions(service);
        var thread = new AtomicReference<String>();
        when(service.extraView())
                .thenAnswer(
                        ignored -> {
                            thread.set(Thread.currentThread().getName());
                            return new io.agentscope.dataagent.dataset.BiExtraSyncStatus.View(
                                    "NOT_INITIALIZED",
                                    "待初始化",
                                    false,
                                    false,
                                    false,
                                    "UNKNOWN",
                                    null,
                                    List.of());
                        });
        var admin = client(service, "ROLE_ADMIN");
        admin.get()
                .uri("/api/admin/bi-sync/extra")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.state")
                .isEqualTo("NOT_INITIALIZED");
        assertTrue(thread.get().startsWith("boundedElastic"));
        admin.post().uri("/api/admin/bi-sync/extra").exchange().expectStatus().isEqualTo(405);
        verify(service).extraView();
    }

    @Test
    void adminReadsWithoutCachingOnBoundedElasticAndNoWriteEndpointExists() {
        var service = mock(BiSyncStatusService.class);
        var thread = new AtomicReference<String>();
        when(service.view())
                .thenAnswer(
                        ignored -> {
                            thread.set(Thread.currentThread().getName());
                            return new BiSyncStatusService.View(
                                    "NOT_INITIALIZED",
                                    "尚未初始化",
                                    false,
                                    false,
                                    "UNKNOWN",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null);
                        });
        var client = client(service, "ROLE_ADMIN");
        client.get()
                .uri("/api/admin/bi-sync")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.state")
                .isEqualTo("NOT_INITIALIZED");
        assertTrue(thread.get().startsWith("boundedElastic"));
        client.post().uri("/api/admin/bi-sync").exchange().expectStatus().isEqualTo(405);
        verify(service, times(1)).view();
    }
}
