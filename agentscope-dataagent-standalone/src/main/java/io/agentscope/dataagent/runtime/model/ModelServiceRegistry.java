package io.agentscope.dataagent.runtime.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.dataagent.web.persistence.jpa.ModelServiceConfigurationEntity;
import io.agentscope.dataagent.web.persistence.jpa.ModelServiceConfigurationRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Single-instance, versioned control plane; no provider credentials leave its views. */
@Service
public class ModelServiceRegistry {
    private static final String ID = "global";
    private final ModelServiceConfigurationRepository repository;
    private final ModelServiceCipher cipher;
    private final ManagedModelFactory factory;
    private final ObjectMapper mapper;
    private final Set<String> testing = new HashSet<>();
    private ModelServiceConfigurationEntity row;
    private State state;
    private Snapshot applied;

    public enum Provider {
        OPENAI,
        DEEPSEEK,
        QWEN
    }

    public record Profile(
            String id,
            long revision,
            String name,
            Provider provider,
            String baseUrl,
            String endpointPath,
            String modelName,
            String apiKey,
            boolean stream,
            int timeoutSeconds,
            double temperature,
            boolean disableThinking,
            boolean external,
            String updatedAt) {
        @Override
        public String toString() {
            return "ModelProfile[" + id + ", revision=" + revision + "]";
        }
    }

    public record Request(
            Long revision,
            String name,
            Provider provider,
            String baseUrl,
            String endpointPath,
            String modelName,
            String apiKey,
            Boolean stream,
            Integer timeoutSeconds,
            Double temperature,
            Boolean disableThinking,
            Boolean external) {
        @Override
        public String toString() {
            return "ModelServiceRequest[credentials omitted]";
        }
    }

    public record TestResult(
            boolean success, long revision, String testedAt, long durationMs, String message) {}

    public record SwitchEvent(
            String actorId, String appliedAt, String fromId, String toId, long revision) {}

    public record Active(Profile profile, String actorId, String appliedAt) {}

    public record State(
            List<Profile> profiles,
            Active active,
            Map<String, TestResult> tests,
            List<SwitchEvent> history) {
        public static State empty() {
            return new State(List.of(), null, Map.of(), List.of());
        }
    }

    public record ProfileView(
            String id,
            long revision,
            String name,
            Provider provider,
            String baseUrl,
            String endpointPath,
            String modelName,
            boolean keyConfigured,
            boolean stream,
            int timeoutSeconds,
            double temperature,
            boolean disableThinking,
            boolean external,
            String updatedAt,
            boolean current,
            boolean pendingChanges,
            TestResult lastTest) {}

    public record ActiveView(
            String id,
            long revision,
            String name,
            String modelName,
            String actorId,
            String appliedAt,
            boolean external) {}

    public record View(List<ProfileView> services, ActiveView active, List<SwitchEvent> history) {}

    public record Snapshot(
            String id, long revision, String name, Model model, GenerateOptions connection) {}

    public ModelServiceRegistry(
            ModelServiceConfigurationRepository repository,
            ModelServiceCipher cipher,
            ManagedModelFactory factory,
            ObjectMapper mapper) {
        this.repository = repository;
        this.cipher = cipher;
        this.factory = factory;
        this.mapper = mapper;
    }

    public synchronized View view() {
        State data = load();
        var views =
                data.profiles().stream()
                        .map(
                                p -> {
                                    boolean current =
                                            data.active() != null
                                                    && data.active().profile().id().equals(p.id());
                                    return new ProfileView(
                                            p.id(),
                                            p.revision(),
                                            p.name(),
                                            p.provider(),
                                            p.baseUrl(),
                                            p.endpointPath(),
                                            p.modelName(),
                                            true,
                                            p.stream(),
                                            p.timeoutSeconds(),
                                            p.temperature(),
                                            p.disableThinking(),
                                            p.external(),
                                            p.updatedAt(),
                                            current,
                                            current
                                                    && data.active().profile().revision()
                                                            != p.revision(),
                                            data.tests().get(p.id()));
                                })
                        .toList();
        Active a = data.active();
        ActiveView active =
                a == null
                        ? new ActiveView(null, 0, "启动配置", "", null, null, false)
                        : new ActiveView(
                                a.profile().id(),
                                a.profile().revision(),
                                a.profile().name(),
                                a.profile().modelName(),
                                a.actorId(),
                                a.appliedAt(),
                                a.profile().external());
        return new View(views, active, data.history());
    }

