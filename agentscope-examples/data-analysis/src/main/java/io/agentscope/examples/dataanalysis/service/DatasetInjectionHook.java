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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.examples.dataanalysis.entity.QueryResultCache;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A Hook that injects the dataset catalogue and historical query cache into the system message
 * before each LLM reasoning step.
 *
 * <p>The dataset catalogue (brief + detail) is provided by {@link DatasetCatalogueService},
 * which pre-loads it at application startup and keeps it in memory. This means there is
 * <b>zero remote-call overhead</b> per reasoning step — previously this hook fetched the
 * brief catalogue from the NLP service on the first call of every session (~837ms).
 *
 * <p>A version-counter mechanism ensures the hook always picks up the latest catalogue:
 * when {@link DatasetCatalogueService} refreshes the catalogue (e.g. on new session creation),
 * it bumps its version counter. This hook compares that version against its last-seen version
 * on every reasoning step; if a newer version is detected, the prompt is rebuilt.
 *
 * <p>Historical query cache is still loaded from the database to enable multi-turn data reuse.
 *
 * <p>Priority is set to {@value #PRIORITY} so it runs after {@link ContextTrimHook} (10)
 * and after {@link ToolResultLifecycleHook} (15), ensuring trimming happens first.
 */
public class DatasetInjectionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(DatasetInjectionHook.class);

    /** Run after ContextTrimHook (10) and ToolResultLifecycleHook (15). */
    static final int PRIORITY = 20;

    private final DatasetCatalogueService catalogueService;
    private final String baseSysPrompt;
    private final String sessionId;
    private final QueryResultCacheService cacheService;

    /** Cached full system prompt = base + catalogue + echarts + historyCache. */
    private volatile String cachedSysPrompt = null;

    /**
     * Last-seen catalogue version. When this differs from
     * {@link DatasetCatalogueService#getVersion()}, the prompt is rebuilt.
     */
    private volatile int lastCatalogueVersion = -1;

    /**
     * Flag to force refresh of cached sys prompt on next reasoning.
     * Set to true when new query results are cached (history cache changed).
     */
    private volatile boolean needsRefresh = false;

    public DatasetInjectionHook(
            DatasetCatalogueService catalogueService,
            String baseSysPrompt,
            String sessionId,
            QueryResultCacheService cacheService) {
        this.catalogueService = catalogueService;
        this.baseSysPrompt = baseSysPrompt;
        this.sessionId = sessionId;
        this.cacheService = cacheService;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreReasoningEvent pre)) {
            return Mono.just(event);
        }

        int currentCatalogueVersion = catalogueService.getVersion();
        boolean catalogueChanged = (currentCatalogueVersion != lastCatalogueVersion);

        // Fast path: no changes at all — inject cached prompt directly (zero I/O).
        if (cachedSysPrompt != null && !needsRefresh && !catalogueChanged) {
            injectSysPrompt(pre, cachedSysPrompt);
            return Mono.just(event);
        }

        // Rebuild: catalogue section is read from memory (zero remote I/O);
        // only the history cache requires a DB query (fast, local).
        String sysPrompt = buildSysPrompt();
        cachedSysPrompt = sysPrompt;
        lastCatalogueVersion = currentCatalogueVersion;
        needsRefresh = false;

        log.info(
                "[DatasetInjectionHook] session={} prompt {} (catalogueVersion={}, "
                        + "historyCache entries={}).",
                sessionId,
                catalogueChanged ? "rebuilt (catalogue changed)" : "refreshed (history changed)",
                currentCatalogueVersion,
                cacheService.getCachedResults(sessionId).size());

        injectSysPrompt(pre, sysPrompt);
        return Mono.just(event);
    }

    /**
     * Mark that the cached sys prompt needs to be refreshed (history cache changed).
     */
    public void markNeedsRefresh() {
        this.needsRefresh = true;
        log.debug("[DatasetInjectionHook] Marked for refresh on next reasoning");
    }

    // ─────────────────── Internal helpers ───────────────────

    /**
     * Replace or create the leading SYSTEM message in the input messages with the given prompt.
     */
    private void injectSysPrompt(PreReasoningEvent event, String sysPrompt) {
        List<Msg> messages = event.getInputMessages();
        List<Msg> modified = new ArrayList<>(messages.size());

        // Replace existing system message, or prepend a new one.
        if (!messages.isEmpty() && MsgRole.SYSTEM.equals(messages.get(0).getRole())) {
            // Rebuild the system message with the enriched content, preserving other fields.
            Msg original = messages.get(0);
            modified.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .name(original.getName())
                            .content(TextBlock.builder().text(sysPrompt).build())
                            .metadata(original.getMetadata())
                            .build());
            modified.addAll(messages.subList(1, messages.size()));
        } else {
            modified.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .name("system")
                            .content(TextBlock.builder().text(sysPrompt).build())
                            .build());
            modified.addAll(messages);
        }

        event.setInputMessages(modified);
    }

    /**
     * Build the full system prompt:
     *   baseSysPrompt + ECHARTS_SKILL_SPEC + catalogueSection + historyCacheSection
     *
     * <p>All parts except historyCacheSection are read from memory — zero I/O.
     */
    private String buildSysPrompt() {
        StringBuilder sb = new StringBuilder(baseSysPrompt);
        sb.append(ECHARTS_SKILL_SPEC);

        // ── Dataset catalogue section (brief + detail, from DatasetCatalogueService) ──
        String catalogueSection = catalogueService.getCatalogueSection();
        if (catalogueSection != null && !catalogueSection.isBlank()) {
            sb.append(catalogueSection);
        }

        // ── Historical query cache section (from DB) ──
        List<QueryResultCache> historyCache = cacheService.getCachedResults(sessionId);
        if (historyCache != null && !historyCache.isEmpty()) {
            sb.append("\n\n---\n")
                    .append("## Historical Query Results (本轮对话已查询数据)\n")
                    .append("**重要**：调用 `query_dataset` 前，先检查下表是否有可复用的数据。\n")
                    .append("通过对比当前问题与历史问题，判断是否可以复用。\n\n")
                    .append("| 序号 | 数据集 | 查询问题 |\n")
                    .append("|------|--------|----------|\n");

            int index = 1;
            for (QueryResultCache cache : historyCache) {
                String truncatedQuestion = truncate(cache.getQuestion(), 50);
                sb.append(
                        String.format(
                                "| %d | %s | %s |\n",
                                index++, cache.getDatasetName(), truncatedQuestion));
            }

            sb.append("\n**复用规则**：对比当前问题与历史问题的语义相似度，")
                    .append("若历史数据可能包含所需信息，可直接复用，禁止调用 `query_dataset`。\n");
        }

        return sb.toString();
    }

    /**
     * ECharts chart generation specification embedded into every system prompt.
     *
     * <p>Previously loaded on-demand via {@code load_skill_through_path}, which wasted one LLM
     * reasoning round per report and caused the skill result to be truncated in history (default
     * 500-char limit). Embedding it statically ensures the LLM always has the full spec without
     * any tool call overhead.
     */
    private static final String ECHARTS_SKILL_SPEC =
            "\n\n"
                + "---\n"
                + "## 图表生成规范（ECharts）\n\n"
                + "在 `<chart>` 标签内输出合法 JSON，前端自动渲染。`<chart>` 紧跟在 `</report>` 之后，内部只写"
                + " JSON，不用代码块包裹；每次最多一个图表；仅1个数据点时不输出图表；data 数组必须包含查询结果的**全部**数据行，严禁抽样。\n\n"
                + "**图表类型选择：**\n"
                + "| 场景 | type |\n"
                + "|------|------|\n"
                + "| 单指标时间趋势 | `line` |\n"
                + "| 多指标时间趋势对比 | `multi_line` |\n"
                + "| 单指标分类对比/排名 | `bar` |\n"
                + "| 多指标分类对比 | `multi_bar` |\n"
                + "| 占比构成 | `pie` |\n"
                + "| 两量纲差异大的指标 | `dual_axes` |\n\n"
                + "**JSON 格式：**\n"
                + "- `line`/`bar`/`pie`：`{\"type\":\"line\",\"title\":\"标题\",\"data\":[{\"label\":\"3-25\",\"value\":1200}]}`\n"
                + "- `multi_line`/`multi_bar`：`{\"type\":\"multi_line\",\"title\":\"标题\",\"categories\":[\"3-25\"],\"series\":[{\"name\":\"指标A\",\"data\":[1200]}]}`\n"
                + "- `dual_axes`：series 每项需含 `\"type\":\"bar\"|\"line\"` 和 `\"yAxisIndex\":0|1`，另需"
                + " `\"yAxis\":[{\"name\":\"左轴\"},{\"name\":\"右轴\"}]`\n";

    /** Truncate a string to maxLen characters, adding ellipsis if truncated. */
    private String truncate(String s, int maxLen) {
        if (s == null) return "-";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}
