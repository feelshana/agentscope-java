package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.tools.data.DataAgentToolkit;
import io.agentscope.dataagent.web.auth.AuthSettings;
import io.agentscope.dataagent.web.auth.JwtService;
import io.agentscope.dataagent.web.config.SecurityConfig;
import io.agentscope.dataagent.web.persistence.jpa.ChartOptionEntity;
import io.agentscope.dataagent.web.persistence.jpa.ChartOptionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        classes = ChartOwnershipHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:chart-owner-http;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.sql.init.mode=never",
            "dataagent.jwt.secret=test-chart-key-at-least-thirty-two-bytes"
        })
class ChartOwnershipHttpTest {
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ChartOptionEntity.class)
    @EnableJpaRepositories(basePackageClasses = ChartOptionRepository.class)
    @Import({SecurityConfig.class, JwtService.class, AuthSettings.class, ChartController.class})
    static class App {}

    @Autowired ChartOptionRepository charts;
    @Autowired JwtService jwt;

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    String render(String user) throws Exception {
        return render(user, new DataAgentToolkit(charts));
    }

    String render(String user, DataAgentToolkit toolkit) throws Exception {
        String result =
                toolkit.renderChart(
                        RuntimeContext.builder().userId(user).sessionId("test").build(),
                        "商品数量",
                        List.of("品类", "数量"),
                        List.of(List.of("甲", "1"), List.of("乙", "2")),
                        null,
                        null,
                        "bar",
                        "品类",
                        List.of("数量"),
                        null,
                        null,
                        true);
        return new ObjectMapper().readTree(result).path("chartId").asText();
    }

    HttpResponse<String> get(String id, String user, boolean admin) throws Exception {
        var request =
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/charts/" + id));
        if (user != null)
            request.header(
                    "Authorization",
                    "Bearer "
                            + jwt.generate(
                                    user,
                                    user,
                                    admin ? List.of("user", "admin") : List.of("user")));
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void actualHttpEnforcesOwnerAndPreservesPayloadAfterPersistenceReload() throws Exception {
        String id = render("alice");
        assertThat(charts.findById(id).orElseThrow().getOwnerId()).isEqualTo("alice");
        var own = get(id, "alice", false);
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(own.body()).contains("\"option\"", "商品数量");
        assertThat(own.headers().firstValue("cache-control").orElse(""))
                .contains("no-store", "private");
        assertThat(get(id, "bob", false).statusCode()).isEqualTo(404);
        assertThat(get(id, "admin", true).statusCode()).isEqualTo(404);
        assertThat(get(id, "shared-kb-owner", false).statusCode()).isEqualTo(404);
        assertThat(get(id, null, false).statusCode()).isEqualTo(401);
        assertThat(get(id.replace("-", "%2D"), "bob", false).statusCode()).isEqualTo(404);
        assertThat(get(id.replace("-", "%2D"), "alice", false).statusCode()).isEqualTo(200);
        // A fresh controller/repository read models process-local state being absent.
        var auth = new UsernamePasswordAuthenticationToken("alice", "unused");
        assertThat(new ChartController(charts).get(id, auth).block().getBody())
                .containsKey("option");
    }

    @Test
    void legacyAndForeignMalformedChartsAreNotParsedOrExposed() throws Exception {
        var legacy = new ChartOptionEntity();
        legacy.setChartId("legacy-chart");
        legacy.setOptionJson("not json");
        charts.saveAndFlush(legacy);
        charts.saveAndFlush(new ChartOptionEntity("foreign-malformed", "alice", "not json"));
        assertThat(get("legacy-chart", "alice", false).statusCode()).isEqualTo(404);
        assertThat(get("legacy-chart", "admin", true).statusCode()).isEqualTo(404);
        assertThat(get("foreign-malformed", "bob", false).statusCode()).isEqualTo(404);
        assertThat(charts.findById("legacy-chart")).isPresent();
    }

    @Test
    void exactOwnerCheckDoesNotDependOnDatabaseCollation() {
        var repo = mock(ChartOptionRepository.class);
        when(repo.findByChartIdAndOwnerId("id", "ALICE"))
                .thenReturn(Optional.of(new ChartOptionEntity("id", "alice", "{}")));
        assertThatThrownBy(
                        () ->
                                new ChartController(repo)
                                        .get(
                                                "id",
                                                new UsernamePasswordAuthenticationToken(
                                                        "ALICE", "x"))
                                        .block())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void missingIdentityNeverSavesOrLeaksIntoToolSchema() {
        var repo = mock(ChartOptionRepository.class);
        var tools = new DataAgentToolkit(repo);
        for (RuntimeContext context :
                java.util.Arrays.asList(
                        null,
                        RuntimeContext.empty(),
                        RuntimeContext.builder().userId(" ").build())) {
            assertThat(
                            tools.renderChart(
                                    context,
                                    "q",
                                    List.of("a", "n"),
                                    List.of(List.of("x", "1"), List.of("y", "2")),
                                    null,
                                    null))
                    .startsWith("error:");
        }
        verifyNoInteractions(repo);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        var schema = toolkit.getToolSchemas().get(0).getParameters();
        assertThat(schema.toString()).doesNotContain("owner_id", "userId", "context");
    }

    @Test
    void sharedToolkitConcurrentCallsPersistIndependentOwners() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        var shared = new DataAgentToolkit(charts);
        try {
            var tasks =
                    java.util.stream.IntStream.range(0, 10)
                            .mapToObj(
                                    i ->
                                            (java.util.concurrent.Callable<String>)
                                                    () -> render("chart-user-" + i, shared))
                            .toList();
            var result = executor.invokeAll(tasks);
            for (int i = 0; i < result.size(); i++) {
                String id = result.get(i).get();
                assertThat(charts.findById(id).orElseThrow().getOwnerId())
                        .isEqualTo("chart-user-" + i);
                assertThat(get(id, "chart-user-" + i, false).statusCode()).isEqualTo(200);
                assertThat(get(id, "chart-user-" + ((i + 1) % 10), false).statusCode())
                        .isEqualTo(404);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
