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

/**
 * SPI for executing {@code describe_table} / {@code run_sql_preview} against a configured
 * {@link DataSource}. Mirrors {@link ChartRenderer}: implementations live behind a Spring
 * {@code @Bean} so operators can swap the JDBC connector for a BigQuery / Hologres connector
 * without touching {@link DataAgentToolkit}.
 *
 * <p>Implementations decide themselves which sources they can serve via {@link #supports}; the
 * toolkit routes per source and surfaces a clear error string when no connector matches, so the
 * agent reports the limitation instead of hallucinating results.
 */
public interface SqlConnector {

    /** Returns {@code true} when this connector can serve the given data source. */
    boolean supports(DataSource source);

    /**
     * Returns the column schema, row count, and a short sample for a table, formatted as a small
     * markdown report.
     *
     * @param source the data source to introspect (from {@link DataSourceRegistry#findById})
     * @param table fully-qualified table name as understood by the source
     * @return markdown report; callers format errors as {@code "error: ..."} strings
     */
    String describeTable(DataSource source, String table);

    /**
     * Executes a read-only SQL statement and returns the first N rows as a small markdown report.
     *
     * <p>The report starts with the natural-language question and the SQL statement that was
     * executed, followed by the result table — so every tool invocation is self-documenting.
     *
     * @param source the data source to query
     * @param sql SELECT / WITH statement to execute
     * @param question optional natural-language question being answered (for display; may be null)
     * @param rowLimit max rows to return; implementations enforce a hard cap
     * @return markdown report with question, SQL, and result table; errors as {@code
     *     "error: ..."} strings
     */
    String runSqlPreview(DataSource source, String sql, String question, int rowLimit);
}
