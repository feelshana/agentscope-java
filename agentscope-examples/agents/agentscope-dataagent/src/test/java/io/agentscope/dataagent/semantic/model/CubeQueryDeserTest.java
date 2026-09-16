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
package io.agentscope.dataagent.semantic.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LLM 常把 cube/measures/dimensions 写成对象形式，反序列化必须两种都容忍。 */
class CubeQueryDeserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsStringShapes() throws Exception {
        CubeQuery q =
                mapper.readValue(
                        "{\"type\":\"cube_query\",\"cube\":\"click_metrics\","
                                + "\"measures\":[\"click_pv\",\"click_uv\"],"
                                + "\"dimensions\":[\"module_name\"]}",
                        CubeQuery.class);
        assertEquals("click_metrics", q.getCube());
        assertEquals(List.of("click_pv", "click_uv"), q.getMeasures());
        assertEquals(List.of("module_name"), q.getDimensions());
    }

    @Test
    void acceptsObjectShapesAndCubeNameAlias() throws Exception {
        CubeQuery q =
                mapper.readValue(
                        "{\"cubeName\":{\"name\":\"click_metrics\"},"
                                + "\"measures\":[{\"name\":\"click_pv\"}],"
                                + "\"dimensions\":[{\"name\":\"module_name\"}]}",
                        CubeQuery.class);
        assertEquals("click_metrics", q.getCube());
        assertEquals(List.of("click_pv"), q.getMeasures());
        assertEquals(List.of("module_name"), q.getDimensions());
    }
}
