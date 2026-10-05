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

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.MdlSuggestionService;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermEntity;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Business-term dictionary CRUD scoped to one knowledge base (specs/026 — previously a global
 * dictionary with no tenant checks). Terms are appended to the [KNOWLEDGE_BASE_OVERVIEW] section
 * of the system prompt for chats bound to that group only. Every endpoint resolves the caller's
 * userId and runs the {@link DatasetGroupService#getGroup} owner check before touching data.
 */
@RestController
public class SemanticTermController {

    public record TermVO(String id, String term, String explanation, String synonyms) {}

    public record TermRequest(String term, String explanation, String synonyms) {}

    private final MdlSuggestionService modelingService;
    private final DatasetGroupService groupService;

    public SemanticTermController(
            MdlSuggestionService modelingService, DatasetGroupService groupService) {
        this.modelingService = modelingService;
        this.groupService = groupService;
    }

    @GetMapping("/api/groups/{groupId}/semantic-terms")
    public Mono<List<TermVO>> list(@PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return modelingService.listTerms(groupId).stream()
                                    .map(SemanticTermController::toVO)
                                    .toList();
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(SemanticTermController::toStatus);
    }

    @PostMapping("/api/groups/{groupId}/semantic-terms")
    public Mono<TermVO> create(
            @PathVariable String groupId, @RequestBody TermRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            validate(req);
                            return toVO(
                                    modelingService.createTerm(
                                            groupId,
                                            req.term().trim(),
                                            req.explanation(),
                                            req.synonyms()));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(SemanticTermController::toStatus);
    }

    @PutMapping("/api/groups/{groupId}/semantic-terms/{id}")
    public Mono<TermVO> update(
            @PathVariable String groupId,
            @PathVariable String id,
            @RequestBody TermRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            validate(req);
                            // Same posture as views/cubes: update = delete + create within the
                            // group boundary, so a renamed entry keeps its group binding.
                            modelingService.deleteTerm(groupId, id);
                            return toVO(
                                    modelingService.createTerm(
                                            groupId,
                                            req.term().trim(),
                                            req.explanation(),
                                            req.synonyms()));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(SemanticTermController::toStatus);
    }

    @DeleteMapping("/api/groups/{groupId}/semantic-terms/{id}")
    public Mono<Void> delete(
            @PathVariable String groupId, @PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            modelingService.deleteTerm(groupId, id);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.<Void>empty())
                .onErrorMap(SemanticTermController::toStatus);
    }

    /** Maps domain errors to HTTP status (same posture as SemanticModelingController). */
    private static Throwable toStatus(Throwable t) {
        if (t instanceof DatasetException de) {
            return new ResponseStatusException(
                    HttpStatus.valueOf(de.status()), de.getMessage(), de);
        }
        return t;
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
        return new TermVO(e.getId(), e.getTerm(), e.getExplanation(), e.getSynonyms());
    }
}
