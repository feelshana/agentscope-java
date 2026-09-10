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
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.persistence.jpa.ChartOptionRepository;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Serves server-built ECharts options by chart id. render_chart returns only a small payload with
 * a chartId (harness truncates large tool outputs), and the UI — live or history — fetches the
 * full option here, which also survives restarts.
 */
@RestController
@RequestMapping("/api/charts")
public class ChartController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChartOptionRepository repository;

    public ChartController(ChartOptionRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> get(@PathVariable String id) {
        return Mono.fromCallable(
                        () -> {
                            String json =
                                    repository
                                            .findById(id)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "chart not found: " + id))
                                            .getOptionJson();
                            @SuppressWarnings("unchecked")
                            Map<String, Object> option = MAPPER.readValue(json, Map.class);
                            return Map.<String, Object>of("option", option);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
