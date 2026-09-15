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
package io.agentscope.dataagent.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * LLM 输出的结构化 Cube 查询参数。Agent 根据自然语言问题生成此对象，
 * 由 {@link io.agentscope.dataagent.semantic.service.CubeQueryToSqlConverter} 转化为可执行 SQL。
 *
 * <p>JSON 格式示例：
 * <pre>{@code
 * {
 *     "type": "cube_query",
 *     "cube": "click_metrics",
 *     "measures": ["click_pv", "click_uv"],
 *     "dimensions": ["module_name"],
 *     "timeDimensions": [
 *         {"name": "click_time", "granularity": "month", "start": "2026-08-01", "end": "2026-09-07"}
 *     ],
 *     "filters": [
 *         {"dimension": "event_company", "operator": "eq", "value": "中国移动"}
 *     ],
 *     "orderBy": [{"member": "click_pv", "direction": "desc"}],
 *     "limit": 100
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CubeQuery {

    /** 查询类型标识：固定为 "cube_query"。 */
    private String type;

    /** Cube 名称（如 "click_metrics"）。 */
    private String cube;

    /** 度量列表（如 ["click_pv", "click_uv"]）。 */
    private List<String> measures;

    /** 维度列表（如 ["module_name", "event_company"]）。 */
    private List<String> dimensions;

    /** 时间维度过滤和分组配置。 */
    private List<TimeDimensionFilter> timeDimensions;

    /** 过滤条件列表。 */
    private List<QueryFilter> filters;

    /** 排序配置。 */
    private List<OrderByItem> orderBy;

    /** 结果行数限制。 */
    private Integer limit;

    // ---- 内部类 ----

    /** 时间维度过滤器。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimeDimensionFilter {
        private String name;
        private String granularity; // day, week, month, quarter, year
        private String start;
        private String end;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getGranularity() {
            return granularity;
        }

        public void setGranularity(String granularity) {
            this.granularity = granularity;
        }

        public String getStart() {
            return start;
        }

        public void setStart(String start) {
            this.start = start;
        }

        public String getEnd() {
            return end;
        }

        public void setEnd(String end) {
            this.end = end;
        }
    }

    /** 查询过滤器。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryFilter {
        private String dimension;
        private String operator; // eq, ne, gt, gte, lt, lte, in, not_in, like, between
        private Object value;

        public String getDimension() {
            return dimension;
        }

        public void setDimension(String dimension) {
            this.dimension = dimension;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }
    }

    /** 排序项。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderByItem {
        private String member;
        private String direction; // asc, desc

        public String getMember() {
            return member;
        }

        public void setMember(String member) {
            this.member = member;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String direction) {
            this.direction = direction;
        }
    }

    // ---- Getters/Setters ----

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCube() {
        return cube;
    }

    public void setCube(String cube) {
        this.cube = cube;
    }

    public List<String> getMeasures() {
        return measures;
    }

    public void setMeasures(List<String> measures) {
        this.measures = measures;
    }

    public List<String> getDimensions() {
        return dimensions;
    }

    public void setDimensions(List<String> dimensions) {
        this.dimensions = dimensions;
    }

    public List<TimeDimensionFilter> getTimeDimensions() {
        return timeDimensions;
    }

    public void setTimeDimensions(List<TimeDimensionFilter> timeDimensions) {
        this.timeDimensions = timeDimensions;
    }

    public List<QueryFilter> getFilters() {
        return filters;
    }

    public void setFilters(List<QueryFilter> filters) {
        this.filters = filters;
    }

    public List<OrderByItem> getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(List<OrderByItem> orderBy) {
        this.orderBy = orderBy;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
