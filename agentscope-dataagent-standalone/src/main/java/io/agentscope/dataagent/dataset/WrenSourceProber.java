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

import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import java.util.List;

/**
 * Publish-time connectivity probe for a group bound to an external datasource (specs/010 M4,
 * ADR 0021).
 *
 * <p>{@code wren context validate/build} never open a database connection, so a table that is
 * unreachable on the remote instance (typo in the JDBC url, dropped table, network block) would
 * only surface as a runtime 1146 after publishing. The prober moves that failure to the publish
 * click — the one place where the user is already acting on the group and can fix the source.
 */
public interface WrenSourceProber {

    /** Outcome of one probe; {@code message} is a user-facing Simplified Chinese sentence. */
    record ProbeReport(boolean ok, String message, List<String> missingTables) {

        static ProbeReport success() {
            return new ProbeReport(true, null, List.of());
        }

        static ProbeReport fail(String message, List<String> missingTables) {
            return new ProbeReport(false, message, missingTables);
        }
    }

    /**
     * Verifies the datasource is reachable and every dataset's physical table exists on it.
     * Never throws — connection failures come back as an {@code ok=false} report.
     */
    ProbeReport probe(ExternalDataSourceEntity source, List<DatasetEntity> datasets);
}
