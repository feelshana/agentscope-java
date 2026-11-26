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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.MdlTypes.CompiledMdl;
import io.agentscope.dataagent.dataset.MdlTypes.MdlIssue;
import io.agentscope.dataagent.dataset.MdlTypes.MdlPreview;
import io.agentscope.dataagent.dataset.MdlTypes.MdlPublishResult;
import io.agentscope.dataagent.dataset.MdlTypes.MdlValidation;
import io.agentscope.dataagent.dataset.MdlTypes.MdlView;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
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
    private KnowledgeBaseOperations operations;
    private KnowledgeBaseAccessService access;

    private static final Logger log = LoggerFactory.getLogger(MdlPublishService.class);

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
    private final MdlReadService readService;
    private final MdlBuildChecks buildChecks;
    private final MdlPublicationStore publicationStore;
    private org.springframework.transaction.PlatformTransactionManager publicationTransactions;
    private final MdlQuestionStore questionStore;
    private final org.springframework.context.ApplicationEventPublisher events;
    private AssetReviewStore assetReviews;

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
        this(
                datasetRepository,
                groupRepository,
                externalSources,
                mapper,
                props,
                wrenCli,
                profileHome,
                sourceProber,
                seeder,
                workspace,
                reader,
                event -> {});
    }

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
            MdlWorkspaceReader reader,
            org.springframework.context.ApplicationEventPublisher events) {
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
        this.readService = new MdlReadService(groupRepository, props, workspace, reader);
        this.buildChecks = new MdlBuildChecks(props, wrenCli, profileHome, mapper);
        this.publicationStore = new MdlPublicationStore(mapper);
        this.questionStore = new MdlQuestionStore(workspace);
        this.events = events;
    }

    private org.springframework.transaction.support.TransactionTemplate publicationTransaction() {
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        publicationTransactions);
        tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    private int committedVersion(String groupId) {
        if (publicationTransactions == null)
            return groupRepository
                    .findById(groupId)
                    .map(DatasetGroupEntity::getMdlVersion)
                    .orElse(-1);
        // A locking read waits for a server-side commit that may outlive a client connection error.
        return publicationTransaction()
                .execute(
                        status ->
                                groupRepository
                                        .findForPublicationRecovery(groupId)
                                        .map(DatasetGroupEntity::getMdlVersion)
                                        .orElse(-1));
    }

    private void commitPublication(DatasetGroupEntity group, long revision, Instant at) {
        int previous = group.getMdlVersion();
        if (publicationTransactions == null) {
            applyPublication(group, previous + 1, at);
            groupRepository.save(group);
            groupRepository.coverModelingRevision(group.getId(), revision);
        } else {
            publicationTransaction()
                    .executeWithoutResult(
                            status -> {
                                if (groupRepository.commitPublication(
                                                group.getId(), previous, previous + 1, at, revision)
                                        != 1) throw new DatasetException("发布版本已变化，请重新检查后再发布", 409);
                            });
        }
    }

    private static void applyPublication(DatasetGroupEntity group, int version, Instant at) {
        group.setMdlState("PUBLISHED");
        group.setMdlVersion(version);
        group.setMdlPublishedAt(at);
        group.setMdlLastError(null);
        group.setUpdatedAt(at);
    }

    @jakarta.annotation.PostConstruct
    void recoverPublications() {
        Path home = props.mdlRoot();
        if (!Files.isDirectory(home)) return;
        try (var directories = Files.list(home)) {
            for (var root : directories.filter(Files::isDirectory).toList()) {
                if (root.getFileName().toString().matches("[A-Za-z0-9_-]{1,64}")
                        && MdlPublicationStore.pending(root)) {
                    try {
                        publicationStore.recover(
                                root, committedVersion(root.getFileName().toString()));
                    } catch (RuntimeException failure) {
                        // Keep this group's query fence, without taking healthy groups offline.
                        log.error(
                                "Publication recovery pending: group={}, failure={}",
                                root.getFileName(),
                                failure.getClass().getSimpleName());
                    }
                }
            }
        } catch (IOException failure) {
            throw new DatasetException("无法检查发布恢复记录", failure);
        }
    }

    // ------------------------------------------------------------------ public API

    /** Lists workspace and published files for the YAML diff card; never reconciles. */
    public MdlPreview preview(String groupId) {
        return readService.preview(groupId);
    }

    /** Reads the current official compiled MDL texts without building either version. */
    public CompiledMdl compiled(String groupId) {
        return readService.compiled(groupId);
    }

    /** Renders the current structured workspace without entering publication. */
    public MdlView view(String groupId) {
        return readService.view(groupId);
    }

    /**
     * Runs the full publish chain minus the snapshot swap: reconcile, staging copy, {@code
     * validate --strict}, {@code build}, per-view/per-derived-model
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
                    List<DatasetEntity> datasets = datasetRepository.findByGroupId(groupId);
                    if (datasets.isEmpty()) {
                        issues.add(issue("error", "知识库内没有数据集，无法生成 MDL", "local"));
                        return new MdlValidation(false, issues, "", List.of());
                    }
                    WrenSourceSelection selection = selectWrenSource(datasets);
                    if (selection.error() != null) {
                        issues.add(issue("error", selection.error(), "local"));
                        return new MdlValidation(false, issues, "", reader.listFiles(groupId));
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
                    // spec149: check what would ship, with unreviewed caliber changes held back.
                    issues.addAll(
                            CaliberReviews.stage(
                                    workspace.workspaceRoot(groupId),
                                    projectDir,
                                    props.groupRoot(groupId).resolve("published")));
                    snapshot = shipped(snapshot, projectDir);
                    WrenCli.Result result =
                            wrenCli.run(
                                    projectDir,
                                    props.timeout(),
                                    List.of("context", "validate", "--strict"));
                    issues.addAll(buildChecks.parseWrenOutput(result));
                    if (hasErrors(issues)) {
                        return new MdlValidation(false, issues, result.output(), snapshot.files());
                    }
                    WrenCli.Result build =
                            wrenCli.run(projectDir, props.timeout(), List.of("context", "build"));
                    if (!build.ok()) {
                        issues.addAll(buildChecks.parseWrenOutput(build));
                        if (!hasErrors(issues)) {
                            issues.add(
                                    issue(
                                            "error",
                                            "wren build 失败：" + firstLine(build.output()),
                                            "wren"));
                        }
                        return new MdlValidation(false, issues, build.output(), snapshot.files());
                    }

                    issues.addAll(
                            buildChecks.dryRunSqlObjects(
                                    projectDir, snapshot, selection.external()));
                    issues.addAll(buildChecks.dryRunCubes(projectDir, snapshot.cubes()));
                    // spec149: generated caliber assets must be exactly what their lists produce.
                    issues.addAll(CaliberCompiler.check(projectDir));
                    return new MdlValidation(
                            !hasErrors(issues), issues, result.output(), snapshot.files());
                });
    }

    /** Lets a change that must land in the next snapshot finish before a publish can start. */
    public <T> T beforeNextPublish(String groupId, Supplier<T> action) {
        return withPublishLock(groupId, action);
    }

    /** Runs the action while holding the group's publish mutex (see {@link #publishLocks}). */
    private <T> T withPublishLock(String groupId, Supplier<T> action) {
        ReentrantLock lock = publishLocks.computeIfAbsent(groupId, key -> new ReentrantLock());
        lock.lock();
        try {
            if (operations != null) operations.requireActive(groupId);
            if (MdlPublicationStore.pending(props.groupRoot(groupId)))
                publicationStore.recover(props.groupRoot(groupId), committedVersion(groupId));
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
                        if (!MdlPublicationStore.pending(props.groupRoot(groupId)))
                            markFailed(group, List.of(issue("error", safeMessage(e), "local")), "");
                        throw e;
                    }
                });
    }

    /** Prepares the existing interactive workbench without changing published artifacts. */
    public MdlPublishResult prepareModeling(String groupId) {
        return withPublishLock(
                groupId,
                () -> {
                    DatasetGroupEntity group = requireGroup(groupId);
                    MdlSeeder.SeedResult result = seeder.reconcile(groupId);
                    if (hasErrors(result.issues())) return failure(group, result.issues(), "");
                    return new MdlPublishResult(
                            true,
                            result.issues(),
                            "采样及草稿准备完成，请进入语义建模并显式发布。",
                            "DIRTY",
                            group.getMdlVersion(),
                            group.getMdlPublishedAt() == null
                                    ? null
                                    : group.getMdlPublishedAt().toString());
                });
    }

    /** One SQL to compare between the draft and the published snapshot. */
    public record AssetCheck(String key, String sql) {}

    /** Raw engine replies; {@code published} is null when the snapshot cannot answer. */
    public record AssetRun(String key, String draft, String published, String error) {}

    /**
     * Runs each SQL on one scratch build of the draft and on the published snapshot (ADR0128).
     * Authorization is rechecked before every call, because each one can wait in a queue.
     */
    public List<AssetRun> verifyAssets(
            String groupId,
            List<AssetCheck> checks,
            io.agentscope.dataagent.runtime.wren.WrenQueryGateway gateway,
            Runnable authorization) {
        return withPublishLock(
                groupId,
                () ->
                        workspace.withWorkspaceLock(
                                groupId,
                                () -> {
                                    try {
                                        return runAssetChecks(
                                                groupId, checks, gateway, authorization);
                                    } finally {
                                        workspace.deleteScratchQuietly(groupId);
                                    }
                                }));
    }

    private List<AssetRun> runAssetChecks(
            String groupId,
            List<AssetCheck> checks,
            io.agentscope.dataagent.runtime.wren.WrenQueryGateway gateway,
            Runnable authorization) {
        MdlSeeder.SeedResult seed = seeder.reconcile(groupId);
        if (hasErrors(seed.issues())) throw new DatasetException("模型播种存在问题，请先修复");
        WrenSourceSelection selection = selectWrenSource(datasetRepository.findByGroupId(groupId));
        if (selection.error() != null) throw new DatasetException(selection.error());
        Path scratch = workspace.copyToScratch(groupId);
        for (List<String> step :
                List.of(List.of("context", "validate", "--strict"), List.of("context", "build"))) {
            WrenCli.Result result = wrenCli.run(scratch, props.timeout(), step);
            if (!result.ok()) throw new DatasetException(result.output());
        }
        boolean published = requireGroup(groupId).getMdlVersion() > 0;
        List<AssetRun> runs = new ArrayList<>();
        for (AssetCheck check : checks) {
            authorization.run();
            String error = null;
            String draft = null;
            String live = null;
            var call = gateway.callDraft(groupId, scratch, selection.profile(), check.sql(), 1000);
            if (call.ok()) draft = call.payload();
            else error = call.payload();
            if (published && error == null) {
                var online =
                        gateway.callAuthorized(
                                groupId,
                                "run_sql",
                                java.util.Map.of("sql", check.sql(), "limit", 1000),
                                authorization);
                // A new asset is unknown to the snapshot: the draft result stands alone.
                if (online.ok()) live = online.payload();
            }
            runs.add(new AssetRun(check.key(), draft, live, error));
        }
        return runs;
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
                        if (!MdlPublicationStore.pending(props.groupRoot(groupId)))
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
        // Read under the workspace lock: the group entity was loaded before waiting for it, and
        // a change committed meanwhile (e.g. a question confirmed during the wait) is part of
        // this snapshot, so it must also be covered by it.
        long coveredRevision = requireGroup(groupId).getModelingRevision();
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
        // ADR0128: changed Views and ref_sql models need a person's review and verification.
        if (assetReviews != null) issues.addAll(assetReviews.publishIssues(groupId));
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
        // spec149: a caliber list ships only when fully reviewed and verified, and an unfinished
        // one blocks the release (a table with a list is queried only through its calibers);
        // hand-written Cubes stay out of this version with a warning.
        issues.addAll(
                CaliberReviews.stage(
                        workspace.workspaceRoot(groupId),
                        projectDir,
                        props.groupRoot(groupId).resolve("published"),
                        true));
        // spec149 P7-2 (ADR0156): a caliber knowledge base answers every table from its list.
        // The draft is checked: staging holds unreviewed lists back, which the stage step already
        // reports, and a table with a draft list is not "without a list".
        if (group.isCaliberMode())
            issues.addAll(CaliberReviews.coverage(workspace.workspaceRoot(groupId)));
        if (hasErrors(issues)) return failure(group, issues, "");
        issues.addAll(CaliberCompiler.ruleWarnings(projectDir));
        snapshot = shipped(snapshot, projectDir);

        WrenCli.Result validation =
                wrenCli.run(
                        projectDir, props.timeout(), List.of("context", "validate", "--strict"));
        issues.addAll(buildChecks.parseWrenOutput(validation));
        if (hasErrors(issues)) {
            return failure(group, issues, validation.output());
        }

        WrenCli.Result build =
                wrenCli.run(projectDir, props.timeout(), List.of("context", "build"));
        if (!build.ok()) {
            issues.addAll(buildChecks.parseWrenOutput(build));
            if (!hasErrors(issues)) {
                issues.add(issue("error", "wren build 失败：" + firstLine(build.output()), "wren"));
            }
            return failure(group, issues, build.output());
        }

        // Official build output is immutable semantic input.
        // Same gate as validate(): a view that cannot survive its own dry-run must never be
        // snapshotted; the official engine is the authority for view execution.
        issues.addAll(buildChecks.dryRunSqlObjects(projectDir, snapshot, selection.external()));
        // ADR 0033 D6: every cube must survive its own SQL transformation before the snapshot.
        issues.addAll(buildChecks.dryRunCubes(projectDir, snapshot.cubes()));
        // spec149: generated caliber assets must be exactly what their lists produce.
        issues.addAll(CaliberCompiler.check(projectDir));
        if (hasErrors(issues)) {
            return failure(group, issues, build.output());
        }

        // spec091: confirmed questions must still plan against this build; failures are left out
        // of the snapshot and returned to the user without blocking the model release.
        issues.addAll(dryPlanConfirmedQuestions(groupId, projectDir, selection.external()));

        Path groupRoot = props.groupRoot(groupId);
        Path preparedDir = groupRoot.resolve("published.next");
        Path preparedManifest = groupRoot.resolve("mdl.json.next");
        prepareSnapshot(projectDir, preparedDir);
        // spec112: only confirmed questions ship as examples; those awaiting review stay out
        // without blocking the release.
        for (var held : questionStore.filterPublishedExamples(groupId, preparedDir))
            issues.add(
                    issue(
                            "warning",
                            "「" + held.question().question() + "」口径变更待复核，暂不作为问数示例",
                            "questions-review"));

        writeWrenSourceProperties(preparedDir, selection.profile());
        if (assetReviews != null) assetReviews.writeWindows(groupId, preparedDir);
        Instant publicationTime = Instant.now();
        writeMetaJson(preparedManifest, group, snapshot, publicationTime);
        int previousVersion = group.getMdlVersion();
        String previousState = group.getMdlState();
        Instant previousPublishedAt = group.getMdlPublishedAt();
        Instant previousUpdatedAt = group.getUpdatedAt();
        String previousError = group.getMdlLastError();
        try {
            publicationStore.install(
                    groupRoot,
                    preparedDir,
                    preparedManifest,
                    previousVersion,
                    () -> commitPublication(group, coveredRevision, publicationTime),
                    () -> committedVersion(groupId));
        } catch (RuntimeException failure) {
            group.setMdlVersion(previousVersion);
            group.setMdlState(previousState);
            group.setMdlPublishedAt(previousPublishedAt);
            group.setUpdatedAt(previousUpdatedAt);
            group.setMdlLastError(previousError);
            throw failure;
        }
        applyPublication(group, previousVersion + 1, publicationTime);
        // The draft now equals the snapshot; its change annotations are spent (ADR0111).
        try {
            DraftChangeLog.clear(workspace.workspaceRoot(groupId));
        } catch (RuntimeException cleanup) {
            // Publication is committed; annotation cleanup cannot turn success into a retry.
            issues.add(issue("warning", "发布已完成，草稿修改记录清理失败，请检查工作区文件权限", "cleanup"));
            log.warn("Committed publication annotation cleanup pending: {}", groupId);
        }

        // From here the snapshot reflects every change committed before this run began; automatic
        // baseline requests that entered before this tick can skip their own run.
        assembledAtTicks.merge(groupId, assembleStartTick, Math::max);
        try {
            events.publishEvent(new PublishedQueryProjectChanged(group.getOwnerId(), groupId));
        } catch (RuntimeException failure) {
            log.warn(
                    "Query preparation scheduling failed for group={}: {}",
                    groupId,
                    failure.getClass().getSimpleName());
        }
        return new MdlPublishResult(
                true,
                issues,
                build.output(),
                group.getMdlState(),
                group.getMdlVersion(),
                group.getMdlPublishedAt() == null ? null : group.getMdlPublishedAt().toString());
    }

    /**
     * Plans one question SQL against the current draft, compiled in scratch, with the same
     * dry-plan publish applies to confirmed questions. Returns null when it plans (or when the
     * draft itself does not build, which publish validation reports), else the official error.
     */
    public String planDraftSql(String groupId, String sql) {
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    try {
                        Path scratch = workspace.copyToScratch(groupId);
                        WrenCli.Result build =
                                wrenCli.run(scratch, props.timeout(), List.of("context", "build"));
                        if (!build.ok()) return null;
                        WrenCli.Result plan =
                                wrenCli.run(
                                        scratch,
                                        props.timeout(),
                                        List.of(
                                                "dry-plan",
                                                "--sql",
                                                sql,
                                                "-d",
                                                props.dataSource()));
                        if (!plan.ok()) return planError(plan.output());
                        return explainDraft(groupId, scratch, List.of(sql)).get(0);
                    } finally {
                        workspace.deleteScratchQuietly(groupId);
                    }
                });
    }

    /** The workspace snapshot restricted to the Views and Cubes present in the staging copy. */
    private static MdlWorkspaceReader.Snapshot shipped(
            MdlWorkspaceReader.Snapshot snapshot, Path projectDir) {
        return new MdlWorkspaceReader.Snapshot(
                snapshot.files(),
                snapshot.issues(),
                snapshot.models(),
                snapshot.relations(),
                snapshot.cubes().stream()
                        .filter(c -> Files.isRegularFile(projectDir.resolve(c.path())))
                        .toList(),
                snapshot.views().stream()
                        .filter(v -> Files.isRegularFile(projectDir.resolve(v.path())))
                        .toList());
    }

    /**
     * spec149 P2: caliber verification expands each generated Cube query with the official
     * {@code cube query --sql-only} on one scratch build of the draft, then EXPLAINs the expanded
     * SQL; no rows are read. {@code sql} of each plan is the argument line shown to the reviewer.
     */
    public List<DraftPlan> planDraftCubeQueries(String groupId, List<List<String>> queries) {
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    try {
                        Path scratch = workspace.copyToScratch(groupId);
                        WrenCli.Result build =
                                wrenCli.run(scratch, props.timeout(), List.of("context", "build"));
                        List<DraftPlan> out = new ArrayList<>();
                        for (List<String> query : queries) {
                            String line = String.join(" ", query);
                            if (!build.ok()) {
                                out.add(
                                        new DraftPlan(
                                                line, null, "草稿编译失败：" + planError(build.output())));
                                continue;
                            }
                            List<String> args = new ArrayList<>(List.of("cube", "query"));
                            args.addAll(query);
                            args.add("--sql-only");
                            WrenCli.Result plan = wrenCli.run(scratch, props.timeout(), args);
                            String expanded = plan.output() == null ? "" : plan.output().strip();
                            out.add(
                                    plan.ok() && !expanded.isEmpty()
                                            ? new DraftPlan(line, expanded, null)
                                            : new DraftPlan(line, null, planError(plan.output())));
                        }
                        List<Integer> planned = new ArrayList<>();
                        for (int i = 0; i < out.size(); i++)
                            if (out.get(i).error() == null) planned.add(i);
                        if (!planned.isEmpty()) {
                            List<String> errors =
                                    explainDraft(
                                            groupId,
                                            scratch,
                                            planned.stream()
                                                    .map(i -> out.get(i).expanded())
                                                    .toList());
                            for (int k = 0; k < planned.size(); k++) {
                                if (errors.get(k) == null) continue;
                                DraftPlan p = out.get(planned.get(k));
                                out.set(
                                        planned.get(k),
                                        new DraftPlan(p.sql(), p.expanded(), errors.get(k)));
                            }
                        }
                        return out;
                    } finally {
                        workspace.deleteScratchQuietly(groupId);
                    }
                });
    }

    /** EXPLAINs SQL already planned on the scratch build, with the group's publish connection. */
    private List<String> explainDraft(String groupId, Path scratch, List<String> sqls) {
        List<DatasetEntity> datasets = datasetRepository.findByGroupId(groupId);
        String refused = datasets.isEmpty() ? "知识库内没有数据集，无法校验 SQL" : null;
        WrenSourceSelection selection = refused == null ? selectWrenSource(datasets) : null;
        if (selection != null && selection.error() != null) refused = selection.error();
        if (refused != null) {
            String error = refused;
            return sqls.stream().map(sql -> error).toList();
        }
        try {
            return buildChecks.explainAll(scratch, sqls, selection.external());
        } catch (DatasetException e) {
            return sqls.stream().map(sql -> e.getMessage()).toList();
        }
    }

    /**
     * One SQL planned against the draft: the expanded SQL Wren would send, or the error. Since
     * spec149 an EXPLAIN failure keeps the expansion and adds the error, so it cannot pass.
     */
    public record DraftPlan(String sql, String expanded, String error) {}

    /**
     * spec114 / ADR0132: verification only reads SQL. Each SQL is planned with the official
     * {@code dry-plan} on one scratch build of the draft, then EXPLAINed (spec149 P0) so unknown
     * columns fail; no rows are read.
     */
    public List<DraftPlan> planDraft(String groupId, List<String> sqls) {
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    try {
                        Path scratch = workspace.copyToScratch(groupId);
                        WrenCli.Result build =
                                wrenCli.run(scratch, props.timeout(), List.of("context", "build"));
                        List<DraftPlan> out = new ArrayList<>();
                        for (String sql : sqls) {
                            if (!build.ok()) {
                                out.add(
                                        new DraftPlan(
                                                sql, null, "草稿编译失败：" + planError(build.output())));
                                continue;
                            }
                            WrenCli.Result plan =
                                    wrenCli.run(
                                            scratch,
                                            props.timeout(),
                                            List.of(
                                                    "dry-plan",
                                                    "--sql",
                                                    sql,
                                                    "-d",
                                                    props.dataSource()));
                            out.add(
                                    plan.ok()
                                            ? new DraftPlan(
                                                    sql,
                                                    plan.output() == null
                                                            ? ""
                                                            : plan.output().strip(),
                                                    null)
                                            : new DraftPlan(sql, null, planError(plan.output())));
                        }
                        // spec149 P0: dry-plan does not resolve columns; planned SQL must also
                        // pass the database's EXPLAIN, keeping the expansion for the reviewer.
                        List<Integer> planned = new ArrayList<>();
                        for (int i = 0; i < out.size(); i++) {
                            if (out.get(i).error() == null) planned.add(i);
                        }
                        if (!planned.isEmpty()) {
                            List<String> errors =
                                    explainDraft(
                                            groupId,
                                            scratch,
                                            planned.stream().map(i -> out.get(i).sql()).toList());
                            for (int k = 0; k < planned.size(); k++) {
                                if (errors.get(k) == null) continue;
                                DraftPlan p = out.get(planned.get(k));
                                out.set(
                                        planned.get(k),
                                        new DraftPlan(p.sql(), p.expanded(), errors.get(k)));
                            }
                        }
                        return out;
                    } finally {
                        workspace.deleteScratchQuietly(groupId);
                    }
                });
    }

    /** The official "Error: ..." line, skipping notes the CLI prints before it. */
    static String planError(String output) {
        if (output != null) {
            for (String line : output.split("\\R")) {
                String t = line.strip();
                if (t.startsWith("Error")) return t.length() > 300 ? t.substring(0, 300) + "…" : t;
            }
        }
        return firstLine(output);
    }

    private List<MdlIssue> dryPlanConfirmedQuestions(
            String groupId, Path projectDir, ExternalDataSourceEntity external) {
        List<MdlIssue> issues = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        Map<String, String> planned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : questionStore.confirmedSql(groupId).entrySet()) {
            WrenCli.Result result =
                    wrenCli.run(
                            projectDir,
                            props.timeout(),
                            List.of(
                                    "dry-plan",
                                    "--sql",
                                    entry.getValue(),
                                    "-d",
                                    props.dataSource()));
            if (result.ok()) planned.put(entry.getKey(), entry.getValue());
            else failures.put(entry.getKey(), planError(result.output()));
        }
        // spec149 P0: dry-plan does not resolve columns, so planned questions are also EXPLAINed.
        // An unavailable connection only warns; it must not revoke confirmations.
        if (!planned.isEmpty()) {
            try {
                List<String> keys = List.copyOf(planned.keySet());
                List<String> errors =
                        buildChecks.explainAll(projectDir, List.copyOf(planned.values()), external);
                for (int i = 0; i < keys.size(); i++) {
                    if (errors.get(i) != null) failures.put(keys.get(i), errors.get(i));
                }
            } catch (DatasetException e) {
                issues.add(issue("warning", "问答数据库校验未执行：" + e.getMessage(), "questions"));
            }
        }
        for (Map.Entry<String, String> failure : failures.entrySet()) {
            String reason = "发布检查未通过，未进入本次发布：" + failure.getValue();
            try {
                Files.deleteIfExists(
                        projectDir.resolve("knowledge/sql/" + failure.getKey() + ".md"));
            } catch (IOException e) {
                throw new DatasetException("移除未通过检查的问答失败：" + e.getMessage(), e);
            }
            questionStore.revokeConfirmation(groupId, failure.getKey(), reason);
            String text = questionStore.requireQuestion(groupId, failure.getKey()).question();
            issues.add(issue("warning", "「" + text + "」" + reason, "questions"));
        }
        return issues;
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
    /** Returns the server-selected draft connection; agents cannot choose a profile. */
    public String draftCliProfile(String actorId, String groupId) {
        if (access == null) throw new DatasetException("知识库权限服务尚未就绪，未绑定草稿连接", 503);
        access.requireManage(actorId, groupId);
        WrenSourceSelection selected = selectWrenSource(datasetRepository.findByGroupId(groupId));
        if (selected.error() != null) throw new DatasetException(selected.error(), 400);
        profileHome.ensureAll(externalSources.findAll());
        return selected.profile();
    }

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
        if (!source.isEnabled()) return WrenSourceSelection.refused("数据源已停用，请联系创建人或全局管理员启用后再发布。");
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

    // ------------------------------------------------------------------ file system

    /**
     * Lists the published snapshot's files for the diff card, excluding build output and the
     * runtime profile marker (neither exists in the workspace, so they would always look
     * "changed").
     */
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

    /**
     * Renders the platform manifest from the workspace snapshot. Shape identical to v1 (read by
     * {@code MdlCatalog} for the prompt middleware and {@code wren_describe_model}): models with
     * dataset ids from the platform mapping, relations with the parsed column pairs, cubes and
     * views with their members.
     */
    private void writeMetaJson(
            Path manifestFile,
            DatasetGroupEntity group,
            MdlWorkspaceReader.Snapshot snapshot,
            Instant publicationTime) {
        var tree =
                new MdlManifestRenderer(mapper)
                        .render(
                                new MdlManifestRenderer.Header(
                                        group.getId(),
                                        group.getName(),
                                        group.getMdlVersion() + 1,
                                        publicationTime,
                                        props.dataSource()),
                                snapshot);
        try {
            Files.createDirectories(manifestFile.getParent());
            Files.writeString(
                    manifestFile,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree)
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("写入 mdl.json 失败：" + e.getMessage(), e);
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
        if (publicationTransactions == null) groupRepository.save(group);
        else
            publicationTransaction()
                    .executeWithoutResult(
                            status ->
                                    groupRepository.recordPublicationFailure(
                                            group.getId(),
                                            group.getMdlLastError(),
                                            group.getUpdatedAt()));
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
            MdlWorkspaceReader reader,
            KnowledgeBaseOperations operations,
            KnowledgeBaseAccessService access) {
        this(
                datasetRepository,
                groupRepository,
                externalSources,
                mapper,
                props,
                wrenCli,
                profileHome,
                sourceProber,
                seeder,
                workspace,
                reader,
                operations,
                access,
                event -> {});
    }

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
            MdlWorkspaceReader reader,
            KnowledgeBaseOperations operations,
            KnowledgeBaseAccessService access,
            org.springframework.context.ApplicationEventPublisher events) {
        this(
                datasetRepository,
                groupRepository,
                externalSources,
                mapper,
                props,
                wrenCli,
                profileHome,
                sourceProber,
                seeder,
                workspace,
                reader,
                operations,
                access,
                events,
                null);
    }

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
            MdlWorkspaceReader reader,
            KnowledgeBaseOperations operations,
            KnowledgeBaseAccessService access,
            org.springframework.context.ApplicationEventPublisher events,
            AssetReviewStore assetReviews) {
        this(
                datasetRepository,
                groupRepository,
                externalSources,
                mapper,
                props,
                wrenCli,
                profileHome,
                sourceProber,
                seeder,
                workspace,
                reader,
                events);
        this.operations = operations;
        this.access = access;
        this.assetReviews = assetReviews;
    }

    @org.springframework.beans.factory.annotation.Autowired
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
            MdlWorkspaceReader reader,
            KnowledgeBaseOperations operations,
            KnowledgeBaseAccessService access,
            org.springframework.context.ApplicationEventPublisher events,
            AssetReviewStore assetReviews,
            org.springframework.transaction.PlatformTransactionManager publicationTransactions) {
        this(
                datasetRepository,
                groupRepository,
                externalSources,
                mapper,
                props,
                wrenCli,
                profileHome,
                sourceProber,
                seeder,
                workspace,
                reader,
                operations,
                access,
                events,
                assetReviews);
        this.publicationTransactions = publicationTransactions;
    }
}
