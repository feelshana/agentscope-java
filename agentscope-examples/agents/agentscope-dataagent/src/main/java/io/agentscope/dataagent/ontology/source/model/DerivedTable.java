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
 * sources.json 中的派生表定义。所有批次加载完毕后按声明顺序执行 SQL 创建。
 *
 * <pre>
 * { "table": "user_identity", "sql": "SELECT ..." }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DerivedTable {

    /** 目标表名。 */
    private String table;

    /** 创建该表的 SQL 语句（应为 MySQL 8.0 兼容方言）。 */
    private String sql;

    /** 业务注释（可选）。 */
    private String comment;

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
