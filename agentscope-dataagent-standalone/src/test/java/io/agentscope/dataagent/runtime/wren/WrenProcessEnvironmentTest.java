package io.agentscope.dataagent.runtime.wren;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WrenProcessEnvironmentTest {
    @TempDir Path tmp;

    @Test
    void installsPackagedResourceAndPreservesInheritedPythonPath() throws Exception {
        var environment = new HashMap<String, String>();
        environment.put("PYTHONPATH", "existing-path");
        WrenProcessEnvironment.configure(environment);
        assertThat(environment.get("PYTHONPATH"))
                .endsWith(java.io.File.pathSeparator + "existing-path");
        assertThat(environment.get("DATAAGENT_WREN_COMPAT")).isEqualTo("1");
        assertThat(Files.readString(WrenProcessEnvironment.directory().resolve("sitecustomize.py")))
                .contains("0.15.0", "min(int(count.this), limit)");
    }

    @Test
    void installedWrenRunsCompatibilityRegressionSuite() throws Exception {
        String python = System.getProperty("dataagent.test.wren.python", "");
        assumeTrue(
                !python.isBlank(),
                "Set -Ddataagent.test.wren.python to the Wren environment's Python");
        Path output = tmp.resolve("compatibility.log");
        var process =
                new ProcessBuilder(
                        python,
                        "-X",
                        "utf8",
                        "src/test/resources/wren-compat/test_limit.py",
                        WrenProcessEnvironment.directory().resolve("sitecustomize.py").toString());
        WrenProcessEnvironment.configure(process.environment());
        process.redirectErrorStream(true).redirectOutput(output.toFile());
        var running = process.start();
        try {
            assertThat(running.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(running.exitValue()).as(Files.readString(output)).isZero();
        } finally {
            if (running.isAlive()) running.destroyForcibly();
        }
    }
}
