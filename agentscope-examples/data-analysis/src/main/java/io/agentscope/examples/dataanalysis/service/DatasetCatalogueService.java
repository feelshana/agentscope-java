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
package io.agentscope.examples.dataanalysis.service;

import io.agentscope.examples.dataanalysis.client.DataApiClient;
import io.agentscope.examples.dataanalysis.dto.DatasetInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Manages the dataset catalogue section injected into the LLM system prompt.
 *
 * <p>Two information sources are combined:
 * <ol>
 *   <li><b>Brief catalogue</b>: dataset name + description, fetched from the NLP service.
 *       Refreshed at startup (blocking) and on every new session creation (async).</li>
 *   <li><b>Detail catalogue</b>: dimension/metric/term definitions, loaded from a local file
 *       {@code prompts/dataset-detail.txt} at startup as a fallback, then refreshed from the
 *       NLP service. If the API call succeeds, the file on disk is updated with the latest
 *       content; if it fails, the file-based content remains in use.</li>
 * </ol>
 *
 * <p>A monotonically-increasing {@link #version} counter is bumped on every catalogue rebuild.
 * {@link DatasetInjectionHook} tracks the version it last used and rebuilds its cached prompt
 * whenever it detects a newer version — ensuring that async refresh results are picked up
 * on the very next reasoning step without any remote-call overhead.
 */
@Service
public class DatasetCatalogueService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DatasetCatalogueService.class);

    /** Maximum time (seconds) to wait for the initial brief fetch at startup. */
    private static final int STARTUP_FETCH_TIMEOUT_SECONDS = 30;

    private final DataApiClient dataApiClient;

    /** Fully-built catalogue section (brief + detail) ready to append to system prompt. */
    private final AtomicReference<String> cachedCatalogueSection = new AtomicReference<>("");

    /**
     * Monotonically-increasing version counter. Bumped every time the catalogue section is
     * rebuilt (brief refresh or detail file reload). Consumers (e.g. DatasetInjectionHook)
     * compare this against their last-seen version to detect changes.
     */
    private final AtomicInteger version = new AtomicInteger(0);

    /** Guard: prevents concurrent brief-refresh calls from stacking up. */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    /** Last-known brief dataset list (for logging / diagnostics). */
    private volatile List<DatasetInfo> lastKnownDatasets = Collections.emptyList();

    /** Last brief refresh time (for diagnostics). */
    private volatile LocalDateTime lastRefreshedAt = null;

    /** Detail content loaded from file; replaced on {@link #reloadDetailFromFile()}. */
    private volatile String detailFileContent = "";

    /** Tracks where the current detail content came from: "file" or "api". */
    private volatile String detailRefreshSource = "file";

    @Value("${dataset.catalogue.detail-file:prompts/dataset-detail.txt}")
    private String detailFilePath;

    /**
     * External (on-disk) path for the detail file. When set, both reading and writing use this
     * path, ensuring API-refreshed content survives restarts. When blank (default), the path
     * is derived from {@link #detailFilePath} — see {@link #resolveExternalDetailFilePath()}.
     *
     * <p>In production JAR deployments, set this to an absolute path outside the JAR
     * (e.g. {@code /opt/app/prompts/dataset-detail.txt}) so that the file can be both
     * read and written.
     */
    @Value("${dataset.catalogue.detail-file-path:}")
    private String detailExternalPath;

    @Value("${dataset.catalogue.include-detail:true}")
    private boolean includeDetail;

    public DatasetCatalogueService(DataApiClient dataApiClient) {
        this.dataApiClient = dataApiClient;
    }

    // ─────────────────── Lifecycle ───────────────────

    /**
     * Load detail file, perform a <b>blocking</b> initial brief fetch, and attempt to refresh
     * detail from the NLP service at startup.
     *
     * <p>Startup sequence:
     * <ol>
     *   <li>Load detail from local file (fallback guarantee)</li>
     *   <li>Blocking brief fetch (guarantees non-empty catalogue for first request)</li>
     *   <li>Attempt to refresh detail from NLP service — if successful, update the local file
     *       and rebuild the section; if failed, the file-based detail from step 1 remains</li>
     * </ol>
     */
    @Override
    public void afterPropertiesSet() {
        detailFileContent = loadDetailFile();
        log.info(
                "[DatasetCatalogueService] Detail file loaded ({} chars). "
                        + "Performing blocking initial brief fetch...",
                detailFileContent.length());
        doRefreshBriefBlocking("startup");
        doRefreshDetailFromApi("startup");
        log.info(
                "[DatasetCatalogueService] Initialization complete. Catalogue section: {} chars, "
                        + "version={}, detailSource={}.",
                cachedCatalogueSection.get().length(),
                version.get(),
                detailRefreshSource);
    }

    // ─────────────────── Public API ───────────────────

    /**
     * Trigger an async brief refresh for a new session.
     *
     * <p>Called by {@link SessionAgentManager} every time a new in-memory session entry is
     * created. The refresh runs in the background — callers return immediately with the
     * currently-cached section. The updated section will be detected by
     * {@link DatasetInjectionHook} via the version counter on the next reasoning step.
     *
     * <p>Concurrent calls are de-duplicated: if a refresh is already in progress,
     * this call is silently ignored.
     */
    public void triggerSessionRefresh() {
        doRefreshBriefAsync("new-session");
    }

    /**
     * Return the fully-built catalogue section ready to be appended to the system prompt.
     * Pure in-memory read — zero latency.
     */
    public String getCatalogueSection() {
        return cachedCatalogueSection.get();
    }

    /**
     * Return the current version of the catalogue section.
     * Consumers compare this against their last-seen version to detect changes.
     */
    public int getVersion() {
        return version.get();
    }

    /**
     * Force-reload the detail file from classpath and rebuild the cached section.
     * Useful after manually updating dataset-detail.txt and redeploying.
     */
    public void reloadDetailFromFile() {
        detailFileContent = loadDetailFile();
        log.info(
                "[DatasetCatalogueService] Detail file reloaded ({} chars).",
                detailFileContent.length());
        rebuildSection(lastKnownDatasets);
    }

    /**
     * Return diagnostic information about the current cache state.
     */
    public String getDiagnostics() {
        return String.format(
                "version=%d, datasets=%d, lastRefreshedAt=%s, sectionLength=%d, refreshing=%b,"
                        + " detailSource=%s",
                version.get(),
                lastKnownDatasets.size(),
                lastRefreshedAt,
                cachedCatalogueSection.get().length(),
                refreshing.get(),
                detailRefreshSource);
    }

    // ─────────────────── Internal: brief refresh ───────────────────

    /**
     * Blocking brief fetch — used at startup to ensure the catalogue is populated
     * before any user request arrives.
     */
    private void doRefreshBriefBlocking(String reason) {
        try {
            List<DatasetInfo> datasets =
                    dataApiClient
                            .listDatasets()
                            .doOnNext(dataApiClient::registerDatasets)
                            .block(Duration.ofSeconds(STARTUP_FETCH_TIMEOUT_SECONDS));
            if (datasets != null) {
                lastKnownDatasets = datasets;
                lastRefreshedAt = LocalDateTime.now();
                rebuildSection(datasets);
                log.info(
                        "[DatasetCatalogueService] Brief refreshed [blocking, {}]: {} datasets, "
                                + "section {} chars, version={}.",
                        reason,
                        datasets.size(),
                        cachedCatalogueSection.get().length(),
                        version.get());
            } else {
                log.warn(
                        "[DatasetCatalogueService] Brief fetch [blocking, {}] returned null, "
                                + "using empty catalogue.",
                        reason);
            }
        } catch (Exception e) {
            log.error(
                    "[DatasetCatalogueService] Brief fetch [blocking, {}] failed: {}. Proceeding"
                            + " with empty catalogue — will retry on next session creation.",
                    reason,
                    e.getMessage());
        }
    }

    /**
     * Async brief fetch with concurrency guard.
     * If a refresh is already running, the new call is dropped to avoid request stacking.
     */
    private void doRefreshBriefAsync(String reason) {
        if (!refreshing.compareAndSet(false, true)) {
            log.debug(
                    "[DatasetCatalogueService] Refresh already in progress, skipping ({}).",
                    reason);
            return;
        }
        dataApiClient
                .listDatasets()
                .doOnNext(dataApiClient::registerDatasets)
                .doOnNext(
                        datasets -> {
                            lastKnownDatasets = datasets;
                            lastRefreshedAt = LocalDateTime.now();
                            rebuildSection(datasets);
                            log.info(
                                    "[DatasetCatalogueService] Brief refreshed [async, {}]: {}"
                                            + " datasets, section {} chars, version={}.",
                                    reason,
                                    datasets.size(),
                                    cachedCatalogueSection.get().length(),
                                    version.get());
                        })
                .doOnError(
                        e ->
                                log.error(
                                        "[DatasetCatalogueService] Brief refresh [async, {}]"
                                                + " failed: {}",
                                        reason,
                                        e.getMessage()))
                .doFinally(signal -> refreshing.set(false))
                .subscribe();
    }

    // ─────────────────── Internal: detail refresh from API ───────────────────

    /**
     * Blocking detail fetch from the NLP service. On success, overwrites
     * {@link #detailFileContent}, writes the new content back to the local file, and
     * rebuilds the cached section. On failure, the existing file-based content remains
     * unchanged — the service continues with the last-known-good detail.
     *
     * @param reason a label for logging (e.g. "startup", "manual")
     */
    private void doRefreshDetailFromApi(String reason) {
        if (lastKnownDatasets == null || lastKnownDatasets.isEmpty()) {
            log.warn(
                    "[DatasetCatalogueService] No datasets available, skipping detail refresh"
                            + " [{}].",
                    reason);
            return;
        }
        String agentIds =
                lastKnownDatasets.stream()
                        .map(DatasetInfo::getAgentId)
                        .filter(id -> id != null && !id.isBlank())
                        .collect(Collectors.joining(","));
        if (agentIds.isBlank()) {
            log.warn(
                    "[DatasetCatalogueService] No agentIds in dataset list, skipping detail"
                            + " refresh [{}].",
                    reason);
            return;
        }
        try {
            String apiDetail =
                    dataApiClient
                            .fetchDatasetDetail(agentIds)
                            .block(Duration.ofSeconds(STARTUP_FETCH_TIMEOUT_SECONDS));
            if (apiDetail != null
                    && !apiDetail.isBlank()
                    && !apiDetail.startsWith("Failed to fetch")) {
                detailFileContent = apiDetail;
                detailRefreshSource = "api";
                rebuildSection(lastKnownDatasets);
                log.info(
                        "[DatasetCatalogueService] Detail refreshed from API [{}]: {} chars, "
                                + "version={}.",
                        reason,
                        apiDetail.length(),
                        version.get());
                // Write back to the local file so it serves as a fresh fallback next time
                writeDetailFile(apiDetail);
            } else {
                log.warn(
                        "[DatasetCatalogueService] Detail API returned empty/error [{}], "
                                + "keeping file-based detail ({} chars).",
                        reason,
                        detailFileContent.length());
            }
        } catch (Exception e) {
            log.warn(
                    "[DatasetCatalogueService] Detail API fetch [{}] failed: {}. Keeping "
                            + "file-based detail ({} chars).",
                    reason,
                    e.getMessage(),
                    detailFileContent.length());
        }
    }

    /**
     * Write the given content to the detail file on disk. Best-effort — failure is logged
     * but does not affect the in-memory cache.
     *
     * <p>Always writes to the external file path (the same path that {@link #loadDetailFile}
     * reads from first). This ensures that the next startup will pick up the latest content.
     */
    private void writeDetailFile(String content) {
        try {
            Path path = resolveExternalDetailFilePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            log.info(
                    "[DatasetCatalogueService] Detail file updated on disk: {} chars → {}",
                    content.length(),
                    path);
        } catch (Exception e) {
            log.warn(
                    "[DatasetCatalogueService] Failed to write detail file to disk: {}",
                    e.getMessage());
        }
    }

    /**
     * Resolve the external (on-disk) path for the detail file.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code dataset.catalogue.detail-file-path} is configured with an absolute or
     *       relative path, use it directly (relative to the working directory).</li>
     *   <li>Otherwise, derive the path from the classpath resource: in dev mode the
     *       classpath resource resolves to a real file; in a JAR deployment it won't,
     *       so we fall back to {@code ./prompts/dataset-detail.txt} relative to the
     *       working directory.</li>
     * </ol>
     */
    private Path resolveExternalDetailFilePath() {
        // If an explicit external path is configured, use it
        if (detailExternalPath != null && !detailExternalPath.isBlank()) {
            return Paths.get(detailExternalPath).toAbsolutePath();
        }
        // Try to resolve from classpath resource (works in IDE / exploded JAR)
        try {
            ClassPathResource resource = new ClassPathResource(detailFilePath);
            if (resource.isFile()) {
                return resource.getFile().toPath();
            }
        } catch (IOException e) {
            log.debug(
                    "[DatasetCatalogueService] ClassPathResource is not a file, using fallback"
                            + " path.");
        }
        // Fallback: working-directory relative path (for JAR deployments)
        return Paths.get(detailFilePath).toAbsolutePath();
    }

    // ─────────────────── Internal: section rebuild ───────────────────

    /**
     * Rebuild {@link #cachedCatalogueSection} from the given dataset list + current detail
     * file content, then bump the version counter.
     */
    private void rebuildSection(List<DatasetInfo> datasets) {
        StringBuilder sb = new StringBuilder();

        // ── Brief section ──
        if (datasets != null && !datasets.isEmpty()) {
            String catalogue =
                    datasets.stream()
                            .map(
                                    ds ->
                                            "  - Name: "
                                                    + ds.getName()
                                                    + "\n    Description: "
                                                    + ds.getDescription())
                            .collect(Collectors.joining("\n"));
            sb.append("\n\n---\n")
                    .append("## Available Datasets (pre-loaded)\n")
                    .append("**数据粒度说明**：除「驾驶舱热门内容日榜」外，其他数据集均为统计性聚合结果，")
                    .append("不包含用户明细数据。因此无法进行用户级分析（如单个用户行为路径、用户分群画像等）。")
                    .append("分析报告中应：\n")
                    .append("1. 基于聚合指标进行趋势判断、维度对比、异常归因；\n")
                    .append("2. 在结论中明确指出分析边界，如「基于聚合数据，无法定位具体用户」。\n")
                    .append(catalogue);
        }

        // ── Detail section (from local file) ──
        if (includeDetail && detailFileContent != null && !detailFileContent.isBlank()) {
            sb.append("\n\n---\n")
                    .append("## Dataset Field Details (维度、指标、维度值定义)\n")
                    .append("**⚠️ 使用说明**：以下详情仅供字段查阅，制定分析计划时请遵守：\n")
                    .append("1. 严格根据用户问题中提到的指标/维度判断最相关的数据集，**不要因为多个数据集都包含相似字段就同时查询**；\n")
                    .append("2. 优先选择 **1 个**最匹配的数据集；仅当用户问题明确涉及多个数据集的对比分析时，才创建多数据集子任务；\n")
                    .append("3. 有疑问时选字段覆盖度最高的那一个，而非全部都查。\n\n")
                    .append(detailFileContent);
        }

        cachedCatalogueSection.set(sb.toString());
        version.incrementAndGet(); // Signal to consumers that the catalogue has changed
    }

    // ─────────────────── Internal: file loading ───────────────────

    /**
     * Load the detail file content. Resolution order:
     * <ol>
     *   <li>External file on disk (same path that {@link #writeDetailFile} writes to) —
     *       ensures API-refreshed content survives restarts.</li>
     *   <li>Classpath resource (initial copy bundled inside the JAR) — fallback when the
     *       external file does not exist yet (e.g. first-ever startup).</li>
     * </ol>
     */
    private String loadDetailFile() {
        // 1. Try external file first (may have been updated by a previous API refresh)
        try {
            Path externalPath = resolveExternalDetailFilePath();
            if (Files.exists(externalPath) && Files.isReadable(externalPath)) {
                String content = Files.readString(externalPath, StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    log.info(
                            "[DatasetCatalogueService] Loaded detail from external file: {} ({}"
                                    + " chars).",
                            externalPath,
                            content.length());
                    return content;
                }
            }
        } catch (IOException e) {
            log.warn(
                    "[DatasetCatalogueService] Failed to read external detail file: {}",
                    e.getMessage());
        }

        // 2. Fallback: classpath resource (bundled in JAR)
        try {
            ClassPathResource resource = new ClassPathResource(detailFilePath);
            if (!resource.exists()) {
                log.warn(
                        "[DatasetCatalogueService] Detail file '{}' not found on classpath, "
                                + "proceeding without detail section.",
                        detailFilePath);
                return "";
            }
            String content =
                    StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            log.info(
                    "[DatasetCatalogueService] Loaded detail from classpath: '{}' ({} chars).",
                    detailFilePath,
                    content.length());
            return content;
        } catch (IOException e) {
            log.warn(
                    "[DatasetCatalogueService] Failed to load detail file from classpath '{}': {}",
                    detailFilePath,
                    e.getMessage());
            return "";
        }
    }
}
