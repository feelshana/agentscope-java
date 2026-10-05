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
package io.agentscope.dataagent.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link WrenCli} backed by {@link ProcessBuilder}. Output is redirected to a temp file
 * instead of a pipe so a chatty process can never dead-lock the (single-threaded) reader while we
 * wait for the timeout; the process is force-killed when the limit is hit.
 */
@Component
public class ProcessWrenCli implements WrenCli {

    private static final Logger log = LoggerFactory.getLogger(ProcessWrenCli.class);

    private final WrenProperties props;

    public ProcessWrenCli(WrenProperties props) {
        this.props = props;
    }

    @Override
    public Result run(Path projectHome, Duration timeout, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(props.resolveExecutable());
        command.addAll(args);
        Path outFile = null;
        try {
            outFile = Files.createTempFile("wren-cli-", ".log");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectHome.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(outFile.toFile());
            // UTF-8 mode is mandatory on Windows: without it the CLI writes GBK files and
            // crashes printing check marks (see the wren subprocess integration notes).
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("WREN_PROJECT_HOME", projectHome.toAbsolutePath().toString());

            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                log.warn("wren CLI unavailable ({}): {}", props.executable(), e.getMessage());
                return new Result(
                        -1, WrenProperties.unavailableMessage(props.executable(), e.getMessage()));
            }
            process.getOutputStream().close();

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(
                        -1,
                        "wren " + String.join(" ", args) + " 执行超时（" + timeout.toSeconds() + "s）");
            }
            String output = Files.readString(outFile, StandardCharsets.UTF_8).trim();
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            return new Result(-1, "执行 wren 命令失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "wren 命令执行被中断");
        } finally {
            if (outFile != null) {
                try {
                    Files.deleteIfExists(outFile);
                } catch (IOException ignored) {
                    // temp file cleanup is best-effort
                }
            }
        }
    }
}
