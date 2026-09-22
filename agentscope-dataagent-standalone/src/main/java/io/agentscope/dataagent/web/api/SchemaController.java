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

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Returns the schema and relationship metadata for the tenant analytics
 * tables. Currently hardcoded for {@code tenant_storage_utilization} and
 * {@code project_info} — replace with dynamic introspection when the data
 * source topology grows.
 *
 * <p>The agent (and external tools) can call this endpoint to understand
 * what tables are available, their columns with Chinese descriptions,
 * and how they relate to each other.
 */
@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> getSchema() {
        return Mono.just(buildSchema());
    }

    private static Map<String, Object> buildSchema() {
        List<Map<String, Object>> tables =
                List.of(
                        Map.of(
                                "tableName",
                                "tenant_storage_utilization",
                                "description",
                                "项目存储利用表 — 每个租户（项目）在各级数据库中的表级存储指标",
                                "columns",
                                List.of(
                                        col("id", "INT", "主键"),
                                        col("project_name", "VARCHAR(64)", "项目名"),
                                        col("db_name", "VARCHAR(64)", "库"),
                                        col("table_name", "VARCHAR(64)", "表"),
                                        col("size_gb", "DECIMAL(10,2)", "表大小(GB)"),
                                        col("file_count", "INT", "表文件数"),
                                        col("dir_path", "VARCHAR(256)", "表目录树"),
                                        col("uncompressed_size_gb", "DECIMAL(10,2)", "未压缩文件大小(GB)"),
                                        col("small_file_size_gb", "DECIMAL(10,2)", "小文件大小(GB)"),
                                        col("expired_file_size_gb", "DECIMAL(10,2)", "过期文件大小(GB)"),
                                        col("expired_file_count", "INT", "过期文件数量")),
                                "primaryKey",
                                "id"),
                        Map.of(
                                "tableName",
                                "project_info",
                                "description",
                                "项目信息表 — 租户（项目）的基本信息，含负责人",
                                "columns",
                                List.of(
                                        col("id", "INT", "项目ID"),
                                        col("project_name", "VARCHAR(64)", "项目名称"),
                                        col("owner_name", "VARCHAR(32)", "负责人名称")),
                                "primaryKey",
                                "id"));

        List<Map<String, String>> relationships =
                List.of(
                        Map.of(
                                "fromTable",
                                "tenant_storage_utilization",
                                "fromColumn",
                                "project_name",
                                "toTable",
                                "project_info",
                                "toColumn",
                                "project_name",
                                "type",
                                "logical_fk",
                                "description",
                                "通过项目名将存储利用数据关联到项目信息和负责人"));

        return Map.of(
                "dataSource",
                Map.of(
                        "id",
                        "test-data",
                        "type",
                        "mysql",
                        "url",
                        "jdbc:mysql://127.0.0.1:3306/test_data"),
                "tables",
                tables,
                "relationships",
                relationships);
    }

    private static Map<String, String> col(String name, String type, String description) {
        return Map.of("name", name, "type", type, "description", description);
    }
}
