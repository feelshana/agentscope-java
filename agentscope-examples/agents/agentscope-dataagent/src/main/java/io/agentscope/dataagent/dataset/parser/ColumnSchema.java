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
package io.agentscope.dataagent.dataset.parser;

/**
 * A physical column of a parsed dataset. {@code name} is the sanitised SQL identifier
 * ({@code [A-Za-z0-9_]+}); {@code originalName} preserves the source header (often Chinese);
 * {@code description} carries the business semantics (defaults to the original header, editable
 * in the dataset detail page) so the agent and the UI share one understanding of the column.
 */
public record ColumnSchema(
        String name, String originalName, String sqlType, boolean nullable, String description) {}
