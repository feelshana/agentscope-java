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
package io.agentscope.dataagent.ontology.source.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * sources.json 中 columns 列表的每个元素。
 *
 * <pre>
 * { "comment": "用户ID", "name": "user_id", "type": "varchar(200)" }
 * </pre>
 *
 * <p>{@code name} 即 MySQL 列名；{@code type} 可以是 MySQL DDL 类型（如 varchar(200)）或逻辑类型
 * （percent / yesno / date_compact），后者由 {@link #toMysqlType()} 转换。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ColumnMapping {

    /** MySQL 列名（同时用于建表和 Excel 表头匹配）。 */
    private String name;

    /** MySQL DDL 类型或逻辑类型（varchar(200), bigint, timestamp, percent, yesno, date_compact）。 */
    private String type;

    /** 业务注释（可选）。 */
    private String comment;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    /**
     * 将 type 解析为 MySQL 8.0 DDL 片段。
     *
     * <p>如果是已知的逻辑类型则转换；否则原样返回（视为已合法的 DDL 类型）。
     */
    public String toMysqlType() {
        if (type == null || type.isBlank()) {
            return "VARCHAR(255)";
        }
        return switch (type.toLowerCase()) {
            case "bigint" -> "BIGINT";
            case "double" -> "DOUBLE";
            case "timestamp" -> "DATETIME";
            case "date_compact" -> "DATE";
            case "percent" -> "DECIMAL(18,6)";
            case "yesno" -> "TINYINT(1)";
            default -> type; // already a DDL type like varchar(200)
        };
    }
}
