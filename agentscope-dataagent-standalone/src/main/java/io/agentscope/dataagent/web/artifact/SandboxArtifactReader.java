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
package io.agentscope.dataagent.web.artifact;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Binary transfer bypasses DockerSandbox.exec's 512 KiB text-output truncation. */
public final class SandboxArtifactReader {
    private SandboxArtifactReader() {}

    public static byte[] read(
            RuntimeContext rc, AbstractSandboxFilesystem fs, String path, long size)
            throws Exception {
        if (size < 1 || size > Integer.MAX_VALUE - 1)
            throw new IOException("Invalid artifact size");
        var binding = rc != null ? rc.get(SandboxAcquireResult.class) : null;
        if (binding != null
                && binding.getSandbox().getState() instanceof DockerSandboxState state) {
            // Arguments are passed directly, never interpolated into a shell command.
            Process process =
                    new ProcessBuilder("docker", "exec", state.getContainerId(), "cat", "--", path)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            var reader =
                    Executors.newSingleThreadExecutor(
                            r -> {
                                Thread t = new Thread(r, "sandbox-artifact-read");
                                t.setDaemon(true);
                                return t;
                            });
            try {
                var read = reader.submit(() -> process.getInputStream().readNBytes((int) size + 1));
                byte[] bytes = read.get(30, TimeUnit.SECONDS);
                if (bytes.length != size) throw new IOException("产物在读取期间变化或读取不完整，请重新生成");
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0)
                    throw new IOException("读取沙箱文件失败");
                return bytes;
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                try {
                    process.getInputStream().close();
                } finally {
                    reader.shutdownNow();
                }
            }
        }
        var result = fs.downloadFiles(rc, List.of(path));
        if (result.isEmpty()
                || !result.get(0).isSuccess()
                || result.get(0).content() == null
                || result.get(0).content().length != size)
            throw new IOException("产物读取失败或被截断，未保存不完整文件");
        return result.get(0).content();
    }
}
