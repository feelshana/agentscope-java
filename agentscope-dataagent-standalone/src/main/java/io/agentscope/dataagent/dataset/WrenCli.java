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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs the {@code wren} CLI with argv-array semantics (no shell interpolation) against a project
 * directory, mirroring the probe script {@code pk.py}: {@code PYTHONUTF8=1} +
 * {@code PYTHONIOENCODING=utf-8} environment, {@code WREN_PROJECT_HOME} pointing at the project,
 * and the process working directory set to the project so relative arguments (e.g. {@code -i
 * types_in.json}) resolve locally.
 *
 * <p>Implementations never throw for a missing/broken executable or a timeout — they return a
 * non-zero {@link Result} carrying a human-readable message, so callers can surface the problem
 * as a structured validation issue instead of a 500.
 */
public interface WrenCli {

    /** Outcome of one CLI invocation: exit code plus merged stdout/stderr (UTF-8, trimmed). */
    record Result(int exitCode, String output) {

        public boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * Runs {@code <executable> <args...>} inside {@code projectHome}.
     *
     * @param projectHome directory used as working directory and {@code WREN_PROJECT_HOME}
     * @param timeout hard limit before the process is killed
     * @param args argv after the executable name, e.g. {@code context validate --strict}
     */
    Result run(Path projectHome, Duration timeout, List<String> args);
}
