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
package io.agentscope.dataagent.semantic.service;

import io.agentscope.dataagent.semantic.model.CubeDimension;
import io.agentscope.dataagent.semantic.model.CubeMeasure;
import io.agentscope.dataagent.semantic.model.CubeQuery;
import io.agentscope.dataagent.semantic.model.CubeTimeDimension;
import io.agentscope.dataagent.semantic.model.SemanticCube;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.model.SemanticModelTable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cube 查询 → SQL 转换器。将 LLM 输出的结构化 CubeQuery JSON 转化为可执行 SQL。
 *
 * <p>转换流程：
 * <ol>
 *   <li>查找 Cube 定义和基础物理表</li>
 *   <li>构建 SELECT 列（维度 + 时间维度 + 度量）</li>
 *   <li>构建 FROM（物理表名）</li>
 *   <li>构建 WHERE（filters + timeDimensions 范围）</li>
 *   <li>构建 GROUP BY（维度 + 时间维度分组表达式）</li>
 *   <li>构建 ORDER BY + LIMIT</li>
 * </ol>
 */
@Component
public class CubeQueryToSqlConverter {

    private static final Logger log = LoggerFactory.getLogger(CubeQueryToSqlConverter.class);

    /**
     * 将 CubeQuery 转换为可执行 SQL。
     *
     * @param query LLM 输出的结构化查询参数
     * @param model 语义模型（包含 Cube、Model 定义）
     * @return 可执行 SQL 字符串
     * @throws IllegalArgumentException Cube 或 Model 找不到、参数非法
     */
    public String convert(CubeQuery query, SemanticModel model) {
        if (query == null) {
            throw new IllegalArgumentException("CubeQuery 不能为空");
        }
        if (model == null) {
            throw new IllegalArgumentException("SemanticModel 不能为空");
        }

        // 1. 查找 Cube
        SemanticCube cube = model.findCube(query.getCube());
        if (cube == null) {
            throw new IllegalArgumentException("找不到 Cube: " + query.getCube());
        }

        // 2. 查找基础物理表
        SemanticModelTable baseTable = model.findModel(cube.getBaseObject());
        if (baseTable == null) {
            throw new IllegalArgumentException(
                    "Cube '" + cube.getName() + "' 引用的基础模型不存在: " + cube.getBaseObject());
        }

        StringBuilder sql = new StringBuilder();

        // ---- SELECT ----
        List<String> selectParts = new ArrayList<>();
        List<String> groupByParts = new ArrayList<>();

        // 维度列
        if (query.getDimensions() != null) {
            for (String dimName : query.getDimensions()) {
                CubeDimension dim = findCubeDimension(cube, dimName);
                if (dim != null) {
                    selectParts.add(dim.getExpression() + " AS " + quoteIdentifier(dimName));
                    groupByParts.add(dim.getExpression());
                } else {
                    log.warn("Cube '{}' 中找不到维度 '{}', 尝试直接引用", cube.getName(), dimName);
                    selectParts.add(quoteIdentifier(dimName));
                    groupByParts.add(quoteIdentifier(dimName));
                }
            }
        }

        // 时间维度列
        if (query.getTimeDimensions() != null) {
            for (CubeQuery.TimeDimensionFilter td : query.getTimeDimensions()) {
                CubeTimeDimension timeDim = findCubeTimeDimension(cube, td.getName());
                String expr = timeDim != null ? timeDim.getExpression() : td.getName();
                String timeExpr = buildTimeExpression(expr, td.getGranularity());
                selectParts.add(timeExpr + " AS " + quoteIdentifier(td.getName()));
                groupByParts.add(timeExpr);
            }
        }

        // 度量列
        if (query.getMeasures() != null) {
            for (String measureName : query.getMeasures()) {
                CubeMeasure measure = findCubeMeasure(cube, measureName);
                if (measure != null) {
                    selectParts.add(
                            measure.getExpression() + " AS " + quoteIdentifier(measureName));
                } else {
                    throw new IllegalArgumentException(
                            "Cube '" + cube.getName() + "' 中找不到度量: " + measureName);
                }
            }
        }

        sql.append("SELECT ");
        sql.append(String.join(", ", selectParts));

        // ---- FROM ----
        sql.append("\nFROM ").append(baseTable.getTableName());

        // ---- WHERE ----
        List<String> whereClauses = new ArrayList<>();

        // filters
        if (query.getFilters() != null) {
            for (CubeQuery.QueryFilter filter : query.getFilters()) {
                String clause = buildFilterClause(filter, cube);
                if (clause != null) {
                    whereClauses.add(clause);
                }
            }
        }

        // timeDimensions range
        if (query.getTimeDimensions() != null) {
            for (CubeQuery.TimeDimensionFilter td : query.getTimeDimensions()) {
                CubeTimeDimension timeDim = findCubeTimeDimension(cube, td.getName());
                String expr = timeDim != null ? timeDim.getExpression() : td.getName();
                if (td.getStart() != null) {
                    whereClauses.add(expr + " >= '" + escapeSql(td.getStart()) + "'");
                }
                if (td.getEnd() != null) {
                    whereClauses.add(expr + " < '" + escapeSql(td.getEnd()) + "'");
                }
            }
        }

        if (!whereClauses.isEmpty()) {
            sql.append("\nWHERE ").append(String.join("\n  AND ", whereClauses));
        }

        // ---- GROUP BY ----
        if (!groupByParts.isEmpty()) {
            sql.append("\nGROUP BY ").append(String.join(", ", groupByParts));
        }

        // ---- ORDER BY ----
        if (query.getOrderBy() != null && !query.getOrderBy().isEmpty()) {
            List<String> orderByParts = new ArrayList<>();
            for (CubeQuery.OrderByItem item : query.getOrderBy()) {
                String dir = "desc".equalsIgnoreCase(item.getDirection()) ? "DESC" : "ASC";
                // 如果是度量名，用 alias 引用
                if (query.getMeasures() != null && query.getMeasures().contains(item.getMember())) {
                    orderByParts.add(quoteIdentifier(item.getMember()) + " " + dir);
                } else if (query.getDimensions() != null
                        && query.getDimensions().contains(item.getMember())) {
                    orderByParts.add(quoteIdentifier(item.getMember()) + " " + dir);
                } else {
                    orderByParts.add(item.getMember() + " " + dir);
                }
            }
            sql.append("\nORDER BY ").append(String.join(", ", orderByParts));
        }

        // ---- LIMIT ----
        if (query.getLimit() != null && query.getLimit() > 0) {
            sql.append("\nLIMIT ").append(query.getLimit());
        }

        String result = sql.toString();
        log.debug("CubeQueryToSqlConverter: 生成 SQL:\n{}", result);
        return result;
    }

