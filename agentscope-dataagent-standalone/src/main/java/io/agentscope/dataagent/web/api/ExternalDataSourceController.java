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

import io.agentscope.dataagent.dataset.DataSourceIntrospector;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
 * User-configured external database connections (TC "数据源管理" analogue): CRUD plus read-only
 * introspection (schemas / tables / columns) and connectivity status. Tables of these sources can
 * be associated into knowledge bases via POST /api/dataset-groups/{id}/associate.
 */
@RestController
@RequestMapping("/api/datasources")
public class ExternalDataSourceController {

    public record DataSourceVO(
            String id,
            String name,
            String kind,
            String jdbcUrl,
            String username,
            boolean sampling,
            String createdAt) {}

    public record DataSourceRequest(
            String name,
            String kind,
            String jdbcUrl,
            String username,
            String password,
            Boolean sampling) {}

    public record StatusVO(boolean connected, String error) {}

    public record ColumnVO(String name, String type, String description) {}

    public record TableVO(String name, String type) {}

    private final ExternalDataSourceRepository repository;
    private final DatasetRepository datasetRepository;
    private final DataSourceIntrospector introspector;

    public ExternalDataSourceController(
            ExternalDataSourceRepository repository,
            DatasetRepository datasetRepository,
            DataSourceIntrospector introspector) {
        this.repository = repository;
        this.datasetRepository = datasetRepository;
        this.introspector = introspector;
    }

    @GetMapping
    public Mono<List<DataSourceVO>> list(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                repository.findByOwnerIdOrderByCreatedAtDesc(userId).stream()
                                        .map(ExternalDataSourceController::toVO)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<DataSourceVO> create(@RequestBody DataSourceRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            validate(req);
                            if (repository
                                    .findByOwnerIdAndName(userId, req.name().trim())
                                    .isPresent()) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "data source name already exists: " + req.name());
                            }
                            return toVO(
                                    repository.save(
                                            new ExternalDataSourceEntity(
                                                    UUID.randomUUID().toString(),
                                                    userId,
                                                    req.name().trim(),
                                                    req.kind() == null || req.kind().isBlank()
                                                            ? "mysql"
                                                            : req.kind(),
                                                    req.jdbcUrl().trim(),
                                                    req.username(),
                                                    req.password(),
                                                    req.sampling() == null || req.sampling())));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/{id}")
    public Mono<DataSourceVO> update(
            @PathVariable String id, @RequestBody DataSourceRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            validate(req);
                            ExternalDataSourceEntity e = owned(userId, id);
                            e.setName(req.name().trim());
                            if (req.kind() != null && !req.kind().isBlank()) {
                                e.setKind(req.kind());
                            }
                            e.setJdbcUrl(req.jdbcUrl().trim());
                            e.setUsername(req.username());
                            if (req.password() != null && !req.password().isBlank()) {
                                e.setPassword(req.password());
                            }
                            if (req.sampling() != null) {
                                e.setSampling(req.sampling());
                            }
                            e.setUpdatedAt(Instant.now());
                            return toVO(repository.save(e));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                        () -> {
                            owned(userId, id);
                            boolean associated =
                                    datasetRepository.findAll().stream()
                                            .anyMatch(d -> id.equals(d.getExternalDataSourceId()));
                            if (associated) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "data source still has associated tables; remove them"
                                                + " from the knowledge base first");
                            }
                            repository.deleteById(id);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.empty());
    }

    @GetMapping("/{id}/status")
    public Mono<StatusVO> status(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            ExternalDataSourceEntity e = owned(userId, id);
                            String err = introspector.connectionError(e);
                            return new StatusVO(err == null, err);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/schemas")
    public Mono<List<String>> schemas(@PathVariable String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> introspector.listSchemas(owned(userId, id)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(ExternalDataSourceController::toStatus);
    }

    @GetMapping("/{id}/schemas/{schema}/tables")
    public Mono<List<TableVO>> tables(
            @PathVariable String id, @PathVariable String schema, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                introspector.listTables(owned(userId, id), schema).stream()
                                        .map(t -> new TableVO(t.name(), t.type()))
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(ExternalDataSourceController::toStatus);
    }

    @GetMapping("/{id}/schemas/{schema}/tables/{table}/columns")
    public Mono<List<ColumnVO>> columns(
            @PathVariable String id,
            @PathVariable String schema,
            @PathVariable String table,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () ->
                                introspector.listColumns(owned(userId, id), schema, table).stream()
                                        .map(c -> new ColumnVO(c.name(), c.type(), c.description()))
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(ExternalDataSourceController::toStatus);
    }

    private ExternalDataSourceEntity owned(String userId, String id) {
        return repository
                .findById(id)
                .filter(e -> e.getOwnerId().equals(userId))
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "data source not found: " + id));
    }

    private static void validate(DataSourceRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "name must be 1-64 characters");
        }
        if (req.jdbcUrl() == null || !req.jdbcUrl().trim().startsWith("jdbc:")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "jdbcUrl must start with jdbc:");
        }
    }

    private static Throwable toStatus(Throwable t) {
        if (t instanceof DatasetException de) {
            return new ResponseStatusException(
                    HttpStatus.valueOf(de.status()), de.getMessage(), de);
        }
        return t;
    }

    private static DataSourceVO toVO(ExternalDataSourceEntity e) {
        return new DataSourceVO(
                e.getId(),
                e.getName(),
                e.getKind(),
                e.getJdbcUrl(),
                e.getUsername(),
                e.isSampling(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
    }
}
