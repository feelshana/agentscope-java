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
package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Persists server-built ECharts options keyed by chart id. The render_chart tool returns only a
 * small payload ({chart, chartType, title, chartId}) so harness tool-output truncation can never
 * corrupt the option; the UI fetches the full option by id, which also survives restarts so
 * history sessions re-render their charts.
 */
@Entity
@Table(name = "dataagent_chart_option")
public class ChartOptionEntity {

    @Id
    @Column(name = "chart_id", length = 64, nullable = false)
    private String chartId;

    @Lob
    @Column(name = "option_json")
    private String optionJson;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public ChartOptionEntity() {}

    public ChartOptionEntity(String chartId, String optionJson) {
        this.chartId = chartId;
        this.optionJson = optionJson;
        this.createdAt = Instant.now();
    }

    public String getChartId() {
        return chartId;
    }

    public void setChartId(String chartId) {
        this.chartId = chartId;
    }

    public String getOptionJson() {
        return optionJson;
    }

    public void setOptionJson(String optionJson) {
        this.optionJson = optionJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
