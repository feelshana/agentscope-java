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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Shared {@code wren utils parse-types} wrapper (ADR 0018 D2): physical column types must never
 * be hand-written, so every raw JDBC type goes through the wren CLI's datasource-aware
 * normalizer. Extracted from {@code MdlPublishService} by specs/019 M1 for reuse by the seeder.
 */
@Component
public class WrenTypeNormalizer {

    private final WrenProperties props;
    private final WrenCli wrenCli;
    private final ObjectMapper mapper;

    /** raw_type -> normalized type cache, shared by every group (types are datasource-global). */
    private final Map<String, String> typeCache = new ConcurrentHashMap<>();

    public WrenTypeNormalizer(WrenProperties props, WrenCli wrenCli, ObjectMapper mapper) {
        this.props = props;
        this.wrenCli = wrenCli;
        this.mapper = mapper;
    }

    /** Normalizes the given raw JDBC types; blocks on a subprocess when types are missing. */
    public Map<String, String> normalize(Collection<String> rawTypes) {
        List<String> missing = new ArrayList<>();
        for (String raw : rawTypes) {
            if (!typeCache.containsKey(raw) && !missing.contains(raw)) {
                missing.add(raw);
            }
        }
        if (!missing.isEmpty()) {
            Path tmp = null;
            try {
                tmp = Files.createTempDirectory("wren-types-");
                List<Map<String, String>> input = new ArrayList<>();
                for (int i = 0; i < missing.size(); i++) {
                    input.add(Map.of("column", "c" + i, "raw_type", missing.get(i)));
                }
                Files.writeString(
                        tmp.resolve("types_in.json"),
                        mapper.writeValueAsString(input),
                        StandardCharsets.UTF_8);
                WrenCli.Result result =
                        wrenCli.run(
                                tmp,
                                props.timeout(),
                                List.of(
                                        "utils",
                                        "parse-types",
                                        "-d",
                                        props.dataSource(),
                                        "-i",
                                        "types_in.json"));
                if (!result.ok()) {
                    throw new DatasetException(
                            "wren parse-types 失败：" + firstLine(result.output()), 500);
                }
                List<Map<String, Object>> parsed =
                        mapper.readValue(
                                result.output(), new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> item : parsed) {
                    String raw = text(item.get("raw_type"));
                    String type = text(item.get("type"));
                    if (!raw.isEmpty() && !type.isEmpty()) {
                        typeCache.put(raw, type);
                    }
                }
            } catch (IOException e) {
                throw new DatasetException("类型归一化失败：" + e.getMessage(), e);
            } finally {
                if (tmp != null) {
                    deleteTree(tmp);
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : rawTypes) {
            String type = typeCache.get(raw);
            if (type != null) {
                out.put(raw, type);
            }
        }
        return out;
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // temp cleanup is best-effort
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "(无输出)";
        }
        String t = output.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl);
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }
}
