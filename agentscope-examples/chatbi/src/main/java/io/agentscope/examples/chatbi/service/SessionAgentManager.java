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
package io.agentscope.examples.chatbi.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentTool;
import io.agentscope.examples.chatbi.agent.AgentContext;
import io.agentscope.examples.chatbi.agent.ChatAgentFactory;
import io.agentscope.examples.chatbi.agent.DataLineageAgentFactory;
import io.agentscope.examples.chatbi.agent.DataQueryAgentFactory;
import io.agentscope.examples.chatbi.agent.GuAgentFactory;
import io.agentscope.examples.chatbi.agent.KnowledgeAgentFactory;
import io.agentscope.examples.chatbi.agent.ReportQueryAgentFactory;
import io.agentscope.examples.chatbi.agent.ReportScheduleAgentFactory;
import io.agentscope.examples.chatbi.dto.ChatRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manages per-session RouterAgent + sub-Agent instances for ChatBI.
 *
 * <p>Architecture: RouterAgent (main) + 6 specialised sub-Agents registered as SubAgentTools.
 *
 * <ul>
 *   <li><b>DataQueryAgent</b> (da): mirrors data-analysis logic — PlanNotebook + DataQueryAgentTool
 *       (get_dataset_detail, query_dataset) + DataInterpretTool + DataQueryDatasetInjectionHook.
 *   <li><b>KnowledgeAgent</b> (in/bu): KnowledgeSearchTool via Confluence.
 *   <li><b>GuAgent</b> (gu): ConfluenceSearchTool + ConfluenceFilterHook + ConfluenceGetPageTool.
 *   <li><b>ReportQueryAgent</b> (re): ReportQueryTool via SuperSonic.
 *   <li><b>DataLineageAgent</b> (dl): DataLineageTool via SuperSonic.
 *   <li><b>ReportScheduleAgent</b> (cs): ReportScheduleTool via SuperSonic.
 *   <li><b>ChatAgent</b> (ot): no tools, plain LLM chat.
 * </ul>
 *
 * <p>RouterAgent holds the session memory and QueryRewriteHook. Sub-Agents are created fresh
 * for each session and registered into the RouterAgent's toolkit as SubAgentTools with
 * {@code forwardEvents=true} so their streaming output is forwarded to the frontend SSE.
 */
@Component
public class SessionAgentManager {

    private static final Logger log = LoggerFactory.getLogger(SessionAgentManager.class);

    private final Map<String, SessionEntry> agents = new ConcurrentHashMap<>();

    private final ChatSessionService chatSessionService;

    // Sub-agent factories (injected)
    private final DataQueryAgentFactory dataQueryAgentFactory;
    private final KnowledgeAgentFactory knowledgeAgentFactory;
    private final GuAgentFactory guAgentFactory;
    private final ReportQueryAgentFactory reportQueryAgentFactory;
    private final DataLineageAgentFactory dataLineageAgentFactory;
    private final ReportScheduleAgentFactory reportScheduleAgentFactory;
    private final ChatAgentFactory chatAgentFactory;

    // Configured at startup by ChatBiAgentService
    private String apiKey;
    private String baseUrl;
    private String routerSysPrompt;
    private String rewritePrompt;

    // Sub-agent prompts (stored for diagnostic logging)
    private String dataQuerySysPrompt;
    private String knowledgeSysPrompt;
    private String guSysPrompt;
    private String reportQuerySysPrompt;
    private String reportScheduleSysPrompt;
    private String chatSysPrompt;

    public SessionAgentManager(
            ChatSessionService chatSessionService,
            DataQueryAgentFactory dataQueryAgentFactory,
            KnowledgeAgentFactory knowledgeAgentFactory,
            GuAgentFactory guAgentFactory,
            ReportQueryAgentFactory reportQueryAgentFactory,
            DataLineageAgentFactory dataLineageAgentFactory,
            ReportScheduleAgentFactory reportScheduleAgentFactory,
            ChatAgentFactory chatAgentFactory) {
        this.chatSessionService = chatSessionService;
        this.dataQueryAgentFactory = dataQueryAgentFactory;
        this.knowledgeAgentFactory = knowledgeAgentFactory;
        this.guAgentFactory = guAgentFactory;
        this.reportQueryAgentFactory = reportQueryAgentFactory;
        this.dataLineageAgentFactory = dataLineageAgentFactory;
        this.reportScheduleAgentFactory = reportScheduleAgentFactory;
        this.chatAgentFactory = chatAgentFactory;
    }

