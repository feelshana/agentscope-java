package io.agentscope.dataagent.tools.data;

import java.util.List;

public record SqlPreviewResult(
        List<String> columns,
        List<List<String>> rows,
        int returnedRowCount,
        boolean truncated,
        String truncationReason,
        long elapsedMs) {}
