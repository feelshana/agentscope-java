package io.agentscope.dataagent.runtime.wren;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Installs packaged compatibility resources only into platform-owned Wren subprocesses. */
public final class WrenProcessEnvironment {
    private static Path compatibilityDirectory;

    private WrenProcessEnvironment() {}

    public static void configure(Map<String, String> environment) {
        environment.putIfAbsent("WREN_MEMORY_BACKEND", "lancedb");
        environment.putIfAbsent("WREN_EMBEDDING_BACKEND", "onnx");
        String inherited = environment.getOrDefault("PYTHONPATH", System.getenv("PYTHONPATH"));
        String directory = directory().toString();
        environment.put(
                "PYTHONPATH",
                directory
                        + (inherited == null || inherited.isBlank()
                                ? ""
                                : File.pathSeparator + inherited));
        environment.put("DATAAGENT_WREN_COMPAT", "1");
        environment.put("PYTHONDONTWRITEBYTECODE", "1");
    }

    static synchronized Path directory() {
        if (compatibilityDirectory != null) return compatibilityDirectory;
        try (var resource =
                WrenProcessEnvironment.class.getResourceAsStream("/wren-compat/sitecustomize.py")) {
            if (resource == null) throw new IOException("Missing Wren compatibility resource");
            Path directory = Files.createTempDirectory("dataagent-wren-compat-");
            directory.toFile().deleteOnExit();
            Path script = directory.resolve("sitecustomize.py");
            Files.copy(resource, script);
            script.toFile().deleteOnExit();
            compatibilityDirectory = directory.toAbsolutePath();
            return compatibilityDirectory;
        } catch (IOException e) {
            throw new IllegalStateException("无法部署 Wren 限行兼容资源", e);
        }
    }
}
