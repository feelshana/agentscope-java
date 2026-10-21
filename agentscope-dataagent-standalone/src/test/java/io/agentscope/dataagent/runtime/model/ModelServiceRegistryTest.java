package io.agentscope.dataagent.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.web.persistence.jpa.ModelServiceConfigurationEntity;
import io.agentscope.dataagent.web.persistence.jpa.ModelServiceConfigurationRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class ModelServiceRegistryTest {
    @TempDir Path home;
    ModelServiceConfigurationRepository repository;
    ModelServiceCipher cipher;
    ManagedModelFactory factory;
    ModelServiceRegistry registry;
    AtomicReference<ModelServiceConfigurationEntity> stored;

    @BeforeEach
    void setup() {
        stored = new AtomicReference<>();
        repository = mock(ModelServiceConfigurationRepository.class);
        when(repository.findById("global")).thenAnswer(i -> Optional.ofNullable(stored.get()));
        when(repository.saveAndFlush(any()))
                .thenAnswer(
                        i -> {
                            stored.set(i.getArgument(0));
                            return stored.get();
                        });
        cipher = new ModelServiceCipher("", home.toString());
        factory = mock(ManagedModelFactory.class);
        when(factory.create(any()))
                .thenAnswer(
                        i ->
                                successfulModel(
                                        i.<ModelServiceRegistry.Profile>getArgument(0).name()));
        registry = fresh();
    }

    ModelServiceRegistry fresh() {
        return new ModelServiceRegistry(repository, cipher, factory, new ObjectMapper());
    }

    static ModelServiceRegistry.Request request(Long revision, String name, String key) {
        return new ModelServiceRegistry.Request(
                revision,
                name,
                ModelServiceRegistry.Provider.OPENAI,
                "http://127.0.0.1:12345",
                "/v1/chat/completions",
                "fixture-model",
                key,
                true,
                10,
                0.2,
                false,
                false);
    }

    ModelServiceRegistry.ProfileView add(String name) {
        return registry.save(null, request(null, name, "synthetic-key")).services().stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    void apply(ModelServiceRegistry.ProfileView p) {
        assertThat(registry.test(p.id(), p.revision()).block(Duration.ofSeconds(5)).success())
                .isTrue();
        registry.activate(p.id(), p.revision(), "fixture-admin");
    }

    static Model successfulModel(String name) {
        return new Model() {
            public String getModelName() {
                return name;
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        tools == null || tools.isEmpty()
                                                ? List.of(TextBlock.builder().text("OK").build())
                                                : List.of(
                                                        ToolUseBlock.builder()
                                                                .id("probe")
                                                                .name("connection_probe")
                                                                .input(Map.of())
                                                                .build()))
                                .build());
            }
        };
    }

    @Test
    void encryptedRestoreAndExplicitDraftApplication() throws Exception {
        var a = add("A");
        assertThat(registry.view().active().id()).isNull();
        assertThatThrownBy(() -> registry.activate(a.id(), 1, "admin"))
                .isInstanceOf(ResponseStatusException.class);
        apply(a);
        var original = registry.capture(null);
        var edited = registry.save(a.id(), request(1L, "A edited", "")).services().get(0);
        assertThat(edited.pendingChanges()).isTrue();
        assertThat(edited.lastTest()).isNull();
        assertThat(registry.capture(null)).isSameAs(original);
        assertThat(registry.view().active().name()).isEqualTo("A");
        assertThat(stored.get().getEncryptedPayload())
                .doesNotContain("synthetic-key", "fixture-model", "A edited");
        assertThat(new ObjectMapper().writeValueAsString(registry.view()))
                .doesNotContain("synthetic-key", "apiKey");
        var restarted = fresh();
        assertThat(restarted.capture(null).name()).isEqualTo("A");
        assertThat(restarted.view().services().get(0).pendingChanges()).isTrue();
        apply(edited);
        assertThat(registry.capture(null).name()).isEqualTo("A edited");
        assertThat(registry.view().history()).hasSize(2);
        assertThatThrownBy(() -> registry.delete(a.id(), 2))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void revisionsNamesAndCredentialsAreValidated() {
        var a = add("A");
        assertThatThrownBy(() -> add("a")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> registry.save(null, request(null, "B", "")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> registry.save(a.id(), request(0L, "B", "")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> registry.test(a.id(), 0).block())
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> registry.delete(a.id(), 0))
                .isInstanceOf(ResponseStatusException.class);
        registry.delete(a.id(), 1);
        assertThat(registry.view().services()).isEmpty();
        assertThat(request(null, "A", "synthetic-key").toString()).doesNotContain("synthetic-key");
    }

    @Test
    void staleProbeNeverApprovesNewRevisionAndDuplicateProbeIsRejected() throws Exception {
        var a = add("A");
        var started = new CountDownLatch(1);
        var release = Sinks.<Void>empty();
        Model delayed =
                new Model() {
                    public String getModelName() {
                        return "delayed";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                        started.countDown();
                        return release.asMono()
                                .thenMany(successfulModel("fixture").stream(m, t, o));
                    }
                };
        doReturn(delayed).when(factory).create(any());
        var future = registry.test(a.id(), 1).toFuture();
        assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> registry.test(a.id(), 1).block())
                .isInstanceOf(ResponseStatusException.class);
        registry.save(a.id(), request(1L, "A new", ""));
        release.tryEmitEmpty();
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ResponseStatusException.class);
        assertThat(registry.view().services().get(0).lastTest()).isNull();
    }

    @Test
    void failedProbeDoesNotSwitchOrLeakProviderErrors() {
        var a = add("A");
        apply(a);
        var b = add("B");
        Model failed = mock(Model.class);
        when(failed.stream(any(), any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("synthetic-secret-response")));
        doReturn(failed).when(factory).create(any());
        var result = registry.test(b.id(), 1).block(Duration.ofSeconds(5));
        assertThat(result.success()).isFalse();
        assertThat(result.message()).doesNotContain("synthetic-secret-response");
        assertThatThrownBy(() -> registry.activate(b.id(), 1, "admin"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(registry.view().active().id()).isEqualTo(a.id());
    }

    public static class EchoTool {
        @Tool(description = "隔离测试工具")
        public String echo() {
            return "tool-ok";
        }
    }

    @Test
    void cachedNativeAgentPinsWholeToolTurnAndNextTurnUsesNewService() throws Exception {
        var a = add("A");
        apply(a);
        var b = add("B");
        apply(b);
        var releaseFirstResponse = new CountDownLatch(1);
        var firstCallStarted = new CountDownLatch(1);
        var countA = new AtomicInteger();
        var countB = new AtomicInteger();
        Model modelA =
                new Model() {
                    public String getModelName() {
                        return "A";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                        assertThat(o.getApiKey()).isEqualTo("synthetic-key");
                        assertThat(o.getBaseUrl()).isEqualTo("http://127.0.0.1:12345");
                        int index = countA.incrementAndGet();
                        if (index == 1) {
                            firstCallStarted.countDown();
                            try {
                                if (!releaseFirstResponse.await(5, TimeUnit.SECONDS))
                                    return Flux.error(new IllegalStateException("fixture wait"));
                            } catch (InterruptedException e) {
                                return Flux.error(e);
                            }
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            ToolUseBlock.builder()
                                                                    .id("echo-one")
                                                                    .name("echo")
                                                                    .input(Map.of())
                                                                    .build()))
                                            .build());
                        }
                        return Flux.defer(
                                () -> {
                                    return Flux.just(
                                            ChatResponse.builder()
                                                    .content(
                                                            List.of(
                                                                    TextBlock.builder()
                                                                            .text("A done")
                                                                            .build()))
                                                    .build());
                                });
                    }
                };
        Model modelB =
                new Model() {
                    public String getModelName() {
                        return "B";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                        countB.incrementAndGet();
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(TextBlock.builder().text("B done").build()))
                                        .build());
                    }
                };
        doAnswer(
                        i ->
                                i.<ModelServiceRegistry.Profile>getArgument(0).name().equals("A")
                                        ? modelA
                                        : modelB)
                .when(factory)
                .create(any());
        registry.activate(a.id(), 1, "admin");
        var switching = new SwitchingModel(registry, null);
        var tools = new Toolkit();
        tools.registerTool(new EchoTool());
        var agent =
                ReActAgent.builder()
                        .name("cached-switch-test")
                        .model(switching)
                        .middleware(switching)
                        .toolkit(tools)
                        .generateOptions(
                                GenerateOptions.builder()
                                        .baseUrl("http://stale.invalid")
                                        .apiKey("stale-key")
                                        .build())
                        .build();
        var inFlight = agent.streamEvents("隔离测试").collectList().toFuture();
        try {
            assertThat(firstCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
            registry.activate(b.id(), 1, "admin");
        } finally {
            releaseFirstResponse.countDown();
        }
        assertThat(inFlight.get(5, TimeUnit.SECONDS)).isNotEmpty();
        assertThat(countA.get()).isEqualTo(2);
        assertThat(countB.get()).isZero();
        agent.streamEvents("下一轮").collectList().block(Duration.ofSeconds(5));
        assertThat(countB.get()).isEqualTo(1);
    }

    @Test
    void absentManagedServiceUsesStartupAndMissingKeyNeverFallsBack() {
        var startup = successfulModel("startup");
        assertThat(registry.capture(startup).model()).isSameAs(startup);
        add("A");
        var wrong =
                new ModelServiceRegistry(
                        repository,
                        new ModelServiceCipher(
                                Base64.getEncoder().encodeToString(new byte[32]), home.toString()),
                        factory,
                        new ObjectMapper());
        assertThatThrownBy(() -> wrong.capture(startup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法解密");
    }
}
