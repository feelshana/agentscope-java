package io.agentscope.dataagent.web.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

/** Injects failures at the process boundary without touching the host Docker daemon. */
class UserSandboxDockerReadinessTest {
    private static final String MESSAGE = "Docker 尚未就绪或遗留容器清理失败，请确认 Docker 正常运行后重试";
    private static final String ID = "0123456789ab";

    enum Fault {
        MISSING_EXECUTABLE("Cannot run program \"docker\": CreateProcess error=2"),
        DAEMON_UNAVAILABLE("Cannot connect to the Docker daemon. Is the docker daemon running?"),
        WRONG_CONTEXT("open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file"),
        ACCESS_DENIED("permission denied while trying to connect to the Docker daemon socket"),
        PS_TIMEOUT("Docker cleanup timed out"),
        REMOVE_FAILED(
                "Error response from daemon: removal of container "
                        + ID
                        + " is already in progress"),
        REMOVE_TIMEOUT("Docker cleanup timed out"),
        INVALID_CONTAINER_ID("Unexpected docker container id");

        final String signature;

        Fault(String signature) {
            this.signature = signature;
        }
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void differentFailuresProduceTheSameMemoryReadErrorButDistinctWarnings(Fault fault)
            throws Exception {
        try (var fixture = new Fixture()) {
            fixture.fault = fault;
            fixture.events.list.clear();
            fixture.fsEvents.list.clear();
            try (var processes = fixture.intercept()) {
                fixture.registry.cleanupOrphanedContainers();
                var read = fixture.filesystem.read(RuntimeContext.empty(), "MEMORY.md", 0, 0);
                assertThat(read.isSuccess()).isFalse();
                assertThat(read.error()).contains(MESSAGE);
                assertThat(fixture.warnings())
                        .anySatisfy(s -> assertThat(s).contains(fault.signature));
                assertThat(fixture.fsEvents.list)
                        .anySatisfy(
                                e -> {
                                    assertThat(e.getFormattedMessage())
                                            .contains("execute failed", "MEMORY.md");
                                    assertThat(e.getThrowableProxy().getMessage())
                                            .isEqualTo(MESSAGE);
                                    assertThat(e.getThrowableProxy().getCause()).isNull();
                                });
                verify(fixture.client, never()).create(any(), any(), any());
                assertThat(fixture.commands)
                        .allSatisfy(
                                command -> {
                                    assertThat(command.get(0)).isEqualTo("docker");
                                    if (command.get(1).equals("ps")) {
                                        assertThat(command)
                                                .contains(
                                                        "label=dataagent.managed=true",
                                                        "label=dataagent.instance="
                                                                + fixture.instance);
                                    } else {
                                        assertThat(command)
                                                .containsExactly("docker", "rm", "-f", ID);
                                    }
                                });
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Fault.class,
            names = {"DAEMON_UNAVAILABLE", "REMOVE_FAILED"})
    void recoveryRetriesWithoutRestartingTheRegistryOrLazySandbox(Fault fault) throws Exception {
        try (var fixture = new Fixture();
                var processes = fixture.intercept()) {
            fixture.fault = fault;
            fixture.registry.cleanupOrphanedContainers();
            assertThat(fixture.filesystem.read(RuntimeContext.empty(), "MEMORY.md", 0, 0).error())
                    .contains(MESSAGE);
            fixture.fault = null;
            when(fixture.sandbox.exec(any(), anyString(), isNull()))
                    .thenReturn(new ExecResult(0, "restored memory\n", "", false));
            var recovered = fixture.filesystem.read(RuntimeContext.empty(), "MEMORY.md", 0, 0);
            assertThat(recovered.isSuccess()).isTrue();
            assertThat(recovered.fileData().content()).isEqualTo("restored memory");
            verify(fixture.sandbox).start();
            int checked = fixture.commands.size();
            assertThat(
                            fixture.filesystem
                                    .read(RuntimeContext.empty(), "MEMORY.md", 0, 0)
                                    .isSuccess())
                    .isTrue();
            assertThat(fixture.commands).hasSize(checked);
        }
    }

    @Test
    void aMissingMemoryFileHasANormalNotFoundResultAfterReadinessSucceeds() throws Exception {
        try (var fixture = new Fixture();
                var processes = fixture.intercept()) {
            fixture.registry.cleanupOrphanedContainers();
            when(fixture.sandbox.exec(any(), anyString(), isNull()))
                    .thenReturn(new ExecResult(0, "__NOT_FOUND__\n", "", false));
            var read = fixture.filesystem.read(RuntimeContext.empty(), "MEMORY.md", 0, 0);
            assertThat(read.error()).contains("file_not_found").doesNotContain(MESSAGE);
            assertThat(fixture.warnings()).isEmpty();
            assertThat(fixture.fsEvents.list).isEmpty();
        }
    }

    @Test
    void daemonFailureAfterSuccessfulStartupIsNotThisReadinessException() throws Exception {
        try (var fixture = new Fixture();
                var processes = fixture.intercept()) {
            fixture.registry.cleanupOrphanedContainers();
            int checked = fixture.commands.size();
            fixture.fault = Fault.DAEMON_UNAVAILABLE;
            when(fixture.sandbox.exec(any(), anyString(), isNull()))
                    .thenThrow(new IOException("simulated docker exec daemon connection failure"));
            var read = fixture.filesystem.read(RuntimeContext.empty(), "MEMORY.md", 0, 0);
            assertThat(read.error()).contains("simulated docker exec").doesNotContain(MESSAGE);
            assertThat(fixture.commands).hasSize(checked);
        }
    }

    @Test
    void realProcessLaunchReportsMissingExecutable(@TempDir Path dir) {
        assertThatThrownBy(() -> command(dir.resolve("no-such-docker.exe").toString()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Cannot run program");
    }

    @Test
    void realNonzeroChildProcessPreservesItsErrorOutput() {
        assertThatThrownBy(() -> command(child("failure")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("synthetic daemon connection failure");
    }

    @Test
    @EnabledIfSystemProperty(named = "dataagent.test.docker-readiness-timeout", matches = "true")
    void realHangingProcessReachesTheActualThirtySecondTimeout() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> command(child("hang")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Docker cleanup timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis())
                .isBetween(29_000L, 45_000L);
    }

    private static String[] child(String mode) {
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new String[] {
            Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            Child.class.getName(),
            mode
        };
    }

    private static String command(String... args) throws Throwable {
        var method = UserSandboxRegistry.class.getDeclaredMethod("dockerCommand", String[].class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("hang")) Thread.sleep(60_000);
            else {
                System.err.println("synthetic daemon connection failure");
                System.exit(7);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DockerSandboxClient client = mock(DockerSandboxClient.class);
        final Sandbox sandbox = mock(Sandbox.class);
        final UserSandboxRegistry registry =
                new UserSandboxRegistry(client, null, Duration.ofMinutes(15), Duration.ofHours(1));
        final String instance = "readiness-test-" + UUID.randomUUID();
        final LazySandbox lazy = new LazySandbox(registry, "test-user", "data-agent");
        final SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        final Logger logger = (Logger) LoggerFactory.getLogger(UserSandboxRegistry.class);
        final Logger fsLogger = (Logger) LoggerFactory.getLogger(SandboxBackedFilesystem.class);
        final ListAppender<ILoggingEvent> events = new ListAppender<>();
        final ListAppender<ILoggingEvent> fsEvents = new ListAppender<>();
        final List<List<String>> commands = new ArrayList<>();
        Fault fault;

        Fixture() {
            registry.configureLimits(instance, 2);
            filesystem.setSandbox(lazy);
            when(client.create(any(), any(), any())).thenReturn(sandbox);
            events.start();
            fsEvents.start();
            logger.addAppender(events);
            fsLogger.addAppender(fsEvents);
        }

        org.mockito.MockedConstruction<ProcessBuilder> intercept() {
            return mockConstruction(
                    ProcessBuilder.class,
                    (builder, context) -> {
                        String[] args = (String[]) context.arguments().get(0);
                        when(builder.redirectErrorStream(true)).thenReturn(builder);
                        when(builder.start())
                                .thenAnswer(
                                        invocation -> {
                                            commands.add(List.of(args));
                                            if (fault == Fault.MISSING_EXECUTABLE)
                                                throw new IOException(fault.signature);
                                            boolean removal = args[1].equals("rm");
                                            String output = "";
                                            int exit = 0;
                                            boolean timeout = false;
                                            if (fault != null) {
                                                if (fault == Fault.REMOVE_FAILED
                                                        || fault == Fault.REMOVE_TIMEOUT) {
                                                    if (removal) {
                                                        output = fault.signature;
                                                        exit = 1;
                                                        timeout = fault == Fault.REMOVE_TIMEOUT;
                                                    } else output = ID + "\n";
                                                } else if (fault == Fault.INVALID_CONTAINER_ID)
                                                    output = "not-a-container";
                                                else {
                                                    output = fault.signature;
                                                    exit = 1;
                                                    timeout = fault == Fault.PS_TIMEOUT;
                                                }
                                            }
                                            Process process = mock(Process.class);
                                            when(process.getInputStream())
                                                    .thenReturn(
                                                            new ByteArrayInputStream(
                                                                    output.getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)));
                                            when(process.waitFor(30, TimeUnit.SECONDS))
                                                    .thenReturn(!timeout);
                                            when(process.exitValue()).thenReturn(exit);
                                            when(process.isAlive()).thenReturn(timeout);
                                            when(process.destroyForcibly()).thenReturn(process);
                                            return process;
                                        });
                    });
        }

        List<String> warnings() {
            return events.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(s -> s.contains("orphan cleanup failed"))
                    .toList();
        }

        @Override
        public void close() {
            lazy.releaseLease();
            registry.shutdownAll();
            logger.detachAppender(events);
            fsLogger.detachAppender(fsEvents);
            events.stop();
            fsEvents.stop();
        }
    }
}
