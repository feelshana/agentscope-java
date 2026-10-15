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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * JDBC implementation of {@link WrenSourceProber}: opens a short-lived connection to the
 * external datasource and looks each dataset's physical table up in {@code
 * information_schema.tables}.
 *
 * <p>Blocking by design — publish already runs blocking wren CLI subprocesses on the caller's
 * bounded-elastic scheduler. {@code DriverManager#setLoginTimeout} bounds the connection attempt
 * so a dead host fails the publish quickly instead of hanging it. The url may carry no database
 * segment (the runtime-configured Tencent-Cloud style url from ADR 0021); {@code
 * information_schema} is reachable without a default schema, so no url rewriting is needed.
 */
@Component
public class JdbcWrenSourceProber implements WrenSourceProber {

    private final DataSourceIntrospector introspector;

    public JdbcWrenSourceProber(DataSourceIntrospector introspector) {
        this.introspector = introspector;
    }

    @Override
    public ProbeReport probe(ExternalDataSourceEntity source, List<DatasetEntity> datasets) {
        Set<String> missing = new LinkedHashSet<>();
        try (Connection conn = introspector.open(source)) {
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "SELECT 1 FROM information_schema.tables"
                                    + " WHERE table_schema = ? AND table_name = ?")) {
                ps.setQueryTimeout(30);
                for (DatasetEntity d : datasets) {
                    if (d.getSchemaName() == null || d.getTableName() == null) {
                        continue;
                    }
                    ps.setString(1, d.getSchemaName());
                    ps.setString(2, d.getTableName());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            missing.add(d.getSchemaName() + "." + d.getTableName());
                        }
                    }
                }
            }
        } catch (SQLException e) {
            return ProbeReport.fail(
                    "无法连接外部数据源「"
                            + source.getName()
                            + "」（"
                            + source.getJdbcUrl()
                            + "）："
                            + e.getMessage(),
                    List.of());
        }
        if (!missing.isEmpty()) {
            List<String> ordered = new ArrayList<>(missing);
            return ProbeReport.fail(
                    "外部数据源「"
                            + source.getName()
                            + "」上不存在以下表："
                            + String.join("、", ordered)
                            + "。请检查数据源的 JDBC 地址或表是否被删除，修复后再发布。",
                    ordered);
        }
        return ProbeReport.success();
    }
}
