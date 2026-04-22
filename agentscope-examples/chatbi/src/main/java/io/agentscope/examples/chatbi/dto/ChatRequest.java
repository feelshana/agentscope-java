/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.chatbi.dto;

/**
 * Request object for the ChatBI chat API.
 *
 * <p>Carries the user's message plus rich context (report, dashboard, token, etc.)
 * that mirrors the start-node variables in ChatBI.yml.
 */
public class ChatRequest {

    private String sessionId;
    private String message;
    private String userName;

    /** Current report ID from the frontend context. */
    private String reportId;

    /** Current report name from the frontend context. */
    private String reportName;

    /** Current dashboard ID from the frontend context. */
    private String dashboardId;

    /** Current dashboard name from the frontend context. */
    private String dashboardName;

    /** Tenant / project ID. */
    private String projectId;

    /** SuperSonic Agent ID for data query routing. */
    private String agentId;

    /** SuperSonic auth token, passed per-request from frontend. */
    private String supersonicToken;

    private String easyBiSession;

    /**
     * Chart/table data payload for "请解读当前数据" requests.
     * Contains the frontend's current chart data as JSON string.
     */
    private String param;

    public ChatRequest() {}

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getReportName() {
        return reportName;
    }

    public void setReportName(String reportName) {
        this.reportName = reportName;
    }

    public String getDashboardId() {
        return dashboardId;
    }

    public void setDashboardId(String dashboardId) {
        this.dashboardId = dashboardId;
    }

    public String getDashboardName() {
        return dashboardName;
    }

    public void setDashboardName(String dashboardName) {
        this.dashboardName = dashboardName;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSupersonicToken() {
        return supersonicToken;
    }

    public void setSupersonicToken(String supersonicToken) {
        this.supersonicToken = supersonicToken;
    }

    public String getParam() {
        return param;
    }

    public void setParam(String param) {
        this.param = param;
    }

    public String getEasyBiSession() {
        return easyBiSession;
    }

    public void setEasyBiSession(String easyBiSession) {
        this.easyBiSession = easyBiSession;
    }
}
