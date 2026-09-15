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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sources.json 中的单个 batch 定义：一个 Excel 文件 → 一张 MySQL 表。
 *
 * <pre>
 * { "id": "user-list", "file": "用户列表.xlsx", "table": "app_user", "columns": [...] }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourceBatch {

    /** 批次唯一标识，如 "user-list"。 */
    private String id;

    /** Excel 源文件名。 */
    private String file;

    /** 目标 MySQL 表名。 */
    private String table;

    /** 时间维度字段名（可选）。 */
    private String time_field;

    /** 常量列注入（可选），如 {"scan_date": "2026-09-09"}。 */
    private Map<String, String> constants = new LinkedHashMap<>();

    /** 列定义列表：每项含 name（MySQL 列名）、type、comment。 */
    private List<ColumnMapping> columns = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getTime_field() {
        return time_field;
    }

    public void setTime_field(String time_field) {
        this.time_field = time_field;
    }

    public Map<String, String> getConstants() {
        return constants;
    }

    public void setConstants(Map<String, String> constants) {
        this.constants = constants;
    }

    public List<ColumnMapping> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnMapping> columns) {
        this.columns = columns;
    }
}
