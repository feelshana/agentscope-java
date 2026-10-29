package io.agentscope.dataagent.tools.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Built-in query analysis skills (spec097): each is {@code SKILL.md} plus one fixed Python script
 * under {@code classpath:analysis-skills/<id>/}. The skill text is mounted on the query agent from
 * a materialised copy; the scripts are uploaded into the session sandbox before {@code
 * run_python} runs, so the model calls them and never writes the algorithm itself.
 */
public final class AnalysisSkills {
    private static final Logger log = LoggerFactory.getLogger(AnalysisSkills.class);
    private static final String ROOT = "analysis-skills/";

    /** The only analysis methods a request may select; also the skill names. */
    public static final List<String> IDS = List.of("attribution", "anomaly", "forecast");

    /** Chinese names used in prompts and messages. */
    public static final Map<String, String> NAMES =
            Map.of("attribution", "指标归因", "anomaly", "异常检测", "forecast", "趋势预测");

    private static volatile Map<String, byte[]> scripts;

    private AnalysisSkills() {}

    /** Returns the id when it names a built-in method, otherwise {@code null}. */
    public static String normalize(String method) {
        if (method == null) return null;
        String id = method.strip().toLowerCase(java.util.Locale.ROOT);
        return IDS.contains(id) ? id : null;
    }

    /**
     * Copies every bundled skill file under {@code target}, replacing what an earlier version left
     * there, so the mounted skills always match this build. Returns {@code null} when nothing
     * could be written; the query agent then runs without analysis skills.
     */
    public static Path materialize(Path target) {
        try {
            if (Files.exists(target)) {
                try (var walk = Files.walk(target)) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(p);
                }
            }
            for (var entry : files().entrySet()) {
                Path file = target.resolve(entry.getKey()).normalize();
                if (!file.startsWith(target)) continue;
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
            return target;
        } catch (IOException e) {
            log.warn("Analysis skills not materialised at {}: {}", target, e.getMessage());
            return null;
        }
    }

    /** The Python scripts keyed by {@code <id>/<file>.py}, uploaded under the sandbox skills dir. */
    public static Map<String, byte[]> scripts() {
        Map<String, byte[]> cached = scripts;
        if (cached == null) {
            Map<String, byte[]> found = new LinkedHashMap<>();
            files().forEach(
                            (path, bytes) -> {
                                if (path.endsWith(".py")) found.put(path, bytes);
                            });
            scripts = cached = Map.copyOf(found);
        }
        return cached;
    }

    private static Map<String, byte[]> files() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try {
            Resource[] resources =
                    new PathMatchingResourcePatternResolver(AnalysisSkills.class.getClassLoader())
                            .getResources("classpath*:" + ROOT + "**/*");
            for (Resource r : resources) {
                if (!r.isReadable()) continue;
                String url = r.getURL().toString();
                int at = url.lastIndexOf(ROOT);
                if (at < 0) continue;
                String relative = url.substring(at + ROOT.length());
                if (relative.isEmpty() || relative.endsWith("/")) continue;
                String id = relative.substring(0, Math.max(0, relative.indexOf('/')));
                if (!IDS.contains(id)) continue;
                try (InputStream in = r.getInputStream()) {
                    out.put(
                            java.net.URLDecoder.decode(
                                    relative, java.nio.charset.StandardCharsets.UTF_8),
                            in.readAllBytes());
                }
            }
        } catch (IOException e) {
            log.warn("Analysis skills could not be read from the classpath: {}", e.getMessage());
        }
        return out;
    }
}
