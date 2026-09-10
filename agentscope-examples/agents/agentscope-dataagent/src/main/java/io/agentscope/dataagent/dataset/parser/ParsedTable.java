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

import java.util.List;

/**
 * Result of parsing an uploaded tabular file. Cell values are canonical strings ({@code null} for
 * blanks); physical typing happens once at DDL/insert time via {@link TypeInferrer} and the
 * provisioner.
 */
public record ParsedTable(
        List<ColumnSchema> columns, List<List<String>> rows, long totalRows, boolean truncated) {}
