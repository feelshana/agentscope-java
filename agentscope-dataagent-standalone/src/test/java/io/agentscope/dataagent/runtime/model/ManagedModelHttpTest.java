package io.agentscope.dataagent.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.dataagent.web.persistence.jpa.ModelServiceConfigurationRepository;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ManagedModelHttpTest {
    @TempDir Path home;
    HttpServer server;
    ObjectMapper mapper = new ObjectMapper();
    List<String> paths = new CopyOnWriteArrayList<>();
    List<String> auth = new CopyOnWriteArrayList<>();
    List<String> bodies = new CopyOnWriteArrayList<>();
    AtomicInteger status = new AtomicInteger(200);
    ModelServiceRegistry registry;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    paths.add(exchange.getRequestURI().getPath());
                    auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    bodies.add(body);
                    var json = mapper.readTree(body);
                    boolean tool = json.path("tools").isArray() && !json.path("tools").isEmpty();
                    boolean stream = json.path("stream").asBoolean();
                    String content =
                            tool
                                    ? "\"tool_calls\":[{\"index\":0,\"id\":\"probe-call\",\"type\":\"function\",\"function\":{\"name\":\"connection_probe\",\"arguments\":\"{}\"}}]"
                                    : "\"content\":\"OK\"";
                    String response =
                            status.get() != 200
                                    ? "{\"error\":{\"message\":\"synthetic-server-secret\"}}"
                                    : stream
                                            ? "data:"
                                                  + " {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                                                    + content
                                                    + "},\"finish_reason\":null}]}\n\n"
                                                    + "data:"
                                                    + " {\"id\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""
                                                    + (tool ? "tool_calls" : "stop")
                                                    + "\"}]}\n\ndata: [DONE]\n\n"
                                            : "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"model\":\"fixture\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                                                    + content
                                                    + "},\"finish_reason\":\""
                                                    + (tool ? "tool_calls" : "stop")
                                                    + "\"}]}";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders()
                            .set(
                                    "Content-Type",
                                    stream && status.get() == 200
                                            ? "text/event-stream"
                                            : "application/json");
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        var repo = mock(ModelServiceConfigurationRepository.class);
        when(repo.findById("global")).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        registry =
                new ModelServiceRegistry(
                        repo,
                        new ModelServiceCipher("", home.toString()),
                        new ManagedModelFactory(),
                        mapper);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ModelServiceRegistry.ProfileView add(String provider, boolean stream) {
        return registry.save(
                        null,
                        new ModelServiceRegistry.Request(
                                null,
                                "fixture",
                                ModelServiceRegistry.Provider.valueOf(provider),
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/gateway",
                                "/complete",
                                "fixture-model",
                                "synthetic-credential&Signature=test",
                                stream,
                                5,
                                0.2,
                                true,
                                false))
                .services()
                .get(0);
    }

    @ParameterizedTest
    @CsvSource({
        "OPENAI,true",
        "OPENAI,false",
        "DEEPSEEK,true",
        "DEEPSEEK,false",
        "QWEN,true",
        "QWEN,false"
    })
    void nativeClientUsesConfiguredPathBearerSseAndToolCalls(String provider, boolean stream)
            throws Exception {
        var service = add(provider, stream);
        assertThat(registry.test(service.id(), 1).block(Duration.ofSeconds(8)).success()).isTrue();
        assertThat(paths).containsExactly("/gateway/complete", "/gateway/complete");
        assertThat(auth).containsOnly("Bearer synthetic-credential&Signature=test");
        var body = mapper.readTree(bodies.get(0));
        assertThat(body.path("model").asText()).isEqualTo("fixture-model");
        assertThat(body.path("stream").asBoolean()).isEqualTo(stream);
        if (provider.equals("QWEN"))
            assertThat(body.path("enable_thinking").asBoolean(true)).isFalse();
        if (provider.equals("DEEPSEEK"))
            assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        registry.activate(service.id(), 1, "fixture-admin");
        assertThat(registry.view().active().id()).isEqualTo(service.id());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    void errorsAreSafeAndDoNotRetryOrActivate(int errorStatus) {
        status.set(errorStatus);
        var service = add("OPENAI", true);
        var result = registry.test(service.id(), 1).block(Duration.ofSeconds(8));
        assertThat(result.success()).isFalse();
        assertThat(result.message())
                .doesNotContain("synthetic-server-secret", "synthetic-credential");
        assertThat(paths).hasSize(1);
        assertThat(registry.view().active().id()).isNull();
    }
}
