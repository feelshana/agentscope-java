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
package io.agentscope.dataagent.ontology.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * 业务规则定义。包含定义、阈值、身份去重、资格排除等类型。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntologyRule {

    private String statement;

    /** definition, threshold, identity_dedup, eligibility 等。 */
    private String kind;

    private Map<String, Object> params;
    private List<String> open_questions;

    /** 仅 identity_dedup 类型：always / conditional。 */
    private String enforcement;

    private List<String> applies_to_metrics;
    private String note;

    /** 仅 eligibility 类型：用途标签。 */
    private String purpose;

    private List<Map<String, String>> exclude;
    private List<Map<String, String>> defer;
    private boolean requires_full_coverage;

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public List<String> getOpen_questions() {
        return open_questions;
    }

    public void setOpen_questions(List<String> open_questions) {
        this.open_questions = open_questions;
    }

    public String getEnforcement() {
        return enforcement;
    }

    public void setEnforcement(String enforcement) {
        this.enforcement = enforcement;
    }

    public List<String> getApplies_to_metrics() {
        return applies_to_metrics;
    }

    public void setApplies_to_metrics(List<String> applies_to_metrics) {
        this.applies_to_metrics = applies_to_metrics;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public List<Map<String, String>> getExclude() {
        return exclude;
    }

    public void setExclude(List<Map<String, String>> exclude) {
        this.exclude = exclude;
    }

    public List<Map<String, String>> getDefer() {
        return defer;
    }

    public void setDefer(List<Map<String, String>> defer) {
        this.defer = defer;
    }

    public boolean isRequires_full_coverage() {
        return requires_full_coverage;
    }

    public void setRequires_full_coverage(boolean requires_full_coverage) {
        this.requires_full_coverage = requires_full_coverage;
    }
}
