package io.agentscope.dataagent.web.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.AssetExampleService;
import io.agentscope.dataagent.dataset.SemanticAssetService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@Tag("integration")
class SemanticAssetRecoveryHttpTest {
    @Test
    void canInspectAndRemoveAnIncompleteViewUsingItsRevision() {
        var assets = mock(SemanticAssetService.class);
        var client =
                WebTestClient.bindToController(
                                new SemanticAssetController(
                                        assets, mock(AssetExampleService.class)))
                        .webFilter(
                                (exchange, chain) ->
                                        chain.filter(
                                                exchange.mutate()
                                                        .principal(
                                                                Mono.just(
                                                                        new UsernamePasswordAuthenticationToken(
                                                                                "alice",
                                                                                "ignored")))
                                                        .build()))
                        .build();
        String path = "/api/dataset-groups/g1/assets/incomplete-views/sales";
        when(assets.inspectIncompleteView("alice", "g1", "sales"))
                .thenReturn(
                        new SemanticAssetService.IncompleteView(
                                List.of(
                                        new SemanticAssetService.SourceFile(
                                                "views/sales/sql.yml", "statement: SELECT 1")),
                                new SemanticAssetService.Impact(
                                        List.of(), List.of("How many orders?")),
                                "reviewed-revision"));

        client.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.revision")
                .isEqualTo("reviewed-revision")
                .jsonPath("$.files[0].path")
                .isEqualTo("views/sales/sql.yml")
                .jsonPath("$.impact.questions[0]")
                .isEqualTo("How many orders?");
        client.method(HttpMethod.DELETE)
                .uri(path)
                .bodyValue(Map.of("revision", "reviewed-revision"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.deleted")
                .isEqualTo(true);
        verify(assets).removeIncompleteView("alice", "g1", "sales", "reviewed-revision");
    }
}
