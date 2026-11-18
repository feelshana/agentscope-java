package io.agentscope.dataagent.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.tablesync.TableSyncWorker;
import io.agentscope.dataagent.runtime.tablesync.TableSyncWorker.Action;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class AdminTableSyncControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TableSyncWorker worker = mock(TableSyncWorker.class);

    @Test
    void ordinaryUsersCannotDiscoverOrWrite() {
        post(client(null), "tables").expectStatus().isUnauthorized();
        post(client("ROLE_USER"), "test-date").expectStatus().isForbidden();
        post(client("ROLE_KNOWLEDGE_ADMIN"), "create").expectStatus().isForbidden();
        post(client("ROLE_USER"), "rerun").expectStatus().isForbidden();
        post(client("ROLE_USER"), "target").expectStatus().isForbidden();
        client("ROLE_USER")
                .get()
                .uri("/api/admin/table-sync/a")
                .exchange()
                .expectStatus()
                .isForbidden();
        verifyNoInteractions(worker);
    }

    @Test
    void dispatchAcceptsOnlyPublicOperationsAndUsesAuthenticatedActor() {
        var admin = client("ROLE_ADMIN");
        post(admin, "next-job").expectStatus().isNotFound();
        post(admin, "run-job").expectStatus().isNotFound();
        verifyNoInteractions(worker);
        when(worker.invoke(eq(Action.TEST_DATE), any(), eq("admin-fixture")))
                .thenReturn(mapper.createObjectNode().put("matches", 1));
        post(admin, "test-date")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.matches")
                .isEqualTo(1);
        verify(worker).invoke(eq(Action.TEST_DATE), any(), eq("admin-fixture"));
    }

    @Test
    void blockedSourceUsesSanitizedClientError() {
        when(worker.invoke(eq(Action.TABLES), any(), eq("admin-fixture")))
                .thenThrow(new TableSyncWorker.Failure("SOURCE_NOT_ALLOWED"));
        post(client("ROLE_ADMIN"), "tables").expectStatus().isBadRequest();
    }

    private WebTestClient.ResponseSpec post(WebTestClient client, String operation) {
        return client.post()
                .uri("/api/admin/table-sync/" + operation)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"actor\":\"claimed-by-caller\"}")
                .exchange();
    }

    private WebTestClient client(String role) {
        var builder = WebTestClient.bindToController(new AdminTableSyncController(worker, mapper));
        if (role != null) {
            var authentication =
                    new UsernamePasswordAuthenticationToken(
                            "admin-fixture", "", List.of(new SimpleGrantedAuthority(role)));
            builder.webFilter(
                    (exchange, chain) ->
                            chain.filter(
                                    exchange.mutate()
                                            .principal(Mono.just(authentication))
                                            .build()));
        }
        return builder.build();
    }
}