    /** Called by ChatBiAgentService after Spring context is ready with LLM config and prompts. */
    public void configure(
            String apiKey,
            String baseUrl,
            String routerSysPrompt,
            String dataQuerySysPrompt,
            String knowledgeSysPrompt,
            String guSysPrompt,
            String reportQuerySysPrompt,
            String dataLineageSysPrompt,
            String reportScheduleSysPrompt,
            String chatSysPrompt,
            String rewritePrompt) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.routerSysPrompt = routerSysPrompt;
        this.rewritePrompt = rewritePrompt;

        // Store prompts locally for diagnostic logging in createEntry()
        this.dataQuerySysPrompt = dataQuerySysPrompt;
        this.knowledgeSysPrompt = knowledgeSysPrompt;
        this.guSysPrompt = guSysPrompt;
        this.reportQuerySysPrompt = reportQuerySysPrompt;
        this.reportScheduleSysPrompt = reportScheduleSysPrompt;
        this.chatSysPrompt = chatSysPrompt;

        // Configure sub-agent factories with their prompts
        dataQueryAgentFactory.setSysPrompt(dataQuerySysPrompt);
        knowledgeAgentFactory.setSysPrompt(knowledgeSysPrompt);
        guAgentFactory.setSysPrompt(guSysPrompt);
        reportQueryAgentFactory.setSysPrompt(reportQuerySysPrompt);
        // dataLineageAgentFactory does NOT use sysPrompt (uses final_prompt from tool result)
        reportScheduleAgentFactory.setSysPrompt(reportScheduleSysPrompt);
        chatAgentFactory.setSysPrompt(chatSysPrompt);

