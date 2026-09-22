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

import io.agentscope.dataagent.web.persistence.jpa.SemanticTermEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Business-term dictionary CRUD (TC "语义配置" page backend). Terms are global and are appended to
 * the [KNOWLEDGE_BASE_OVERVIEW] section of the system prompt so the agent maps business nouns onto
 * tables/columns.
 */
@RestController
@RequestMapping("/api/semantic-terms")
public class SemanticTermController {

    public record TermVO(
            String id, String term, String explanation, String synonyms, String scope) {}

    public record TermRequest(String term, String explanation, String synonyms, String scope) {}

    private final SemanticTermRepository repository;

    public SemanticTermController(SemanticTermRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public Mono<List<TermVO>> list() {
        return Mono.fromCallable(
                        () ->
                                repository.findAllByOrderByCreatedAtDesc().stream()
                                        .map(SemanticTermController::toVO)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<TermVO> create(@RequestBody TermRequest req) {
        return Mono.fromCallable(
                        () -> {
                            validate(req);
                            if (repository.findByTerm(req.term().trim()).isPresent()) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "term already exists: " + req.term());
                            }
                            return toVO(
                                    repository.save(
                                            new SemanticTermEntity(
                                                    UUID.randomUUID().toString(),
                                                    req.term().trim(),
                                                    req.explanation(),
                                                    req.synonyms(),
                                                    req.scope())));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<TermVO> update(@PathVariable String id, @RequestBody TermRequest req) {
        return Mono.fromCallable(
                        () -> {
                            validate(req);
                            SemanticTermEntity e =
                                    repository
                                            .findById(id)
                                            .orElseThrow(
                                                    () ->
                                                            new ResponseStatusException(
                                                                    HttpStatus.NOT_FOUND,
                                                                    "term not found: " + id));
                            e.setTerm(req.term().trim());
                            e.setExplanation(req.explanation());
                            e.setSynonyms(req.synonyms());
                            e.setScope(
                                    req.scope() == null || req.scope().isBlank()
                                            ? "global"
                                            : req.scope());
                            e.setUpdatedAt(java.time.Instant.now());
                            return toVO(repository.save(e));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable String id) {
        return Mono.fromRunnable(
                        () -> {
                            if (!repository.existsById(id)) {
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "term not found: " + id);
                            }
                            repository.deleteById(id);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.empty());
    }

    private static void validate(TermRequest req) {
        if (req.term() == null || req.term().isBlank() || req.term().trim().length() > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "term must be 1-30 chars");
        }
        if (req.explanation() != null && req.explanation().length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "explanation must not exceed 100 chars");
        }
    }

    private static TermVO toVO(SemanticTermEntity e) {
        return new TermVO(
                e.getId(), e.getTerm(), e.getExplanation(), e.getSynonyms(), e.getScope());
    }
}
