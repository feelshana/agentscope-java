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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link ProcessWrenCli} never throws for a missing executable: the spawn failure comes
 * back as a non-zero {@link WrenCli.Result} carrying the cross-platform installation guidance, so
 * the modeling page surfaces an actionable message instead of a raw OS error.
 */
class ProcessWrenCliTest {

    @TempDir Path tmp;

    @Test
    void missingExecutableReturnsGuidedResult() {
        // Explicit (non-default) name → used verbatim → CreateProcess fails immediately on any OS.
        WrenProperties props =
                new WrenProperties("definitely-not-a-real-wren-xyz", "", "dataagent", "mysql", 10);
        ProcessWrenCli cli = new ProcessWrenCli(props);

        WrenCli.Result result = cli.run(tmp, Duration.ofSeconds(10), List.of("--version"));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.output())
                .contains("wren 可执行文件「definitely-not-a-real-wren-xyz」无法启动")
                .contains("pip install 'wrenai[mysql,mcp]' 'mcp<2'")
                .contains("DATAAGENT_WREN_EXECUTABLE");
    }

    @Test
    void successTimingsExcludeArgumentsAndOutput() {
        var props = javaProperties();
        String logs =
                capture(
                        () ->
                                new ProcessWrenCli(props)
                                        .run(
                                                tmp,
                                                Duration.ofSeconds(10),
                                                List.of("-version", "private-question-and-sql")));
        assertThat(logs)
                .contains("phase=spawn", "phase=process_wait", "phase=read_output", "phase=total");
        assertThat(logs).doesNotContain("private-question-and-sql", "version \"");
        assertThat(logs).contains("outcome=ok");
    }

    @Test
    void nonzeroExitIsMarkedFailedWithoutLoggingArguments() {
        String logs =
                capture(
                        () ->
                                new ProcessWrenCli(javaProperties())
                                        .run(
                                                tmp,
                                                Duration.ofSeconds(10),
                                                List.of("--private-invalid-option")));
        assertThat(logs).contains("phase=process_wait", "outcome=failed");
        assertThat(logs).doesNotContain("private-invalid-option");
    }

    @Test
    void timeoutIsMeasuredAndKeepsExistingResultContract() throws Exception {
        String classes =
                Path.of(
                                ProcessWrenCliTest.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toString();
        String logs =
                capture(
                        () -> {
                            var result =
                                    new ProcessWrenCli(javaProperties())
                                            .run(
                                                    tmp,
                                                    Duration.ofMillis(100),
                                                    List.of("-cp", classes, Wait.class.getName()));
                            assertThat(result.exitCode()).isEqualTo(-1);
                            assertThat(result.output()).contains("执行超时");
                            return result;
                        });
        assertThat(logs).contains("outcome=timeout", "phase=cleanup", "outcome=failed");
    }

    private WrenProperties javaProperties() {
        Path executable =
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return new WrenProperties(executable.toString(), "", "test", "mysql", 10);
    }

    private String capture(Supplier<WrenCli.Result> action) {
        var logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WrenTiming.class);
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.get();
            return appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    public static class Wait {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(10000);
        }
    }
}