    public synchronized View save(String id, Request request) {
        State data = load();
        if (request == null) throw bad("请填写模型服务配置。");
        Profile previous = id == null ? null : find(id);
        if (previous != null) requireRevision(previous, request.revision());
        if (previous == null && data.profiles().size() >= 32) throw bad("最多保存32个模型服务。");
        String name = required(request.name(), 64, "连接名称");
        if (data.profiles().stream()
                .anyMatch(p -> !p.id().equals(id) && p.name().equalsIgnoreCase(name)))
            throw conflict("连接名称已存在。");
        if (request.provider() == null) throw bad("请选择服务类型。");
        String url = required(request.baseUrl(), 2048, "Base URL");
        try {
            URI uri = URI.create(url);
            if (!("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw bad("Base URL需为有效HTTP/HTTPS地址，不含账号、查询参数或片段。");
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        String endpoint =
                request.endpointPath() == null || request.endpointPath().isBlank()
                        ? "/v1/chat/completions"
                        : request.endpointPath().trim();
        if (endpoint.length() > 256
                || !endpoint.startsWith("/")
                || endpoint.contains("..")
                || endpoint.contains("?")
                || endpoint.contains("#")
                || endpoint.contains("\\")
                || endpoint.chars()
                        .anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw bad("接口路径需为不含查询参数的绝对路径。");
        String key =
                request.apiKey() == null || request.apiKey().isBlank()
                        ? previous == null ? "" : previous.apiKey()
                        : request.apiKey().trim();
        if (key.isBlank() || key.length() > 16384 || key.chars().anyMatch(Character::isISOControl))
            throw bad("请填写有效API Key；编辑时留空可保留原Key。");
        int timeout = request.timeoutSeconds() == null ? 60 : request.timeoutSeconds();
        double temperature = request.temperature() == null ? 0 : request.temperature();
        if (timeout < 5
                || timeout > 300
                || !Double.isFinite(temperature)
                || temperature < 0
                || temperature > 2) throw bad("超时需为5–300秒，Temperature需为0–2。");
        Profile next =
                new Profile(
                        id == null ? UUID.randomUUID().toString() : id,
                        previous == null ? 1 : previous.revision() + 1,
                        name,
                        request.provider(),
                        url,
                        endpoint,
                        required(request.modelName(), 128, "模型名称"),
                        key,
                        request.stream() == null || request.stream(),
                        timeout,
                        temperature,
                        Boolean.TRUE.equals(request.disableThinking()),
                        Boolean.TRUE.equals(request.external()),
                        Instant.now().toString());
        var profiles = new ArrayList<>(data.profiles());
        profiles.removeIf(p -> p.id().equals(next.id()));
        profiles.add(next);
        var tests = new HashMap<>(data.tests());
        tests.remove(next.id());
        store(new State(List.copyOf(profiles), data.active(), Map.copyOf(tests), data.history()));
        return view();
    }

    public synchronized View delete(String id, long revision) {
        State data = load();
        Profile profile = find(id);
        requireRevision(profile, revision);
        if (data.active() != null && data.active().profile().id().equals(id))
            throw conflict("当前使用的服务不可删除，请先切换其他服务。");
        var tests = new HashMap<>(data.tests());
        tests.remove(id);
        store(
                new State(
                        data.profiles().stream().filter(p -> !p.id().equals(id)).toList(),
                        data.active(),
                        Map.copyOf(tests),
                        data.history()));
        return view();
    }

    public synchronized View activate(String id, long revision, String actorId) {
        State data = load();
        Profile profile = find(id);
        requireRevision(profile, revision);
        TestResult result = data.tests().get(id);
        if (result == null || !result.success() || result.revision() != revision)
            throw conflict("请先对当前保存版本完成连接与工具调用测试。");
        if (data.active() != null
                && data.active().profile().id().equals(id)
                && data.active().profile().revision() == revision) return view();
        String time = Instant.now().toString();
        var history = new ArrayList<>(data.history());
        history.add(
                new SwitchEvent(
                        actorId,
                        time,
                        data.active() == null ? null : data.active().profile().id(),
                        id,
                        revision));
        if (history.size() > 50) history.remove(0);
        // Construct before persisting so an invalid client never becomes current.
        Snapshot next = snapshot(profile);
        store(
                new State(
                        data.profiles(),
                        new Active(profile, actorId, time),
                        data.tests(),
                        List.copyOf(history)));
        applied = next;
        return view();
    }

    public Mono<TestResult> test(String id, long revision) {
        return Mono.defer(
                        () -> {
                            Profile profile;
                            synchronized (this) {
                                profile = find(id);
                                requireRevision(profile, revision);
                                if (testing.size() >= 4 || !testing.add(id))
                                    throw conflict("已有连接测试执行中，请稍后重试。");
                            }
                            return probe(profile)
                                    .flatMap(
                                            result ->
                                                    Mono.fromCallable(
                                                                    () -> {
                                                                        synchronized (this) {
                                                                            State data = load();
                                                                            requireRevision(
                                                                                    find(id),
                                                                                    revision);
                                                                            var tests =
                                                                                    new HashMap<>(
                                                                                            data
                                                                                                    .tests());
                                                                            tests.put(id, result);
                                                                            store(
                                                                                    new State(
                                                                                            data
                                                                                                    .profiles(),
                                                                                            data
                                                                                                    .active(),
                                                                                            Map
                                                                                                    .copyOf(
                                                                                                            tests),
                                                                                            data
                                                                                                    .history()));
                                                                            return result;
                                                                        }
                                                                    })
                                                            .subscribeOn(
                                                                    Schedulers.boundedElastic()))
                                    .doFinally(
                                            signal -> {
                                                synchronized (this) {
                                                    testing.remove(id);
                                                }
                                            });
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<TestResult> probe(Profile profile) {
        long started = System.nanoTime();
        return Mono.defer(
                        () -> {
                            Model model = factory.create(profile);
                            var text =
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("这是一条无业务数据的连接测试，请只回复OK。")
                                            .build();
                            return model.stream(
                                            List.of(text),
                                            List.of(),
                                            GenerateOptions.builder().maxTokens(32).build())
                                    .collectList()
                                    .flatMap(
                                            responses -> {
                                                boolean content =
                                                        responses.stream()
                                                                .flatMap(
                                                                        r ->
                                                                                r
                                                                                        .getContent()
                                                                                        .stream())
                                                                .anyMatch(
                                                                        c ->
                                                                                c
                                                                                                instanceof
                                                                                                TextBlock
                                                                                                        t
                                                                                        && t
                                                                                                        .getText()
                                                                                                != null
                                                                                        && !t.getText()
                                                                                                .isBlank());
                                                if (!content) return Mono.error(new ProbeFailure());
                                                var tool =
                                                        ToolSchema.builder()
                                                                .name("connection_probe")
                                                                .description(
                                                                        "无业务数据的连接测试，仅返回空对象，不执行任何操作。")
                                                                .parameters(
                                                                        Map.of(
                                                                                "type",
                                                                                "object",
                                                                                "properties",
                                                                                Map.of(),
                                                                                "additionalProperties",
                                                                                false))
                                                                .build();
                                                var message =
                                                        Msg.builder()
                                                                .role(MsgRole.USER)
                                                                .textContent(
                                                                        "这是连接测试。请调用connection_probe工具，参数为空对象，不输出其他内容。")
                                                                .build();
                                                return model.stream(
                                                                List.of(message),
                                                                List.of(tool),
                                                                GenerateOptions.builder()
                                                                        .maxTokens(64)
                                                                        .toolChoice(
                                                                                new ToolChoice
                                                                                        .Auto())
                                                                        .build())
                                                        .collectList();
                                            })
                                    .map(
                                            responses -> {
                                                boolean tools =
                                                        responses.stream()
                                                                .flatMap(
                                                                        r ->
                                                                                r
                                                                                        .getContent()
                                                                                        .stream())
                                                                .anyMatch(
                                                                        c ->
                                                                                c
                                                                                                instanceof
                                                                                                ToolUseBlock
                                                                                                        t
                                                                                        && "connection_probe"
                                                                                                .equals(
                                                                                                        t
                                                                                                                .getName()));
                                                if (!tools) throw new ProbeFailure();
                                                return testResult(
                                                        profile, started, true, "连接与工具调用检查通过");
                                            });
                        })
                .timeout(Duration.ofSeconds(Math.min(60, profile.timeoutSeconds() * 2L)))
                .onErrorResume(
                        error ->
                                Mono.just(
                                        testResult(
                                                profile,
                                                started,
                                                false,
                                                error instanceof TimeoutException
                                                        ? "测试超时，请检查网络和超时配置。"
                                                        : error instanceof ProbeFailure
                                                                ? "响应或工具调用检查未通过，请确认模型兼容性。"
                                                                : "连接测试失败，请检查地址、凭据、模型名称与网络。")));
    }

    private static TestResult testResult(
            Profile profile, long started, boolean success, String message) {
        return new TestResult(
                success,
                profile.revision(),
                Instant.now().toString(),
                Math.max(0, (System.nanoTime() - started) / 1_000_000),
                message);
    }

    public synchronized Snapshot capture(Model startup) {
        Active active = load().active();
        if (active == null) return new Snapshot(null, 0, "启动配置", startup, null);
        Profile profile = active.profile();
        if (applied == null
                || !Objects.equals(applied.id(), profile.id())
                || applied.revision() != profile.revision()) applied = snapshot(profile);
        return applied;
    }

    private Snapshot snapshot(Profile profile) {
        return new Snapshot(
                profile.id(),
                profile.revision(),
                profile.name(),
                factory.create(profile),
                ManagedModelFactory.connectionOptions(profile));
    }

    private State load() {
        if (state != null) return state;
        row = repository.findById(ID).orElseGet(() -> new ModelServiceConfigurationEntity(ID));
        if (row.getEncryptedPayload() == null) return state = State.empty();
        try {
            return state = mapper.readValue(cipher.decrypt(row.getEncryptedPayload()), State.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型配置读取失败，请检查平台数据库与独立主密钥。");
        }
    }

    private void store(State next) {
        try {
            String encrypted = cipher.encrypt(mapper.writeValueAsString(next));
            row.setEncryptedPayload(encrypted);
            row = repository.saveAndFlush(row);
            state = next;
        } catch (OptimisticLockingFailureException e) {
            state = null;
            row = null;
            applied = null;
            throw conflict("模型配置已被其他操作更新，请刷新后重试。");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            state = null;
            row = null;
            applied = null;
            throw new IllegalStateException("模型配置保存失败，请检查平台数据库、独立主密钥与持久目录。");
        }
    }

    private Profile find(String id) {
        return load().profiles().stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型服务不存在。"));
    }

    private static void requireRevision(Profile profile, Long expected) {
        if (expected == null || profile.revision() != expected) throw conflict("配置版本已变化，请刷新后重试。");
    }

    private static String required(String value, int length, String label) {
        if (value == null
                || value.isBlank()
                || value.trim().length() > length
                || value.chars().anyMatch(Character::isISOControl)) throw bad(label + "不能为空或超长。");
        return value.trim();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static class ProbeFailure extends RuntimeException {}
}
