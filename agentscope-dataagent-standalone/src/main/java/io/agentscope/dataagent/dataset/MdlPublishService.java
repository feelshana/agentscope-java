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
package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MDL publishing v2 (specs/019 M2, ADR 0033): the group's workspace is the single source of
 * truth. Publishing means: reconcile the workspace with the current datasets ({@link
 * MdlSeeder}), copy it into the staging directory, run {@code wren context validate --strict}
 * and {@code context build}, dry-run every view and cube ({@code cube query --sql-only} is pure
 * wren-core SQL transformation, no database connection), then swap the immutable {@code
 * published/} snapshot and the {@code mdl.json} manifest atomically. Failures preserve the
 * previous snapshot.
 *
 * <p>The v1 in-DB compiler (assemble/render from relation/cube/view entities) is retired: the
 * DRAFT/PUBLISHED state machine is gone, the group state stays PUBLISHED while an old snapshot
 * keeps serving queries, and {@code changed} is computed as a file diff between the workspace
 * and the published snapshot.
 *
 * <p>Lock order contract: the publish lock is only ever acquired alone or as the outermost lock;
 * {@code doPublish} nests the workspace lock via {@link MdlSeeder#reconcile}. Nothing may take
 * the publish lock while holding a workspace lock.
 *
 * <p>All methods are blocking (JPA + subprocess); callers must run them on a bounded-elastic
 * scheduler.
 */
@Service
public class MdlPublishService {

    private static final Logger log = LoggerFactory.getLogger(MdlPublishService.class);

    private static final Pattern SUMMARY =
            Pattern.compile("(\\d+)\\s+warning\\(s\\),\\s+(\\d+)\\s+error\\(s\\)");

    private static final Pattern NUMBER_LIKE =
            Pattern.compile(
                    "[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?|0[xX][0-9a-fA-F]+|\\d{4}-\\d{2}-\\d{2}.*");

    private static final Pattern PLAIN_SAFE_START = Pattern.compile("^[\\u4e00-\\u9fffA-Za-z0-9_]");

    private static final String YAML_INDICATORS = "-?:,[]{}#&*!|>'\"%@`";

    private static final Set<String> BOOL_LIKE =
            Set.of("true", "false", "yes", "no", "on", "off", "null", "~");

    private final DatasetRepository datasetRepository;
    private final DatasetGroupRepository groupRepository;
    private final ExternalDataSourceRepository externalSources;
    private final ObjectMapper mapper;
    private final WrenProperties props;
    private final WrenCli wrenCli;
    private final WrenProfileHome profileHome;
    private final WrenSourceProber sourceProber;
    private final MdlSeeder seeder;
    private final MdlWorkspaceService workspace;
    private final MdlWorkspaceReader reader;
    private final MdlQuestionStore questionStore;

    /**
     * Per-group mutex serialising the whole validate/publish flow (reconcile → staging copy →
     * validate → build → snapshot swap). Concurrent entry points (parallel single-file uploads,
     * upload vs modeling-page validate/publish) share one staging directory and the fixed {@code
     * published.next}/{@code mdl.json.next} paths, so overlapping runs corrupt each other. Lock
     * objects are tiny and intentionally never evicted.
     */
    private final Map<String, ReentrantLock> publishLocks = new ConcurrentHashMap<>();

    /**
     * Per group: {@link System#nanoTime()} tick at which the last successful publish began
     * reconciling (specs/015 coalescing). An automatic baseline request whose funnel-entry tick
     * predates that moment is necessarily reflected in the published snapshot and skips its own
     * run. Like {@link #publishLocks}, entries are tiny and intentionally never evicted.
     */
    private final Map<String, Long> assembledAtTicks = new ConcurrentHashMap<>();

    public MdlPublishService(
            DatasetRepository datasetRepository,
            DatasetGroupRepository groupRepository,
            ExternalDataSourceRepository externalSources,
            ObjectMapper mapper,
            WrenProperties props,
            WrenCli wrenCli,
            WrenProfileHome profileHome,
            WrenSourceProber sourceProber,
            MdlSeeder seeder,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader reader) {
        this.datasetRepository = datasetRepository;
        this.groupRepository = groupRepository;
        this.externalSources = externalSources;
        this.mapper = mapper;
        this.props = props;
        this.wrenCli = wrenCli;
        this.profileHome = profileHome;
        this.sourceProber = sourceProber;
        this.seeder = seeder;
        this.workspace = workspace;
        this.reader = reader;
        this.questionStore = new MdlQuestionStore(workspace);
    }

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

    /** One column in a logical model; {@code relationship}/{@code calculated} carry the badges. */
    public record MdlColumnView(
            String name,
            String type,
            String description,
            boolean relationship,
            boolean calculated,
            String expression) {}

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
            String refSqlPath) {}

    /** One relation edge with its (possibly composite) column pair list. */
    public record MdlRelationView(
            String name,
            String leftModel,
            String rightModel,
            String joinType,
            String condition,
            List<String> sourceColumns,
            List<String> targetColumns) {}

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

    // ------------------------------------------------------------------ public API

    /** Lists workspace and published files for the YAML diff card; never reconciles. */
    public MdlPreview preview(String groupId) {
        DatasetGroupEntity group = requireGroup(groupId);
        List<MdlFile> files = reader.listFiles(groupId);
        List<MdlFile> published = readPublishedFiles(groupId);
        return new MdlPreview(
                files,
                published,
                List.of(),
                group.getMdlState(),
                group.getMdlVersion(),
                group.getMdlPublishedAt() == null ? null : group.getMdlPublishedAt().toString(),
                !sameFiles(files, published));
    }

    /** Renders the workspace as a structured view; never touches the staging directory. */
    public MdlView view(String groupId) {
        DatasetGroupEntity group = requireGroup(groupId);
        MdlWorkspaceReader.Snapshot snapshot = reader.read(groupId);
        List<MdlModelView> models = new ArrayList<>();
        List<MdlModelView> derivedModels = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceModel m : snapshot.models()) {
            List<MdlColumnView> cols = new ArrayList<>();
            for (MdlWorkspaceReader.WorkspaceColumn c : m.columns()) {
                cols.add(
                        new MdlColumnView(
                                c.name(),
                                c.type(),
                                c.description(),
                                c.relationship() != null,
                                c.calculated(),
                                c.expression()));
            }
            MdlModelView mv =
                    new MdlModelView(
                            m.datasetId(),
                            m.datasetName(),
                            m.tableName(),
                            m.name(),
                            m.description(),
                            cols,
                            m.path(),
                            m.refSqlPath());
            // Derived (ref_sql) models get their own tab — the schema tab stays physical-only.
            if (m.refSql() != null) {
                derivedModels.add(mv);
            } else {
                models.add(mv);
            }
        }
        List<MdlRelationView> relations = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceRelation r : snapshot.relations()) {
            relations.add(
                    new MdlRelationView(
                            r.name(),
                            r.leftModel(),
                            r.rightModel(),
                            r.joinType(),
                            r.condition(),
                            r.sourceColumns(),
                            r.targetColumns()));
        }
        List<MdlCubeView> cubes = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceCube c : snapshot.cubes()) {
            cubes.add(
                    new MdlCubeView(
                            c.name(),
                            c.baseModel(),
                            c.description(),
                            toMemberViews(c.measures()),
                            toMemberViews(c.dimensions()),
                            toMemberViews(c.timeDimensions()),
                            c.path()));
        }
        List<MdlViewSummary> views = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceView v : snapshot.views()) {
            views.add(new MdlViewSummary(v.name(), v.sql(), v.description(), v.path()));
        }
        boolean changed = !sameFiles(snapshot.files(), readPublishedFiles(groupId));
        return new MdlView(
                group.getMdlState(),
                group.getMdlVersion(),
                group.getMdlPublishedAt() == null ? null : group.getMdlPublishedAt().toString(),
                changed,
                models,
                relations,
                cubes,
                views,
                snapshot.issues(),
                derivedModels);
    }

    private static List<MdlCubeMemberView> toMemberViews(
            List<MdlWorkspaceReader.WorkspaceMember> members) {
        List<MdlCubeMemberView> out = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceMember m : members) {
            out.add(new MdlCubeMemberView(m.name(), m.expression(), m.type(), m.description()));
        }
        return out;
    }

    /**
     * Runs the full publish chain minus the snapshot swap: reconcile, staging copy, {@code
     * validate --strict}, {@code build}, ref_sql materialisation, per-view/per-derived-model
     * dry-run and per-cube {@code cube query --sql-only}. Local and wren issues are returned
     * without throwing.
     */
    public MdlValidation validate(String groupId) {
        return withPublishLock(
                groupId,
                () ->
                        workspace.withWorkspaceLock(
                                groupId,
                                () -> {
                                    MdlValidation result = validateInternal(groupId);
                                    new ModelingWorkflowStore(workspace, questionStore)
                                            .record(groupId, result);
                                    return result;
                                }));
    }

    private MdlValidation validateInternal(String groupId) {
        return withPublishLock(
                groupId,
                () -> {
                    List<MdlIssue> issues = new ArrayList<>();
                    if (datasetRepository.findByGroupId(groupId).isEmpty()) {
                        issues.add(issue("error", "知识库内没有数据集，无法生成 MDL", "local"));
                        return new MdlValidation(false, issues, "", List.of());
                    }
                    MdlSeeder.SeedResult seed = seeder.reconcile(groupId);
                    issues.addAll(seed.issues());
                    if (hasErrors(issues)) {
                        return new MdlValidation(false, issues, "", reader.listFiles(groupId));
                    }
                    MdlWorkspaceReader.Snapshot snapshot = reader.read(groupId);
                    issues.addAll(snapshot.issues());
                    if (hasErrors(issues)) {
                        return new MdlValidation(false, issues, "", snapshot.files());
                    }
                    Path projectDir = workspace.copyToStaging(groupId);
                    WrenCli.Result result =
                            wrenCli.run(
                                    projectDir,
                                    props.timeout(),
                                    List.of("context", "validate", "--strict"));
                    issues.addAll(parseWrenOutput(result));
                    if (hasErrors(issues)) {
                        return new MdlValidation(false, issues, result.output(), snapshot.files());
                    }
                    WrenCli.Result build =
                            wrenCli.run(projectDir, props.timeout(), List.of("context", "build"));
                    if (!build.ok()) {
                        issues.addAll(parseWrenOutput(build));
                        if (!hasErrors(issues)) {
                            issues.add(
                                    issue(
                                            "error",
                                            "wren build 失败：" + firstLine(build.output()),
                                            "wren"));
                        }
                        return new MdlValidation(false, issues, build.output(), snapshot.files());
                    }
                    issues.addAll(materializeDerivedModels(projectDir));
                    issues.addAll(dryRunViews(projectDir, snapshot.views()));
                    issues.addAll(dryRunDerivedModels(projectDir, derivedModelsOf(snapshot)));
                    issues.addAll(dryRunCubes(projectDir, snapshot.cubes()));
                    return new MdlValidation(
                            !hasErrors(issues), issues, result.output(), snapshot.files());
                });
    }

    /** Runs the action while holding the group's publish mutex (see {@link #publishLocks}). */
    private <T> T withPublishLock(String groupId, Supplier<T> action) {
        ReentrantLock lock = publishLocks.computeIfAbsent(groupId, key -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Publishes the workspace as it stands (explicit modeling-page variant, never coalesced).
     * Serialised per group: a later run reconciles from the then-current datasets, so racing
     * uploads converge instead of failing on a wiped staging directory.
     */
    public MdlPublishResult publish(String groupId) {
        return withPublishLock(
                groupId,
                () -> {
                    DatasetGroupEntity group = requireGroup(groupId);
                    try {
                        return doPublish(group, groupId);
                    } catch (RuntimeException e) {
                        markFailed(group, List.of(issue("error", safeMessage(e), "local")), "");
                        throw e;
                    }
                });
    }

    /** Executes a requirement against a fresh draft without replacing any published artifact. */
    public MdlQuestionStore.Review validateQuestion(
            String groupId,
            String questionId,
            io.agentscope.dataagent.runtime.wren.WrenQueryGateway gateway) {
        return withPublishLock(
                groupId,
                () ->
                        workspace.withWorkspaceLock(
                                groupId,
                                () -> {
                                    MdlSeeder.SeedResult seed = seeder.reconcile(groupId);
                                    MdlQuestionStore.Question question =
                                            questionStore.requireQuestion(groupId, questionId);
                                    questionStore.requireCoverage(groupId, question);
                                    if (question.sql().isBlank()
                                            || question.definition().isBlank()) {
                                        throw new DatasetException("请先通过建模对话完善业务口径和逻辑 SQL", 400);
                                    }
                                    MdlQuestionStore.requireReadSql(question.sql());
                                    String hash =
                                            questionStore.modelHash(
                                                    workspace.workspaceRoot(groupId));
                                    com.fasterxml.jackson.databind.JsonNode result = null;
                                    String error = null;
                                    boolean truncated = false;
                                    try {
                                        if (hasErrors(seed.issues()))
                                            throw new DatasetException("模型播种存在问题，请先修复");
                                        WrenSourceSelection selection =
                                                selectWrenSource(
                                                        datasetRepository.findByGroupId(groupId));
                                        if (selection.error() != null)
                                            throw new DatasetException(selection.error());
                                        Path scratch = workspace.copyToScratch(groupId);
                                        WrenCli.Result validation =
                                                wrenCli.run(
                                                        scratch,
                                                        props.timeout(),
                                                        List.of("context", "validate", "--strict"));
                                        if (!validation.ok())
                                            throw new DatasetException(validation.output());
                                        WrenCli.Result build =
                                                wrenCli.run(
                                                        scratch,
                                                        props.timeout(),
                                                        List.of("context", "build"));
                                        if (!build.ok()) throw new DatasetException(build.output());
                                        List<MdlIssue> materialized =
                                                materializeDerivedModels(scratch);
                                        if (hasErrors(materialized))
                                            throw new DatasetException(materialized.toString());
                                        var call =
                                                gateway.callDraft(
                                                        groupId,
                                                        scratch,
                                                        selection.profile(),
                                                        question.sql(),
                                                        1000);
                                        if (!call.ok()) throw new DatasetException(call.payload());
                                        result = mapper.readTree(call.payload());
                                        if (result != null && result.isTextual())
                                            result = mapper.readTree(result.asText());
                                        if (result == null
                                                || !result.path("columns").isArray()
                                                || !result.path("rows").isArray()) {
                                            throw new DatasetException("Wren 未返回结构化查询结果，不能保存为成功验证");
                                        }
                                        truncated =
                                                result.path("truncated").asBoolean(false)
                                                        || result.path("rows").size() >= 1000;
                                    } catch (IOException | RuntimeException e) {
                                        error =
                                                e.getMessage() == null
                                                        ? e.getClass().getSimpleName()
                                                        : e.getMessage();
                                    } finally {
                                        workspace.deleteScratchQuietly(groupId);
                                    }
                                    MdlQuestionStore.Receipt receipt =
                                            questionStore.saveExecution(
                                                    groupId,
                                                    question,
                                                    hash,
                                                    result,
                                                    error,
                                                    truncated,
                                                    new MdlQuestionStore.Evidence(
                                                            question.sql(),
                                                            reader.read(groupId).views()));
                                    return questionStore.review(
                                            groupId, question, receipt.status(), receipt);
                                }));
    }

    /**
     * Baseline variant used after dataset changes. Identical chain to {@link #publish(String)}
     * since the workspace is the single source of truth; only the first-ever publish pre-sets
     * the INITIALIZING state so the upload funnel can show progress.
     */
    public MdlPublishResult publishBaseline(String groupId) {
        return withPublishLock(
                groupId,
                () -> {
                    DatasetGroupEntity group = requireGroup(groupId);
                    if (group.getMdlVersion() == 0) {
                        group.setMdlState("INITIALIZING");
                        group.setMdlLastError(null);
                        group.setUpdatedAt(Instant.now());
                        groupRepository.save(group);
                    }
                    try {
                        return doPublish(group, groupId);
                    } catch (RuntimeException e) {
                        markFailed(group, List.of(issue("error", safeMessage(e), "local")), "");
                        throw e;
                    }
                });
    }

    /**
     * Coalescing variant of {@link #publishBaseline(String)} for the automatic funnel
     * (specs/015). {@code requestTick} is taken when the request enters {@code
     * BaselineMdlService}, strictly after its dataset change committed. A publish that began
     * reconciling after that tick necessarily read a database state containing the change, so
     * once such a publish has succeeded, this queued request skips its run. Failed runs record
     * no coverage, so the next queued request still republishes. Single-instance only, like the
     * file-based staging it guards. Reentrant with {@link #publishBaseline(String)} via the
     * per-group {@link ReentrantLock}.
     */
    public MdlPublishResult publishBaseline(String groupId, long requestTick) {
        return withPublishLock(
                groupId,
                () -> {
                    Long covered = assembledAtTicks.get(groupId);
                    if (covered != null && covered >= requestTick) {
                        log.debug(
                                "Baseline publish for group {} skipped: a publish that began"
                                        + " reconciling at tick {} already observed the change",
                                groupId,
                                covered);
                        DatasetGroupEntity group = requireGroup(groupId);
                        return new MdlPublishResult(
                                true,
                                List.of(),
                                "",
                                group.getMdlState(),
                                group.getMdlVersion(),
                                group.getMdlPublishedAt() == null
                                        ? null
                                        : group.getMdlPublishedAt().toString());
                    }
                    return publishBaseline(groupId);
                });
    }

    /**
     * Records that the current group artifacts (published snapshot or the empty-reset NONE
     * state) reflect every change committed up to now, covering earlier funnel requests. Used
     * by the empty-group reset path, which converges the state without a publish run.
     */
    public void recordBaselineCoverage(String groupId) {
        assembledAtTicks.merge(groupId, System.nanoTime(), Math::max);
    }

    /**
     * Validates, builds and snapshots the workspace. Failures preserve the previous published
     * snapshot while recording the lifecycle state and user-facing reason.
     *
     * <p>specs/010 M4: before touching the CLI the group's wren connection is resolved ({@link
     * #selectWrenSource}) — all-uploaded groups keep the default dataset-store profile, a group
     * whose datasets all come from one MySQL external datasource binds its {@code ext-<id>}
     * profile (connectivity probed up front, missing tables fail the publish with their names).
     * The chosen profile is pinned into the snapshot as {@code published/wren-source.properties}
     * for {@code WrenInstanceRegistry} to pick up; mixed or multi-source groups are rejected
     * because one wren project can only bind a single connection.
     */
    private MdlPublishResult doPublish(DatasetGroupEntity group, String groupId) {
        return workspace.withWorkspaceLock(groupId, () -> doPublishLocked(group, groupId));
    }

    private MdlPublishResult doPublishLocked(DatasetGroupEntity group, String groupId) {
        long assembleStartTick = System.nanoTime();
        List<MdlIssue> issues = new ArrayList<>();
        List<DatasetEntity> datasets = datasetRepository.findByGroupId(groupId);
        if (datasets.isEmpty()) {
            issues.add(issue("error", "知识库内没有数据集，无法发布", "local"));
            return failure(group, issues, "");
        }
        // Workspace reconciliation (specs/019 M1): seed new datasets, append missing physical
        // columns, remove deleted ones, report agent-introduced conflicts. Holds the workspace
        // lock nested inside the publish lock — the only nesting direction.
        MdlSeeder.SeedResult seed = seeder.reconcile(groupId);
        issues.addAll(seed.issues());
        // Optional questions do not gate publication; engineering validation still applies.
        if (hasErrors(issues)) {
            return failure(group, issues, "");
        }
        WrenSourceSelection selection = selectWrenSource(datasets);
        if (selection.error() != null) {
            issues.add(issue("error", selection.error(), "local"));
            return failure(group, issues, "");
        }
        if (selection.external() != null) {
            WrenSourceProber.ProbeReport report =
                    sourceProber.probe(selection.external(), datasets);
            if (!report.ok()) {
                issues.add(issue("error", report.message(), "local"));
                return failure(group, issues, "");
            }
        }
        // Re-render profiles.yml from the database before publishing so the pinned profile
        // entry is guaranteed to exist for the next spawn (idempotent full rebuild, ADR 0021).
        profileHome.ensureAll(externalSources.findAll());

        MdlWorkspaceReader.Snapshot snapshot = reader.read(groupId);
        issues.addAll(snapshot.issues());
        if (hasErrors(issues)) {
            return failure(group, issues, "");
        }

        Path projectDir = workspace.copyToStaging(groupId);

        WrenCli.Result validation =
                wrenCli.run(
                        projectDir, props.timeout(), List.of("context", "validate", "--strict"));
        issues.addAll(parseWrenOutput(validation));
        if (hasErrors(issues)) {
            return failure(group, issues, validation.output());
        }

        WrenCli.Result build =
                wrenCli.run(projectDir, props.timeout(), List.of("context", "build"));
        if (!build.ok()) {
            issues.addAll(parseWrenOutput(build));
            if (!hasErrors(issues)) {
                issues.add(issue("error", "wren build 失败：" + firstLine(build.output()), "wren"));
            }
            return failure(group, issues, build.output());
        }

        // specs/035 / ADR 0043: a ref_sql body is expanded verbatim by the engine (no logical
        // name resolution), so the staging manifest is physically qualified first; unresolved
        // references block the publish with a precise local error.
        issues.addAll(materializeDerivedModels(projectDir));
        // Same gate as validate(): a view that cannot survive its own dry-run must never be
        // snapshotted — published views are force-routed to by the query guard (specs/018).
        issues.addAll(dryRunViews(projectDir, snapshot.views()));
        // Derived models must equally survive their own dry-run against the materialised
        // manifest before the snapshot.
        issues.addAll(dryRunDerivedModels(projectDir, derivedModelsOf(snapshot)));
        // ADR 0033 D6: every cube must survive its own SQL transformation before the snapshot.
        issues.addAll(dryRunCubes(projectDir, snapshot.cubes()));
        if (hasErrors(issues)) {
            return failure(group, issues, build.output());
        }

        Path groupRoot = props.groupRoot(groupId);
        Path preparedDir = groupRoot.resolve("published.next");
        Path preparedManifest = groupRoot.resolve("mdl.json.next");
        prepareSnapshot(projectDir, preparedDir);
        questionStore.filterPublishedExamples(groupId, preparedDir);
        writeWrenSourceProperties(preparedDir, selection.profile());
        writeMetaJson(preparedManifest, group, snapshot);
        replacePublishedArtifacts(groupRoot, preparedDir, preparedManifest);
        markPublished(group);
        // From here the snapshot reflects every change committed before this run began; automatic
        // baseline requests that entered before this tick can skip their own run.
        assembledAtTicks.merge(groupId, assembleStartTick, Math::max);
        return new MdlPublishResult(
                true,
                issues,
                build.output(),
                group.getMdlState(),
                group.getMdlVersion(),
                group.getMdlPublishedAt() == null ? null : group.getMdlPublishedAt().toString());
    }

    /** Removes the group's MDL artifacts (workspace included); used when a KB is deleted. */
    public void deleteArtifacts(String groupId) {
        Path root = props.groupRoot(groupId);
        try {
            deleteTree(root);
        } catch (IOException e) {
            log.warn(
                    "MdlPublishService: could not delete MDL artifacts for group {}: {}",
                    groupId,
                    e.getMessage());
        }
    }

    // ------------------------------------------------------------- wren source selection (M4)

    /** Which wren connection a group binds to: {@code null} error means "refuse to publish". */
    private record WrenSourceSelection(
            String profile, ExternalDataSourceEntity external, String error) {

        static WrenSourceSelection builtin(String profile) {
            return new WrenSourceSelection(profile, null, null);
        }

        static WrenSourceSelection externalProfile(
                String profile, ExternalDataSourceEntity source) {
            return new WrenSourceSelection(profile, source, null);
        }

        static WrenSourceSelection refused(String message) {
            return new WrenSourceSelection(null, null, message);
        }
    }

    /**
     * Resolves the wren connection profile for a group (specs/010 M4, ADR 0021): all-uploaded →
     * the default dataset-store profile; every dataset from one live MySQL external source →
     * {@code ext-<id>}; everything else (mixed upload+external, multiple sources, deleted
     * source, non-MySQL kind) is refused — cross-instance JOIN is physically impossible and a
     * silent broken snapshot is worse than a loud refusal.
     */
    private WrenSourceSelection selectWrenSource(List<DatasetEntity> datasets) {
        boolean anyUpload = false;
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        for (DatasetEntity d : datasets) {
            if (!"datasource".equals(d.getOrigin())) {
                anyUpload = true;
                continue;
            }
            if (d.getExternalDataSourceId() == null) {
                return WrenSourceSelection.refused(
                        "数据集「" + d.getName() + "」来自外部数据源但未关联数据源，无法发布，请重新关联后再试");
            }
            sourceIds.add(d.getExternalDataSourceId());
        }
        if (sourceIds.size() > 1) {
            return WrenSourceSelection.refused(
                    "知识库的数据集来自多个外部数据源（"
                            + sourceLabels(sourceIds)
                            + "」），而 wren 工程一次只能绑定一个数据库连接，无法发布");
        }
        if (sourceIds.isEmpty()) {
            return WrenSourceSelection.builtin(props.profile());
        }
        String sourceId = sourceIds.iterator().next();
        ExternalDataSourceEntity source = externalSources.findById(sourceId).orElse(null);
        if (source == null) {
            return WrenSourceSelection.refused(
                    "数据集关联的外部数据源（" + sourceId + "）已被删除，无法发布，请移除相关数据集或重新配置数据源");
        }
        if (!"mysql".equalsIgnoreCase(source.getKind())) {
            return WrenSourceSelection.refused(
                    "外部数据源「"
                            + source.getName()
                            + "」类型为 "
                            + source.getKind()
                            + "，暂不支持发布到 wren（当前仅支持 MySQL 外部源）");
        }
        if (anyUpload) {
            return WrenSourceSelection.refused(
                    "知识库同时包含上传数据集与外部数据源数据集，两者不在同一数据库、无法 JOIN，" + "无法发布。请将它们拆分到不同知识库。");
        }
        return WrenSourceSelection.externalProfile(
                WrenProfileHome.externalProfileName(sourceId), source);
    }

    private String sourceLabels(LinkedHashSet<String> sourceIds) {
        List<String> labels = new ArrayList<>();
        for (String id : sourceIds) {
            labels.add(
                    externalSources.findById(id).map(ExternalDataSourceEntity::getName).orElse(id));
        }
        return String.join("、", labels);
    }

    /**
     * Pins the group's wren connection into the snapshot as a one-line properties file read by
     * {@code WrenInstanceRegistry#spawn}; pre-M4 snapshots have no marker and fall back to the
     * default profile.
     */
    private static void writeWrenSourceProperties(Path publishedDir, String profile) {
        try {
            Files.writeString(
                    publishedDir.resolve("wren-source.properties"),
                    "profile=" + profile + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("写入 wren-source.properties 失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------- ref_sql materialisation (M5)

    /**
     * Physically qualifies every derived (ref_sql) model in the freshly built {@code
     * target/mdl.json} manifest (specs/035, ADR 0043).
     *
     * <p>Empirical basis (official CLI 0.15.0, {@code target/wren-refsql-verify}): the engine
     * expands a ref_sql body verbatim and resolves bare names against the connection's default
     * database (MySQL error 1146), unlike view statements, for which it injects physical CTEs
     * per referenced model. The workspace keeps human-readable logical names; this method
     * rewrites the staging manifest in place after {@code context build}, and {@code
     * prepareSnapshot} carries the materialised manifest into the published snapshot the
     * runtime loads. The logical→physical map comes from the manifest's own physical models
     * ({@code tableReference}).
     *
     * <p>Unmapped references become error issues (unknown model, chained derived-model
     * reference or view reference); a model with unresolved references is left untouched so the
     * follow-up dry-run reports its own diagnosis, while other models are still materialised.
     * No-op when the manifest has no derived model.
     */
    private List<MdlIssue> materializeDerivedModels(Path projectDir) {
        List<MdlIssue> issues = new ArrayList<>();
        Path mdlJson = projectDir.resolve("target").resolve("mdl.json");
        if (!Files.isRegularFile(mdlJson)) {
            issues.add(issue("error", "派生模型物化失败：未找到构建产物 target/mdl.json", "local"));
            return issues;
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(mdlJson, StandardCharsets.UTF_8));
        } catch (IOException e) {
            issues.add(issue("error", "派生模型物化失败：无法解析 target/mdl.json：" + e.getMessage(), "local"));
            return issues;
        }
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            issues.add(issue("error", "派生模型物化失败：target/mdl.json 缺少 models 数组", "local"));
            return issues;
        }
        Map<String, String> physicalByLogical = new HashMap<>();
        Set<String> derivedNames = new HashSet<>();
        int derivedCount = 0;
        for (JsonNode model : models) {
            String name = model.path("name").asText("");
            if (model.hasNonNull("refSql")) {
                derivedCount++;
                derivedNames.add(name.toLowerCase(Locale.ROOT));
                continue;
            }
            JsonNode tableRef = model.path("tableReference");
            String physical =
                    RefSqlMaterializer.physicalName(
                            tableRef.path("schema").asText(null),
                            tableRef.path("table").asText(null));
            if (!name.isEmpty() && physical != null) {
                physicalByLogical.put(name, physical);
            }
        }
        if (derivedCount == 0) {
            return issues;
        }
        Set<String> viewNames = new HashSet<>();
        for (JsonNode view : root.path("views")) {
            viewNames.add(view.path("name").asText("").toLowerCase(Locale.ROOT));
        }
        boolean changed = false;
        for (JsonNode model : models) {
            if (!model.hasNonNull("refSql")) {
                continue;
            }
            String modelName = model.path("name").asText("");
            String refSql = model.get("refSql").asText("");
            List<String> unresolved = new ArrayList<>();
            for (String ref : RefSqlMaterializer.extractTableRefs(refSql)) {
                if (RefSqlMaterializer.resolve(physicalByLogical, ref) == null) {
                    unresolved.add(ref);
                }
            }
            for (String ref : unresolved) {
                issues.add(
                        issue(
                                "error",
                                "派生模型「"
                                        + modelName
                                        + "」的 SQL 引用了"
                                        + unresolvedHint(ref, derivedNames, viewNames),
                                "local"));
            }
            if (!unresolved.isEmpty()) {
                continue;
            }
            String materialized = RefSqlMaterializer.materialize(refSql, physicalByLogical);
            if (!materialized.equals(refSql)) {
                ((ObjectNode) model).put("refSql", materialized);
                changed = true;
            }
        }
        if (changed) {
            try {
                Files.writeString(
                        mdlJson,
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                issues.add(issue("error", "派生模型物化写回失败：" + e.getMessage(), "local"));
            }
        }
        return issues;
    }

    /** One unresolved bare reference of a derived model, classified for the error message. */
    private static String unresolvedHint(
            String ref, Set<String> derivedNames, Set<String> viewNames) {
        String lower = ref.toLowerCase(Locale.ROOT);
        if (derivedNames.contains(lower)) {
            return "其他派生模型「" + ref + "」；ref_sql 不支持链式引用派生模型，请将其 SQL 展开或改为引用物理模型";
        }
        if (viewNames.contains(lower)) {
            return "视图「" + ref + "」；ref_sql 内不支持引用视图，请改用视图承载该口径或直接书写物理表";
        }
        return "不存在的逻辑模型「" + ref + "」，请检查模型名或先在建模页定义";
    }

    // ------------------------------------------------------------------ dry-run gates

    /**
     * Dry-runs every named view against the freshly built {@code target/mdl.json} (specs/018,
     * ADR 0032 D2): {@code dry-run} is the only pre-publish step that plans view statements, so
     * it is the backstop for dialect errors the create/update blocklist cannot foresee. Each
     * failure becomes an error issue naming the view; the loop continues so one broken view does
     * not mask the others.
     */
    private List<MdlIssue> dryRunViews(
            Path projectDir, List<MdlWorkspaceReader.WorkspaceView> views) {
        List<MdlIssue> issues = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceView view : views) {
            String quoted = view.name().replace("\"", "\"\"");
            WrenCli.Result result =
                    wrenCli.run(
                            projectDir,
                            props.timeout(),
                            List.of("dry-run", "-s", "SELECT * FROM \"" + quoted + "\""));
            if (!result.ok()) {
                issues.add(
                        issue(
                                "error",
                                "视图「" + view.name() + "」试跑失败：" + firstLine(result.output()),
                                "wren"));
            }
        }
        return issues;
    }

    /**
     * Dry-runs every derived (ref_sql) model against the freshly built {@code target/mdl.json}
     * (specs/034, specs/035): the manifest was already materialised to physical names by {@link
     * #materializeDerivedModels}, so a failure here means a broken physical reference, unknown
     * column or unsupported dialect detail in the model's SQL. Same argv shape as {@link
     * #dryRunViews}; each failure becomes an error issue naming the model and the loop continues
     * so one broken model does not mask the others.
     */
    private List<MdlIssue> dryRunDerivedModels(
            Path projectDir, List<MdlWorkspaceReader.WorkspaceModel> derivedModels) {
        List<MdlIssue> issues = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceModel model : derivedModels) {
            String quoted = model.name().replace("\"", "\"\"");
            WrenCli.Result result =
                    wrenCli.run(
                            projectDir,
                            props.timeout(),
                            List.of("dry-run", "-s", "SELECT * FROM \"" + quoted + "\""));
            if (!result.ok()) {
                issues.add(
                        issue(
                                "error",
                                "派生模型「" + model.name() + "」试跑失败：" + firstLine(result.output()),
                                "wren"));
            }
        }
        return issues;
    }

    /** Derived models of the snapshot: those whose model is defined by SQL, not a table. */
    private static List<MdlWorkspaceReader.WorkspaceModel> derivedModelsOf(
            MdlWorkspaceReader.Snapshot snapshot) {
        List<MdlWorkspaceReader.WorkspaceModel> derived = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceModel m : snapshot.models()) {
            if (m.refSql() != null) {
                derived.add(m);
            }
        }
        return derived;
    }

    /**
     * Translates every cube's full measure set through wren-core with {@code cube query
     * --sql-only} (ADR 0033 D6): pure SQL transformation, no database connection. Each failure
     * becomes an error issue naming the cube; the loop continues so one broken cube does not
     * mask the others. Cubes without measures are structurally covered by {@code validate
     * --strict} and skipped here ({@code cube query} requires at least one measure).
     */
    private List<MdlIssue> dryRunCubes(
            Path projectDir, List<MdlWorkspaceReader.WorkspaceCube> cubes) {
        List<MdlIssue> issues = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceCube cube : cubes) {
            if (cube.measures().isEmpty()) {
                continue;
            }
            String measures =
                    cube.measures().stream()
                            .map(MdlWorkspaceReader.WorkspaceMember::name)
                            .reduce((a, b) -> a + "," + b)
                            .orElse("");
            WrenCli.Result result =
                    wrenCli.run(
                            projectDir,
                            props.timeout(),
                            List.of(
                                    "cube",
                                    "query",
                                    "--cube",
                                    cube.name(),
                                    "--measures",
                                    measures,
                                    "--sql-only"));
            if (!result.ok()) {
                issues.add(
                        issue(
                                "error",
                                "Cube「" + cube.name() + "」试跑失败：" + firstLine(result.output()),
                                "wren"));
            }
        }
        return issues;
    }

    // ------------------------------------------------------------- wren CLI interaction

    /** Parses wren's stdout into structured issues; "0 errors" gates acceptance of rc!=0 runs. */
    private List<MdlIssue> parseWrenOutput(WrenCli.Result result) {
        List<MdlIssue> issues = new ArrayList<>();
        int warnings = 0;
        int errors = 0;
        for (String line : result.output().split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            Matcher summary = SUMMARY.matcher(t);
            if (summary.find()) {
                warnings = Math.max(warnings, Integer.parseInt(summary.group(1)));
                errors = Math.max(errors, Integer.parseInt(summary.group(2)));
                continue;
            }
            if (t.startsWith("[ERROR]")) {
                errors++;
                issues.add(issue("error", t.substring("[ERROR]".length()).strip(), "wren"));
            } else if (t.startsWith("Error:")) {
                errors++;
                issues.add(issue("error", t.substring("Error:".length()).strip(), "wren"));
            } else if (t.startsWith("⚠")) {
                warnings++;
                issues.add(issue("warning", t.substring(1).strip(), "wren"));
            }
        }
        if (errors == 0 && !result.ok() && warnings == 0) {
            // A non-zero exit without any parseable diagnostics: surface the raw output so the
            // caller never mistakes a broken CLI (missing executable, crash) for success.
            issues.add(issue("error", "wren 执行失败：" + firstLine(result.output()), "wren"));
        }
        return issues;
    }

    // ------------------------------------------------------------------ file system

    /**
     * Lists the published snapshot's files for the diff card, excluding build output and the
     * runtime profile marker (neither exists in the workspace, so they would always look
     * "changed").
     */
    private List<MdlFile> readPublishedFiles(String groupId) {
        Path published = props.groupRoot(groupId).resolve("published");
        if (!Files.isDirectory(published)) {
            return List.of();
        }
        List<MdlFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(published)) {
            List<Path> files =
                    walk.filter(Files::isRegularFile)
                            .filter(p -> !p.startsWith(published.resolve("target")))
                            .filter(
                                    p ->
                                            !"wren-source.properties"
                                                    .equals(p.getFileName().toString()))
                            .sorted()
                            .toList();
            for (Path p : files) {
                String rel = published.relativize(p).toString().replace('\\', '/');
                out.add(new MdlFile(rel, Files.readString(p, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            log.warn(
                    "MdlPublishService: could not read published snapshot for group {}: {}",
                    groupId,
                    e.getMessage());
            return List.of();
        }
        return out;
    }

    private void prepareSnapshot(Path projectDir, Path preparedDir) {
        try {
            deleteTree(preparedDir);
            Files.createDirectories(preparedDir);
            try (Stream<Path> walk = Files.walk(projectDir)) {
                for (Path path : walk.toList()) {
                    Path destination = preparedDir.resolve(projectDir.relativize(path));
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.copy(path, destination);
                    }
                }
            }
        } catch (IOException e) {
            throw new DatasetException("发布快照准备失败：" + e.getMessage(), e);
        }
    }

    /** Swaps the snapshot and manifest together, restoring the previous publication on failure. */
    private void replacePublishedArtifacts(
            Path groupRoot, Path preparedDir, Path preparedManifest) {
        Path publishedDir = groupRoot.resolve("published");
        Path manifest = groupRoot.resolve("mdl.json");
        Path publishedBackup = groupRoot.resolve("published.previous");
        Path manifestBackup = groupRoot.resolve("mdl.json.previous");
        boolean installedPublished = false;
        boolean installedManifest = false;
        try {
            deleteTree(publishedBackup);
            Files.deleteIfExists(manifestBackup);
            if (Files.exists(publishedDir)) {
                moveReplacing(publishedDir, publishedBackup);
            }
            if (Files.exists(manifest)) {
                moveReplacing(manifest, manifestBackup);
            }
            moveReplacing(preparedDir, publishedDir);
            installedPublished = true;
            moveReplacing(preparedManifest, manifest);
            installedManifest = true;
            deleteTree(publishedBackup);
            Files.deleteIfExists(manifestBackup);
        } catch (IOException e) {
            try {
                if (installedPublished) {
                    deleteTree(publishedDir);
                }
                if (installedManifest) {
                    Files.deleteIfExists(manifest);
                }
                if (Files.exists(publishedBackup)) {
                    moveReplacing(publishedBackup, publishedDir);
                }
                if (Files.exists(manifestBackup)) {
                    moveReplacing(manifestBackup, manifest);
                }
            } catch (IOException restoreError) {
                e.addSuppressed(restoreError);
            }
            throw new DatasetException("发布快照替换失败：" + e.getMessage(), e);
        } finally {
            try {
                deleteTree(preparedDir);
                Files.deleteIfExists(preparedManifest);
            } catch (IOException cleanupError) {
                log.warn(
                        "MdlPublishService: could not clean prepared publication: {}",
                        cleanupError.getMessage());
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Renders the platform manifest from the workspace snapshot. Shape identical to v1 (read by
     * {@code MdlCatalog} for the prompt middleware and {@code wren_describe_model}): models with
     * dataset ids from the platform mapping, relations with the parsed column pairs, cubes and
     * views with their members.
     */
    private void writeMetaJson(
            Path manifestFile, DatasetGroupEntity group, MdlWorkspaceReader.Snapshot snapshot) {
        ObjectNode root = mapper.createObjectNode();
        root.put("groupId", group.getId());
        root.put("groupName", group.getName());
        root.put("version", group.getMdlVersion() + 1);
        root.put("publishedAt", Instant.now().toString());
        root.put("dataSource", props.dataSource());
        Map<String, String> datasetByModel = new HashMap<>();
        for (MdlWorkspaceReader.WorkspaceModel m : snapshot.models()) {
            if (m.datasetId() != null) {
                datasetByModel.put(m.name(), m.datasetId());
            }
        }
        ArrayNode models = root.putArray("models");
        for (MdlWorkspaceReader.WorkspaceModel m : snapshot.models()) {
            ObjectNode node = models.addObject();
            node.put("name", m.name());
            node.put("datasetId", m.datasetId() == null ? "" : m.datasetId());
            node.put("datasetName", m.datasetName());
            if (m.refSql() != null) {
                node.put("derived", true);
            }
            if (m.description() != null) {
                node.put("description", m.description());
            }
            ArrayNode columns = node.putArray("columns");
            for (MdlWorkspaceReader.WorkspaceColumn c : m.columns()) {
                ObjectNode col = columns.addObject();
                col.put("name", c.name());
                col.put("type", c.type());
                if (c.relationship() != null) {
                    col.put("relationship", c.relationship());
                }
                if (c.calculated()) {
                    col.put("calculated", true);
                    col.put("expression", c.expression());
                }
                if (c.description() != null) {
                    col.put("description", c.description());
                }
            }
        }
        ArrayNode relations = root.putArray("relations");
        for (MdlWorkspaceReader.WorkspaceRelation r : snapshot.relations()) {
            ObjectNode node = relations.addObject();
            node.put("name", r.name());
            node.put("leftModel", r.leftModel());
            node.put("rightModel", r.rightModel());
            node.put("joinType", r.joinType());
            node.put("condition", r.condition());
            node.put("sourceDatasetId", datasetByModel.getOrDefault(r.leftModel(), ""));
            node.put("targetDatasetId", datasetByModel.getOrDefault(r.rightModel(), ""));
            node.put("sourceColumn", r.sourceColumns().isEmpty() ? "" : r.sourceColumns().get(0));
            node.put("targetColumn", r.targetColumns().isEmpty() ? "" : r.targetColumns().get(0));
            node.set("sourceColumns", mapper.valueToTree(r.sourceColumns()));
            node.set("targetColumns", mapper.valueToTree(r.targetColumns()));
        }
        ArrayNode cubes = root.putArray("cubes");
        for (MdlWorkspaceReader.WorkspaceCube c : snapshot.cubes()) {
            ObjectNode node = cubes.addObject();
            node.put("name", c.name());
            node.put("baseModel", c.baseModel());
            if (c.description() != null) {
                node.put("description", c.description());
            }
            appendMetaMembers(node.putArray("measures"), c.measures());
            appendMetaMembers(node.putArray("dimensions"), c.dimensions());
            appendMetaMembers(node.putArray("timeDimensions"), c.timeDimensions());
        }
        ArrayNode views = root.putArray("views");
        for (MdlWorkspaceReader.WorkspaceView v : snapshot.views()) {
            ObjectNode node = views.addObject();
            node.put("name", v.name());
            node.put("sql", v.sql());
            if (v.description() != null) {
                node.put("description", v.description());
            }
        }
        try {
            Files.createDirectories(manifestFile.getParent());
            Files.writeString(
                    manifestFile,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("写入 mdl.json 失败：" + e.getMessage(), e);
        }
    }

    private static void appendMetaMembers(
            ArrayNode array, List<MdlWorkspaceReader.WorkspaceMember> members) {
        for (MdlWorkspaceReader.WorkspaceMember m : members) {
            ObjectNode node = array.addObject();
            node.put("name", m.name());
            node.put("expression", m.expression());
            node.put("type", m.type());
            if (m.description() != null) {
                node.put("description", m.description());
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private DatasetGroupEntity requireGroup(String groupId) {
        return groupRepository
                .findById(groupId)
                .orElseThrow(
                        () -> new DatasetException("Knowledge base not found: " + groupId, 404));
    }

    /**
     * Workspace-as-truth state machine (ADR 0033 D3): a successful publish simply bumps the
     * version; there is no draft tracking left.
     */
    private void markPublished(DatasetGroupEntity group) {
        group.setMdlState("PUBLISHED");
        group.setMdlVersion(group.getMdlVersion() + 1);
        group.setMdlPublishedAt(Instant.now());
        group.setMdlLastError(null);
        group.setUpdatedAt(Instant.now());
        groupRepository.save(group);
    }

    private MdlPublishResult failure(
            DatasetGroupEntity group, List<MdlIssue> issues, String output) {
        markFailed(group, issues, output);
        return new MdlPublishResult(
                false,
                issues,
                output,
                group.getMdlState(),
                group.getMdlVersion(),
                group.getMdlPublishedAt() == null ? null : group.getMdlPublishedAt().toString());
    }

    /**
     * Failure bookkeeping under the workspace-as-truth model: with a published snapshot in
     * place the group keeps serving it (state stays PUBLISHED, the error is recorded), only a
     * never-published group shows FAILED.
     */
    private void markFailed(DatasetGroupEntity group, List<MdlIssue> issues, String output) {
        group.setMdlState(group.getMdlVersion() > 0 ? "PUBLISHED" : "FAILED");
        group.setMdlLastError(failureSummary(issues, output));
        group.setUpdatedAt(Instant.now());
        groupRepository.save(group);
    }

    private static String failureSummary(List<MdlIssue> issues, String output) {
        String message =
                issues.stream()
                        .filter(issue -> "error".equals(issue.severity()))
                        .map(MdlIssue::message)
                        .filter(value -> value != null && !value.isBlank())
                        .findFirst()
                        .orElseGet(() -> firstLine(output));
        if (message == null || message.isBlank()) {
            message = "MDL 构建失败";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static MdlIssue issue(String severity, String message, String source) {
        return new MdlIssue(severity, message, source);
    }

    private static boolean hasErrors(List<MdlIssue> issues) {
        return issues.stream().anyMatch(i -> "error".equals(i.severity()));
    }

    private static boolean sameFiles(List<MdlFile> a, List<MdlFile> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, String> byPath = new HashMap<>();
        for (MdlFile f : b) {
            byPath.put(f.path(), f.content());
        }
        for (MdlFile f : a) {
            String other = byPath.get(f.path());
            if (other == null || !other.equals(f.content())) {
                return false;
            }
        }
        return true;
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "(无输出)";
        }
        String t = output.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl);
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    /**
     * YAML plain scalar when provably safe, single-quoted (with '' escaping) otherwise. Public
     * for the modeling toolkit, which composes model metadata files outside this package.
     */
    public static String yamlScalar(String value) {
        String v = value == null ? "" : value.replace("\r", " ").replace("\n", " ").strip();
        if (plainSafe(v)) {
            return v;
        }
        return "'" + v.replace("'", "''") + "'";
    }

    private static boolean plainSafe(String v) {
        if (v.isEmpty() || !PLAIN_SAFE_START.matcher(v).find()) {
            return false;
        }
        char first = v.charAt(0);
        if (YAML_INDICATORS.indexOf(first) >= 0) {
            return false;
        }
        if (v.endsWith(" ") || v.endsWith(":")) {
            return false;
        }
        if (v.contains(": ") || v.contains(" #") || v.contains("\t")) {
            return false;
        }
        String lower = v.toLowerCase(java.util.Locale.ROOT);
        if (BOOL_LIKE.contains(lower) || NUMBER_LIKE.matcher(v).matches()) {
            return false;
        }
        return true;
    }

    /**
     * Keeps Chinese/ASCII identifiers, maps everything else to '_' (collapsed, trimmed). Public
     * for the modeling toolkit's derived-model name-conflict check.
     */
    public static String sanitizeIdentifier(String raw, int maxLength) {
        String s = raw == null ? "" : raw.strip();
        StringBuilder sb = new StringBuilder();
        boolean lastUnderscore = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean keep =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '_'
                            || (c >= '\u4e00' && c <= '\u9fff');
            char out = keep ? c : '_';
            if (out == '_') {
                if (lastUnderscore) {
                    continue;
                }
                lastUnderscore = true;
            } else {
                lastUnderscore = false;
            }
            sb.append(out);
        }
        String cleaned = trimUnderscores(sb.toString());
        if (cleaned.isEmpty()) {
            return "m";
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "m_" + cleaned;
        }
        if (cleaned.length() > maxLength) {
            cleaned = trimUnderscores(cleaned.substring(0, maxLength));
        }
        return cleaned.isEmpty() ? "m" : cleaned;
    }

    private static String trimUnderscores(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '_') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '_') {
            end--;
        }
        return s.substring(start, end);
    }
}
