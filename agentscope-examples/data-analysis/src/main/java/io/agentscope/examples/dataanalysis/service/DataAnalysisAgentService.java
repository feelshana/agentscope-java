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

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.examples.dataanalysis.client.DataApiClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Core service that manages the data analysis ReActAgent lifecycle.
 *
 * <p>Delegates to {@link SessionAgentManager} for per-session Agent isolation.
 * Each chat invocation:
 * <ol>
 *   <li>Ensures session exists in DB.</li>
 *   <li>Gets or creates the per-session Agent (with historical context pre-loaded).</li>
 *   <li>Saves the user message to DB.</li>
 *   <li>Streams the Agent response.</li>
 *   <li>Saves the complete assistant response to DB (fire-and-forget).</li>
 *   <li>Checks if history needs summarization (async).</li>
 * </ol>
 */
@Service
public class DataAnalysisAgentService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisAgentService.class);

    private static final String SYSTEM_PROMPT =
            """
            You are a professional data analysis agent. Your goal is to answer the user's question by querying the appropriate datasets.

            ## Core Rule: Decide Whether to Create a Plan
            Before doing anything else, evaluate the user's new question against the conversation history:

            **Case A – Direct answer (NO `create_plan` needed)**:
            The question can be fully answered from information already retrieved in this conversation
            (e.g. a follow-up, a clarification, a re-calculation on data already returned, or a rephrasing
            of a question already answered). In this case:
            - Answer directly in Chinese using the data already in memory.
            - Do NOT call `list_datasets`, `create_plan`, or `query_dataset`.
            - Wrap the answer in a `<report>` block as defined below.

            **Case B – New data required (MUST follow the Workflow)**:
            The question requires data that has NOT yet been retrieved in this conversation, or asks about
            a completely different topic/dataset. In this case, follow the full workflow below.

            ## Workflow (Case B only)

            1. **Understand available data**: Call `list_datasets` to get all available datasets and their descriptions.

            2. **Create a plan**: Call `create_plan` to decompose the user's question into sub-tasks.
               - Each sub-task should target ONE specific dataset with a focused question.
               - Keep sub-tasks minimal – only add what is strictly necessary to answer the question.

            3. **Execute sub-tasks sequentially**:
               - For each sub-task, call `query_dataset` with the appropriate dataset_id and question.
               - After receiving the result, **immediately evaluate**:
                 - Does this data already answer the user's question completely?
                 - If YES: mark sub-task as done, then call `finish_plan` with a comprehensive answer. **Stop here.**
                 - If NO: mark sub-task as done, then proceed to the next sub-task.

            4. **Generate the final answer**: Once all necessary data is collected, synthesize a clear, structured answer in Chinese.
               Your output MUST follow this two-part format, in this exact order:

               **Part 1 – Plain-text conclusion (for memory / multi-turn context)**
               Write a concise, structured Chinese summary WITHOUT any HTML tags. This part is what
               the model should use as memory in future turns. Example:

               核心结论：XXX指标同比增长20%，主要驱动因素为YYY。
               - 数据点A：具体数值及说明
               - 数据点B：具体数值及说明
               - 数据点C：具体数值及说明

               **Part 2 – Visual report (for frontend rendering only)**
               Immediately after the plain-text conclusion, append ONE `<report>` block with inline-styled HTML.
               The `<report>` block is for display only – do NOT reference it in future turns.
               Structure the report as follows (use ONLY these tags, no external CSS/JS):

               <report>
               <h3 style="text-align:center;font-weight:700;font-size:1rem;margin:0 0 12px;">报告标题</h3>
               <p><strong>核心结论：</strong>一句话总结。</p>
               <h4 style="font-weight:600;font-size:0.9rem;margin:10px 0 4px;">一、分项标题</h4>
               <p>正文内容，数据支撑，简明扼要。</p>
               <h4 style="font-weight:600;font-size:0.9rem;margin:10px 0 4px;">二、分项标题</h4>
               <p>正文内容。</p>
               </report>

               Rules for the report block:
               - `<h3>` is the main title: centered, bold – always present
               - `<h4>` for section headings: left-aligned, bold
               - `<p>` for body text; use `<strong>` for key metrics inline
               - Do NOT use `<div>`, `<span>`, `<table>`, classes, or external resources
               - Keep inline styles minimal and consistent with the examples above

               **Multi-turn memory rule**: In subsequent turns, when recalling previous answers,
               refer ONLY to the plain-text conclusion part. Ignore any `<report>` or `<chart>` blocks
               in history – they are display artifacts, not data.

            ## Chart Output Rules
            When the data is suitable for visualization (comparisons, trends, proportions, rankings), append ONE chart block at the end of your answer using this exact format:

            <chart>
            {
              "type": "bar",
              "title": "图表标题",
              "xLabel": "X轴标签（可选）",
              "yLabel": "Y轴标签（可选）",
              "data": [
                {"label": "类目A", "value": 100},
                {"label": "类目B", "value": 200}
              ]
            }
            </chart>

            - `type` must be one of: `bar` (柱状图), `line` (折线图), `pie` (饼图)
            - Use `bar` for comparisons/rankings, `line` for time-series trends, `pie` for proportions
            - Only output ONE chart block per answer, placed at the very end after the text analysis
            - Do NOT add chart if data has only one data point or is not suitable for visualization
            - The chart JSON must be valid and parseable

            ## Key Rules
            - **Stop early**: Do NOT continue querying once you have enough information.
            - **Be concise**: One sub-task per dataset per specific question. Do not create redundant sub-tasks.
            - **Handle missing data gracefully**: If a query returns empty or no results, note it and decide if other datasets can compensate.
            - **Always use Chinese for the final answer** unless the user asks in another language.
            """;

    private final DataApiClient dataApiClient;
    private final AnalysisPlanService analysisPlanService;
    private final SessionAgentManager sessionAgentManager;
    private final ChatSessionService chatSessionService;

    @Value("${openai.api-key:#{null}}")
    private String apiKeyFromConfig;

    @Value("${openai.base-url:#{null}}")
    private String baseUrlFromConfig;

    public DataAnalysisAgentService(
            DataApiClient dataApiClient,
            AnalysisPlanService analysisPlanService,
            SessionAgentManager sessionAgentManager,
            ChatSessionService chatSessionService) {
        this.dataApiClient = dataApiClient;
        this.analysisPlanService = analysisPlanService;
        this.sessionAgentManager = sessionAgentManager;
        this.chatSessionService = chatSessionService;
    }

    @Override
    public void afterPropertiesSet() {
        String apiKey = resolveApiKey();
        String baseUrl = resolveBaseUrl();
        sessionAgentManager.configure(apiKey, baseUrl, SYSTEM_PROMPT);
        log.info("DataAnalysisAgentService initialized");
    }

    /**
     * Send a user message to the session's agent and receive a streaming response.
     *
     * @param sessionId the chat session identifier
     * @param message   the user's question
     * @param userName  the user identifier (from URL param, used for session isolation)
     * @return Flux of streaming text chunks (SSE-friendly)
     */
    public Flux<String> chat(String sessionId, String message, String userName) {
        // Synchronous DB operations before streaming starts
        chatSessionService.ensureSession(sessionId, userName);
        chatSessionService.saveUserMessage(sessionId, message);
        SessionAgentManager.SessionEntry entry = sessionAgentManager.getOrCreate(sessionId);

        AtomicReference<StringBuilder> replyBuf = new AtomicReference<>(new StringBuilder());

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(message).build())
                        .build();

        return entry.agent.stream(userMsg, buildStreamOptions())
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        event -> {
                            String chunk = eventToString(event);
                            if (!chunk.isEmpty() && !"[STOPPED]".equals(chunk)) {
                                replyBuf.get().append(chunk);
                            }
                            return chunk;
                        })
                .filter(text -> !text.isEmpty())
                .doOnComplete(
                        () -> {
                            String fullReply = replyBuf.get().toString();
                            if (!fullReply.isBlank()) {
                                // Fire-and-forget: save to DB and trigger async compression
                                chatSessionService.saveAssistantMessage(sessionId, fullReply);
                                chatSessionService.maybeCompressAsync(sessionId);
                            }
                        })
                .doOnError(
                        err ->
                                log.error(
                                        "Chat stream error for session {}: {}",
                                        sessionId,
                                        err.getMessage()));
    }

    /**
     * Reset a specific session: evict from memory.
     */
    public void reset(String sessionId) {
        log.info("Resetting session: {}", sessionId);
        sessionAgentManager.evict(sessionId);
        analysisPlanService.broadcastPlanChange();
    }

    private String resolveApiKey() {
        if (apiKeyFromConfig != null && !apiKeyFromConfig.isBlank()) return apiKeyFromConfig;
        String envKey = System.getenv("OPENAI_API_KEY");
        if (envKey != null && !envKey.isBlank()) return envKey;
        throw new IllegalStateException(
                "OpenAI API key is required. Set 'openai.api-key' in application.yml"
                        + " or the OPENAI_API_KEY environment variable.");
    }

    private String resolveBaseUrl() {
        if (baseUrlFromConfig != null && !baseUrlFromConfig.isBlank()) return baseUrlFromConfig;
        String envUrl = System.getenv("OPENAI_BASE_URL");
        if (envUrl != null && !envUrl.isBlank()) return envUrl;
        return null;
    }

    private StreamOptions buildStreamOptions() {
        return StreamOptions.builder()
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                .incremental(true)
                .build();
    }

    private String eventToString(Event event) {
        if (event.getType() == EventType.AGENT_RESULT) {
            Msg msg = event.getMessage();
            if (msg != null && msg.getGenerateReason() == GenerateReason.ACTING_STOP_REQUESTED) {
                return "[STOPPED]";
            }
            return "";
        }
        if (event.isLast()) return "";
        List<TextBlock> blocks = event.getMessage().getContentBlocks(TextBlock.class);
        return blocks.isEmpty() ? "" : blocks.get(0).getText();
    }
}