        OpenAIChatModel.Builder summaryBuilder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("deepseek-chat").stream(false)
                        .formatter(new OpenAIChatFormatter());
        if (baseUrl != null) {
            summaryBuilder.baseUrl(baseUrl);
        }
        chatSessionService.setSummaryModel(summaryBuilder.build());
    }

    /**
     * Kept for backward compatibility with ChatBiAgentService.configure(4-arg).
     * Routes to the new 11-arg configure with default sub-agent prompt values.
     */
    public void configure(String apiKey, String baseUrl, String sysPrompt, String rewritePrompt) {
        configure(
                apiKey,
                baseUrl,
                sysPrompt, // routerSysPrompt
                sysPrompt, // dataQuerySysPrompt (overridden by service if files exist)
                sysPrompt, // knowledgeSysPrompt
                sysPrompt, // guSysPrompt
                sysPrompt, // reportQuerySysPrompt
                sysPrompt, // dataLineageSysPrompt
                sysPrompt, // reportScheduleSysPrompt
                sysPrompt, // chatSysPrompt
                rewritePrompt);
    }

    /**
     * Get or create a RouterAgent session entry.
     */
    public SessionEntry getOrCreate(String sessionId, ChatRequest req) {
        if (agents.containsKey(sessionId)) {
            return agents.get(sessionId);
        }
        List<Msg> historyMsgs = chatSessionService.loadSessionMessages(sessionId);
        SessionEntry entry = createEntry(sessionId, req);
        if (!historyMsgs.isEmpty()) {
            log.info(
                    "Resuming ChatBI session {} with {} historical messages",
                    sessionId,
                    historyMsgs.size());
            preloadHistory(entry, historyMsgs);
        }
        agents.put(sessionId, entry);
        return entry;
    }

    /** Remove a session from the in-memory map. */
    public void evict(String sessionId) {
        agents.remove(sessionId);
        log.info("ChatBI session evicted from memory: {}", sessionId);
    }

    // ─────────────────── Internal helpers ───────────────────

    private SessionEntry createEntry(String sessionId, ChatRequest req) {
        InMemoryMemory routerMemory = new InMemoryMemory();

        // 自定义超时：120秒，不重试（减少长时间阻塞）
        ExecutionConfig modelExecConfig =
                ExecutionConfig.builder().timeout(Duration.ofSeconds(120)).maxAttempts(1).build();
        GenerateOptions rewriteExecOptions =
                GenerateOptions.builder().executionConfig(modelExecConfig).build();
        GenerateOptions streamExecOptions =
                GenerateOptions.builder().executionConfig(modelExecConfig).build();

        // ── Build streaming model for QueryRewriteHook (streaming to avoid long blocking) ──
        OpenAIChatModel.Builder rewriteModelBuilder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("glm-5").stream(true)
                        .generateOptions(rewriteExecOptions)
                        .formatter(new OpenAIChatFormatter());
        if (baseUrl != null) {
            rewriteModelBuilder.baseUrl(baseUrl);
        }
        OpenAIChatModel rewriteModel = rewriteModelBuilder.build();

        // ── Build streaming model (shared across all agents) ──
        OpenAIChatModel.Builder streamModelBuilder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("glm-5").stream(true)
                        .generateOptions(streamExecOptions)
                        .formatter(new OpenAIChatFormatter());
        if (baseUrl != null) {
            streamModelBuilder.baseUrl(baseUrl);
        }
        OpenAIChatModel streamModel = streamModelBuilder.build();

        // ── Build AgentContext for factories ──
        AgentContext ctx =
                new AgentContext(
                        sessionId,
                        streamModel,
                        rewriteModel,
                        req.getSupersonicToken(),
                        req.getAgentId(),
                        req.getReportId(),
                        req.getDashboardId(),
                        req.getParam(),
                        req.getProjectId(),
                        req.getEasyBiSession());

        // ─────────────────────────────────────────────────────
        // Create sub-Agents via factories
        // ─────────────────────────────────────────────────────
        ReActAgent dataQueryAgent = dataQueryAgentFactory.create(ctx);
        ReActAgent knowledgeAgent = knowledgeAgentFactory.create(ctx);
        ReActAgent guAgent = guAgentFactory.create(ctx);
        ReActAgent reportQueryAgent = reportQueryAgentFactory.create(ctx);
        ReActAgent dataLineageAgent = dataLineageAgentFactory.create(ctx);
        ReActAgent reportScheduleAgent = reportScheduleAgentFactory.create(ctx);
        ReActAgent chatAgent = chatAgentFactory.create(ctx);

        // ─────────────────────────────────────────────────────
        // RouterAgent — registers all sub-Agents as SubAgentTools
        // ─────────────────────────────────────────────────────
        SubAgentConfig forwardConfig =
                SubAgentConfig.builder()
                        .forwardEvents(true)
                        .streamOptions(
                                StreamOptions.builder()
                                        .eventTypes(
                                                EventType.REASONING,
                                                EventType.TOOL_RESULT,
                                                EventType.AGENT_RESULT)
                                        .incremental(true)
                                        .build())
                        .build();

        final ReActAgent finalDataQueryAgent = dataQueryAgent;
        final ReActAgent finalKnowledgeAgent = knowledgeAgent;
        final ReActAgent finalGuAgent = guAgent;
        final ReActAgent finalReportQueryAgent = reportQueryAgent;
        final ReActAgent finalDataLineageAgent = dataLineageAgent;
        final ReActAgent finalReportScheduleAgent = reportScheduleAgent;
        final ReActAgent finalChatAgent = chatAgent;

        Toolkit routerToolkit = new Toolkit();
        routerToolkit.registerTool(
                new SubAgentTool(
                        () -> finalDataQueryAgent,
                        SubAgentConfig.builder()
                                .toolName("call_data_query_agent")
                                .description(
                                        "调用问数Agent处理da意图：获取具体数据指标、数据解读分析、"
                                                + "数据归因。支持精确取数和深度分析两种模式。")
                                .forwardEvents(true)
                                .streamOptions(forwardConfig.getStreamOptions())
                                .build()));
        routerToolkit.registerTool(
                new SubAgentTool(
                        () -> finalKnowledgeAgent,
                        SubAgentConfig.builder()
                                .toolName("call_knowledge_agent")
                                .description("调用知识库Agent处理in/bu意图：指标口径定义、业务知识和系统知识检索。")
                                .forwardEvents(true)
                                .streamOptions(forwardConfig.getStreamOptions())
                                .build()));
        routerToolkit.registerTool(
                new SubAgentTool(
                        () -> finalGuAgent,
                        SubAgentConfig.builder()
                                .toolName("call_gu_agent")
                                .description(
                                        "调用工具使用Agent处理gu意图：大数据平台/红海分析云BI工具使用方法、权限申请、报表订阅等操作类问题。")
                                .forwardEvents(true)
                                .streamOptions(forwardConfig.getStreamOptions())
                                .build()));
        routerToolkit.registerTool(
                new SubAgentTool(
                        () -> finalReportQueryAgent,
                        SubAgentConfig.builder()
                                .toolName("call_report_query_agent")
                                .description("调用报表Agent处理re意图：根据用户需求推荐合适的报表、仪表盘或大屏。")
                                .forwardEvents(true)
                                .streamOptions(forwardConfig.getStreamOptions())
                                .build()));
        routerToolkit.registerTool(
                new SubAgentTool(
                        () -> finalDataLineageAgent,
                        SubAgentConfig.builder()
                                .toolName("call_data_lineage_agent")
                                .description("调用血缘Agent处理dl意图：查询数据血缘、上下游表依赖、工作流依赖关系。")
                                .forwardEvents(true)
                                .streamOptions(forwardConfig.getStreamOptions())
                                .build()));
        routerToolkit.registerTool(
                new SubAgentTool(
                        () -> finalReportScheduleAgent,
                        SubAgentConfig.builder()
                                .toolName("call_report_schedule_agent")
                                .description("调用出数Agent处理cs意图：查询报表或仪表盘的数据刷新时间、出数时间点。")
                                .forwardEvents(true)
                                .streamOptions(forwardConfig.getStreamOptions())
                                .build()));
        routerToolkit.registerTool(
                new SubAgentTool(
                        () -> finalChatAgent,
                        SubAgentConfig.builder()
                                .toolName("call_chat_agent")
                                .description("调用闲聊Agent处理ot意图：日常对话、通识问答，无需查询任何数据。")
                                .forwardEvents(true)
                                .streamOptions(forwardConfig.getStreamOptions())
                                .build()));

        ReActAgent routerAgent =
                ReActAgent.builder()
                        .name("RouterAgent-" + sessionId)
                        .sysPrompt(routerSysPrompt)
                        .model(streamModel)
                        .memory(routerMemory)
                        .toolkit(routerToolkit)
                        .maxIters(5) // Router only needs 1 routing decision
                        .hook(new SubAgentCompleteHook()) // Stop iteration after sub-agent
                        // completes
                        .hook(new QueryRewriteHook(rewriteModel, rewritePrompt)) // priority=5
                        .hook(new ContextTrimHook()) // priority=10
                        .hook(new ChatLogHook(sessionId, null, routerSysPrompt)) // priority=900
                        .hook(new PerfTimingHook(sessionId, "RouterAgent")) // priority=950
                        .build();

        log.info(
                "Created ChatBI RouterAgent+6 sub-Agents for session={}, agentId={}, reportId={}",
                sessionId,
                req.getAgentId(),
                req.getReportId());
        return new SessionEntry(routerAgent, routerMemory);
    }

    private void preloadHistory(SessionEntry entry, List<Msg> historyMsgs) {
        for (Msg msg : historyMsgs) {
            entry.memory.addMessage(msg);
        }
    }

    // ─────────────────── Session entry ───────────────────

    /** Holds the RouterAgent + Memory for one session. */
    public static class SessionEntry {
        public final ReActAgent agent;
        public final InMemoryMemory memory;

        public SessionEntry(ReActAgent agent, InMemoryMemory memory) {
            this.agent = agent;
            this.memory = memory;
        }
    }
}
