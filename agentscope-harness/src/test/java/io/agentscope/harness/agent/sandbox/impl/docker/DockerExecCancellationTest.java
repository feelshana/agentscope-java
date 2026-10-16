/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.sandbox.impl.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in real Docker checks, scoped to a fresh container owned by each test. */
@EnabledIfEnvironmentVariable(named = "DATAAGENT_DOCKER_TESTS", matches = "true")
class DockerExecCancellationTest {
    private String container;
    private DockerSandbox sandbox;

    @BeforeEach
    void start() throws Exception {
        Process process =
                new ProcessBuilder(
                                "docker",
                                "run",
                                "-d",
                                "--network=none",
                                "--name",
                                "capacity-exec-test-" + UUID.randomUUID(),
                                "agentscope/dataagent-sandbox:latest",
                                "sh",
                                "-c",
                                "trap 'exit 0' TERM; while :; do sleep 1 & wait $!; done")
                        .redirectErrorStream(true)
                        .start();
        String id =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), id);
        assertTrue(id.matches("[a-f0-9]{64}"), id);
        container = id;
        DockerSandboxState state = new DockerSandboxState();
        state.setContainerId(container);
        state.setWorkspaceRoot("/workspace");
        state.setContainerOwned(false);
        state.setWorkspaceSpec(new io.agentscope.harness.agent.sandbox.WorkspaceSpec());
        sandbox = new DockerSandbox(state);
    }

    @AfterEach
    void stop() throws Exception {
        if (container != null) {
            var process =
                    new ProcessBuilder("docker", "rm", "-f", container)
                            .redirectErrorStream(true)
                            .start();
            process.getInputStream().readAllBytes();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelledCommandStopsChildrenAndLeavesSiblingAlive() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>(),
                siblingFailure = new AtomicReference<>();
        String code =
                "import subprocess,time\n"
                    + "subprocess.Popen(['sh','-c','sleep 3; echo late > /workspace/late-child'])\n"
                    + "open('/workspace/ready','w').write('ready')\n"
                    + "time.sleep(5)\n";
        String encoded =
                java.util.Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8));
        Thread job =
                new Thread(
                        () -> {
                            try {
                                sandbox.doExec(
                                        RuntimeContext.empty(),
                                        "echo "
                                                + encoded
                                                + " | base64 -d > /workspace/job.py; timeout"
                                                + " --foreground --kill-after=1s 10s python3"
                                                + " /workspace/job.py",
                                        15);
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        Thread sibling =
                new Thread(
                        () -> {
                            try {
                                assertTrue(
                                        sandbox.doExec(
                                                        RuntimeContext.empty(),
                                                        "sleep 1; echo sibling",
                                                        8)
                                                .stdout()
                                                .contains("sibling"));
                            } catch (Throwable error) {
                                siblingFailure.set(error);
                            }
                        });
        job.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                sandbox.doExec(RuntimeContext.empty(), "test -f /workspace/ready", 2);
                break;
            } catch (SandboxException.ExecException missing) {
                Thread.sleep(50);
            }
        }
        sibling.start();
        job.interrupt();
        job.join(5000);
        sibling.join(5000);
        assertFalse(job.isAlive());
        assertFalse(sibling.isAlive());
        assertTrue(failure.get() instanceof InterruptedException, String.valueOf(failure.get()));
        assertEquals(null, siblingFailure.get());
        Thread.sleep(3500);
        sandbox.doExec(RuntimeContext.empty(), "test ! -e /workspace/late-child", 2);
    }

    @Test
    void timeoutStopsContainerCommandAndPreservesExecOutput() throws Exception {
        assertTrue(
                sandbox.doExec(RuntimeContext.empty(), "printf '中文 output'", 5)
                        .stdout()
                        .contains("中文 output"));
        assertThrows(
                SandboxException.ExecTimeoutException.class,
                () ->
                        sandbox.doExec(
                                RuntimeContext.empty(),
                                "sleep 3; echo late > /workspace/late-timeout",
                                1));
        Thread.sleep(2500);
        sandbox.doExec(RuntimeContext.empty(), "test ! -e /workspace/late-timeout", 2);
        assertEquals(0, sandbox.doExec(RuntimeContext.empty(), "true", 2).exitCode());
    }
}