    // ---- 辅助方法 ----

    private CubeDimension findCubeDimension(SemanticCube cube, String name) {
        if (cube.getDimensions() == null || name == null) {
            return null;
        }
        return cube.getDimensions().stream()
                .filter(d -> name.equals(d.getName()))
                .findFirst()
                .orElse(null);
    }

    private CubeTimeDimension findCubeTimeDimension(SemanticCube cube, String name) {
        if (cube.getTimeDimensions() == null || name == null) {
            return null;
        }
        return cube.getTimeDimensions().stream()
                .filter(d -> name.equals(d.getName()))
                .findFirst()
                .orElse(null);
    }

    private CubeMeasure findCubeMeasure(SemanticCube cube, String name) {
        if (cube.getMeasures() == null || name == null) {
            return null;
        }
        return cube.getMeasures().stream()
                .filter(m -> name.equals(m.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 构建时间粒度分组表达式（MySQL 方言）。
     */
    private String buildTimeExpression(String expr, String granularity) {
        if (granularity == null || granularity.isBlank()) {
            return expr;
        }
        return switch (granularity.toLowerCase()) {
            case "day" -> "DATE(" + expr + ")";
            case "week" -> "DATE(DATE_SUB(" + expr + ", INTERVAL WEEKDAY(" + expr + ") DAY))";
            case "month" -> "DATE_FORMAT(" + expr + ", '%Y-%m')";
            case "quarter" -> "CONCAT(YEAR(" + expr + "), '-Q', QUARTER(" + expr + "))";
            case "year" -> "YEAR(" + expr + ")";
            default -> expr;
        };
    }

    /**
     * 构建过滤条件 SQL 子句。
     */
    private String buildFilterClause(CubeQuery.QueryFilter filter, SemanticCube cube) {
        if (filter == null || filter.getDimension() == null || filter.getOperator() == null) {
            return null;
        }

        // 查找维度的物理表达式
        String colExpr = filter.getDimension();
        CubeDimension dim = findCubeDimension(cube, filter.getDimension());
        if (dim != null) {
            colExpr = dim.getExpression();
        }

        Object value = filter.getValue();
        return switch (filter.getOperator().toLowerCase()) {
            case "eq" -> colExpr + " = " + formatValue(value);
            case "ne" -> colExpr + " != " + formatValue(value);
            case "gt" -> colExpr + " > " + formatValue(value);
            case "gte" -> colExpr + " >= " + formatValue(value);
            case "lt" -> colExpr + " < " + formatValue(value);
            case "lte" -> colExpr + " <= " + formatValue(value);
            case "in" -> colExpr + " IN (" + formatInValues(value) + ")";
            case "not_in" -> colExpr + " NOT IN (" + formatInValues(value) + ")";
            case "like" -> colExpr + " LIKE '%" + escapeSql(String.valueOf(value)) + "%'";
            case "between" -> formatBetween(colExpr, value);
            default -> colExpr + " = " + formatValue(value);
        };
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return "'" + escapeSql(String.valueOf(value)) + "'";
    }

    @SuppressWarnings("unchecked")
    private String formatInValues(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::formatValue).collect(Collectors.joining(", "));
        }
        return formatValue(value);
    }

    @SuppressWarnings("unchecked")
    private String formatBetween(String colExpr, Object value) {
        if (value instanceof List<?> list && list.size() == 2) {
            return colExpr
                    + " BETWEEN "
                    + formatValue(list.get(0))
                    + " AND "
                    + formatValue(list.get(1));
        }
        return colExpr + " = " + formatValue(value);
    }

    /** SQL 标识符引用。 */
    private String quoteIdentifier(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    /** SQL 字符串转义（防止注入）。 */
    private String escapeSql(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("'", "''").replace("\\", "\\\\");
    }
}
