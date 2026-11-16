package io.agentscope.dataagent.dataset;

/** Server-bound dashboard identity carried by the native conversation runtime. */
public record DashboardTurn(String dashboardId) {}
