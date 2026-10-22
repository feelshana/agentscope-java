package io.agentscope.dataagent.dataset;

/** Signals that a committed published source can prepare its derived query index. */
public record PublishedQueryProjectChanged(String ownerId, String groupId) {}
