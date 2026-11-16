package io.agentscope.dataagent.dataset;

import java.util.List;

/** Shared MDL result contracts; data shapes have no dependency on read or publish orchestration. */
public final class MdlTypes {
    private MdlTypes() {}

    // ------------------------------------------------------------------ public API result types

    /** One structured problem: {@code severity} = error|warning, {@code source} = local|wren. */
    public record MdlIssue(String severity, String message, String source) {}

    /** One workspace/snapshot file; {@code path} is project-relative with '/' separators. */
    public record MdlFile(String path, String content) {}

    /** Workspace preview: current files, the published snapshot, issues and group state. */
    public record MdlPreview(
            List<MdlFile> files,
            List<MdlFile> publishedFiles,
            List<MdlIssue> issues,
            String mdlState,
            int mdlVersion,
            String mdlPublishedAt,
            boolean changed) {}

    /** Outcome of a validation run; {@code output} is the trimmed wren CLI transcript. */
    public record MdlValidation(
            boolean ok, List<MdlIssue> issues, String output, List<MdlFile> files) {}

    /** Outcome of a publish attempt; on success the group state reflects the new version. */
    public record MdlPublishResult(
            boolean ok,
            List<MdlIssue> issues,
            String output,
            String mdlState,
            int mdlVersion,
            String mdlPublishedAt) {}

    // ------------------------------------------------------------- read-only view (specs/013 M2,
    // shapes preserved for the frontend)

    /**
     * One column in a logical model; {@code relationship}/{@code calculated} carry the badges.
     * The last five are platform-maintained {@code properties} the engine ignores (spec117).
     */
    public record MdlColumnView(
            String name,
            String type,
            String description,
            boolean relationship,
            boolean calculated,
            String expression,
            boolean primaryKey,
            String displayName,
            String semanticType,
            List<String> synonyms,
            List<String> values,
            boolean userDescribed) {}

    /**
     * One logical model with its full column list; {@code tableName} is the physical table.
     * {@code refSqlPath} points at the defining SQL file for a derived (ref_sql) model and is
     * {@code null} for physical models (specs/034).
     */
    public record MdlModelView(
            String datasetId,
            String datasetName,
            String tableName,
            String modelName,
            String description,
            List<MdlColumnView> columns,
            String path,
            String refSqlPath,
            String primaryKey,
            String tableRole) {}

    /** One relation edge with its (possibly composite) column pair list. */
    public record MdlRelationView(
            String name,
            String leftModel,
            String rightModel,
            String joinType,
            String condition,
            List<String> sourceColumns,
            List<String> targetColumns,
            String description) {}

    /** One cube member rendered from the workspace file. */
    public record MdlCubeMemberView(
            String name, String expression, String type, String description) {}

    /** One semantic cube with member lists. */
    public record MdlCubeView(
            String name,
            String baseModel,
            String description,
            List<MdlCubeMemberView> measures,
            List<MdlCubeMemberView> dimensions,
            List<MdlCubeMemberView> timeDimensions,
            String path) {}

    /** One named SQL view with its statement (specs/011 M2). */
    public record MdlViewSummary(String name, String sql, String description, String path) {}

    /**
     * Read-only MDL visualization payload (specs/013 M2, ADR 0024 D5): the workspace as it stands
     * (what you see is exactly what a publish would ship) plus the publication status and a
     * {@code changed} flag comparing the workspace against the published snapshot.
     */
    public record MdlView(
            String state,
            int version,
            String publishedAt,
            boolean changed,
            List<MdlModelView> models,
            List<MdlRelationView> relations,
            List<MdlCubeView> cubes,
            List<MdlViewSummary> views,
            List<MdlIssue> issues,
            List<MdlModelView> derivedModels) {}

    /** The official compiled MDL text of one version and when it was built; null when absent. */
    public record CompiledMdl(
            String published, String publishedBuiltAt, String draft, String draftBuiltAt) {}
}
