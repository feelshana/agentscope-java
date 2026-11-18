package io.agentscope.dataagent.dataset;

/** Bounded product status for one native generation; full conversation stays in the transcript. */
public record DashboardGeneration(
        String id,
        String userId,
        String processId,
        String status,
        String phase,
        String startedAt,
        String finishedAt,
        String baseRevision,
        String checkpointRevision,
        String resultRevision,
        int autoAttempt,
        String message,
        String answer,
        String error) {
    public static final String FILE_NAME = "generation.json";

    public boolean active() {
        return "QUEUED".equals(status) || "RUNNING".equals(status);
    }

    public DashboardGeneration progress(
            String nextStatus,
            String nextPhase,
            String nextAnswer,
            String nextError,
            String finished,
            String revision) {
        return new DashboardGeneration(
                id,
                userId,
                processId,
                nextStatus,
                nextPhase,
                startedAt,
                finished,
                baseRevision,
                checkpointRevision,
                revision,
                autoAttempt,
                message,
                nextAnswer,
                nextError);
    }
}
