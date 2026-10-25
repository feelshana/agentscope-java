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
 *
 * <p>spec093 field screening: {@code included} (null = included, for data written before the
 * screening step), the suggested {@code role} BUSINESS / TECHNICAL / UNKNOWN with its
 * {@code roleReason}, and {@code descriptionSource} ("user" when the modeler wrote it).
 * Excluded columns never enter the semantic model.
 */
public record ColumnSchema(
        String name,
        String originalName,
        String sqlType,
        boolean nullable,
        String description,
        Boolean included,
        String role,
        String roleReason,
        String descriptionSource) {

    public static final String SOURCE_USER = "user";

    public ColumnSchema(
            String name,
            String originalName,
            String sqlType,
            boolean nullable,
            String description) {
        this(name, originalName, sqlType, nullable, description, null, null, null, null);
    }

    /** Columns stored before field screening existed count as included. */
    public boolean includedInModel() {
        return included == null || included;
    }

    public boolean userDescribed() {
        return SOURCE_USER.equals(descriptionSource);
    }

    public ColumnSchema withDescription(String text, String source) {
        return new ColumnSchema(
                name, originalName, sqlType, nullable, text, included, role, roleReason, source);
    }

    public ColumnSchema withScreening(Boolean include, String suggestedRole, String reason) {
        return new ColumnSchema(
                name,
                originalName,
                sqlType,
                nullable,
                description,
                include,
                suggestedRole,
                reason,
                descriptionSource);
    }
}
