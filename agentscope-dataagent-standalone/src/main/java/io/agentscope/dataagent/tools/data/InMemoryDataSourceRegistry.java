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
package io.agentscope.dataagent.tools.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link DataSourceRegistry} backed by a thread-safe in-memory map. Seeded at construction
 * (e.g. the embedded-H2 {@code demo-db}) and mutable afterwards: {@link #add(DataSource)} / {@link
 * #remove(String)} let the dataset ingestion flow register user-uploaded sources at runtime while
 * agents concurrently read via {@link #list()} / {@link #findById(String)}. Operators needing
 * durable registration can still replace the bean with a JPA- or Nacos-backed implementation.
 */
public final class InMemoryDataSourceRegistry implements DataSourceRegistry {

    private final ConcurrentMap<String, DataSource> byId = new ConcurrentHashMap<>();

    public InMemoryDataSourceRegistry(List<DataSource> seed) {
        Objects.requireNonNull(seed, "seed");
        for (DataSource ds : seed) {
            if (ds == null) continue;
            byId.put(ds.id(), ds);
        }
    }

    @Override
    public List<DataSource> list() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public Optional<DataSource> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.ofNullable(byId.get(id));
    }

    /** Registers or replaces a data source; visible to agents on the next read. */
    public void add(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        byId.put(dataSource.id(), dataSource);
    }

    /** Removes a data source by id; no-op when absent. */
    public void remove(String id) {
        if (id == null || id.isBlank()) return;
        byId.remove(id);
    }
}
