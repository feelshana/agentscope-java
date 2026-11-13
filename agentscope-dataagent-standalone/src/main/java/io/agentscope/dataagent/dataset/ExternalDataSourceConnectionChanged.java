package io.agentscope.dataagent.dataset;

/** Invalidates derived connection state after a committed configuration or availability change. */
public record ExternalDataSourceConnectionChanged(String sourceId) {}
