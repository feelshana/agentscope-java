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

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ModelException;
import io.agentscope.examples.chatbi.dto.ChatRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Core service that manages the ChatBI multi-Agent lifecycle.
 *
 * <p>Architecture: RouterAgent + 6 specialised sub-Agents.
 * Delegates to {@link SessionAgentManager} for per-session Agent isolation.
 *
 * <p>Each chat invocation:
 * <ol>
 *   <li>Ensures session exists in DB.</li>
 *   <li>Gets or creates the per-session RouterAgent (with historical context pre-loaded).</li>
 *   <li>Saves the user message to DB.</li>
 *   <li>Streams the RouterAgent response (SSE-friendly).</li>
 *   <li>Saves the complete assistant response to DB (fire-and-forget).</li>
 *   <li>Checks if history needs summarization (async).</li>
 * </ol>
 *
 * <p>Intent routing is done by the RouterAgent LLM based on the router system prompt rules.
 * Each sub-Agent handles its specific domain with dedicated tools.
 */
@Service
public class ChatBiAgentService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ChatBiAgentService.class);

    private final SessionAgentManager sessionAgentManager;
    private final ChatSessionService chatSessionService;
    private final ChatBiPlanService planService;

    @Value("${openai.api-key:#{null}}")
    private String apiKeyFromConfig;

    @Value("${openai.base-url:#{null}}")
    private String baseUrlFromConfig;

    @Value("${agent.system-prompt-file:router-system-prompt.txt}")
    private String systemPromptFile;

    @Value("${agent.data-query-prompt-file:data-query-agent-prompt.txt}")
    private String dataQueryPromptFile;

    @Value("${agent.knowledge-prompt-file:knowledge-agent-prompt.txt}")
    private String knowledgePromptFile;

    @Value("${agent.gu-prompt-file:gu-agent-prompt.txt}")
    private String guPromptFile;

    @Value("${agent.report-query-prompt-file:report-query-agent-prompt.txt}")
    private String reportQueryPromptFile;

    @Value("${agent.data-lineage-prompt-file:data-lineage-agent-prompt.txt}")
    private String dataLineagePromptFile;

    @Value("${agent.report-schedule-prompt-file:report-schedule-agent-prompt.txt}")
    private String reportSchedulePromptFile;

    @Value("${agent.chat-prompt-file:chat-agent-prompt.txt}")
    private String chatPromptFile;

    @Value("${agent.query-rewrite-prompt-file:query-rewrite-prompt.txt}")
    private String queryRewritePromptFile;

    public ChatBiAgentService(
            SessionAgentManager sessionAgentManager,
            ChatSessionService chatSessionService,
            ChatBiPlanService planService) {
        this.sessionAgentManager = sessionAgentManager;
        this.chatSessionService = chatSessionService;
        this.planService = planService;
    }

    @Override
    public void afterPropertiesSet() {
        String apiKey = resolveApiKey();
        String baseUrl = resolveBaseUrl();

        String routerPrompt = loadPromptFile(systemPromptFile);
        String dataQueryPrompt = loadPromptFileSafe(dataQueryPromptFile, routerPrompt);
        String knowledgePrompt = loadPromptFileSafe(knowledgePromptFile, routerPrompt);
        String guPrompt = loadPromptFileSafe(guPromptFile, knowledgePrompt);
        String reportQueryPrompt = loadPromptFileSafe(reportQueryPromptFile, routerPrompt);
        String dataLineagePrompt = loadPromptFileSafe(dataLineagePromptFile, routerPrompt);
        String reportSchedulePrompt = loadPromptFileSafe(reportSchedulePromptFile, routerPrompt);
        String chatPrompt = loadPromptFileSafe(chatPromptFile, routerPrompt);
        String rewritePrompt = loadPromptFile(queryRewritePromptFile);
        sessionAgentManager.configure(
                apiKey,
                baseUrl,
                routerPrompt,
                dataQueryPrompt,
                knowledgePrompt,
                guPrompt,
                reportQueryPrompt,
                dataLineagePrompt,
                reportSchedulePrompt,
                chatPrompt,
                rewritePrompt);
        log.info(
                "ChatBiAgentService initialized, routerPromptFile={}, rewritePromptFile={}",
                systemPromptFile,
                queryRewritePromptFile);
    }

    /**
     * Send a user message to the session's agent and receive a streaming response.
     *
     * <p>The ChatRequest carries rich context (agentId, supersonicToken, reportId, etc.)
     * that is injected into the Tool instances at session creation time.
     *
     * @param req the full chat request including all context parameters
     * @return Flux of streaming text chunks (SSE-friendly)
     */
    public Flux<String> chat(ChatRequest req) {
        String sessionId = req.getSessionId();
        String message = req.getMessage();
        String userName = req.getUserName();

        // Ensure session exists in DB (before loading history)
        chatSessionService.ensureSession(sessionId, userName);

        // Get or create session Agent (preloads history if session exists in DB but not memory)
        SessionAgentManager.SessionEntry entry = sessionAgentManager.getOrCreate(sessionId, req);

        // Save user message AFTER getOrCreate to avoid double-loading the message
        chatSessionService.saveUserMessage(sessionId, message);

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
                            // Tool progress markers ([TOOL:xxx]) and thinking content ([THINKING]...)
                            // are streamed for real-time display but NOT included in the saved assistant reply
                            if (!chunk.isEmpty()
                                    && !"[STOPPED]".equals(chunk)
                                    && !chunk.startsWith("[TOOL:")
                                    && !chunk.startsWith("[THINKING]")) {
                                replyBuf.get().append(chunk);
                            }
                            return chunk;
                        })
                .filter(text -> !text.isEmpty())
                .onErrorResume(
                        err -> {
                            String errMsg;
                            if (ChatBiAgentService.isClientDisconnected(err)) {
                                // SSE 客户端主动断开（刷新/切页），静默丢弃，不推送错误
                                log.debug(
                                        "ChatBI stream cancelled by client for session {}",
                                        sessionId);
                                return Flux.empty();
                            } else if (ChatBiAgentService.isInterrupted(err)) {
                                errMsg = "⚠️ 请求被中断，请重新发送消息。";
                            } else if (err instanceof ModelException
                                    && err.getMessage() != null
                                    && err.getMessage().contains("timeout")) {
                                errMsg = "⚠️ 请求超时，当前AI服务响应较慢，请稍后重试。";
                            } else {
                                errMsg = "⚠️ 响应异常：" + err.getMessage();
                            }
                            log.error(
                                    "ChatBI stream error for session {}: {}",
                                    sessionId,
                                    err.getMessage(),
                                    err);
                            return Flux.just(errMsg);
                        })
                .doOnComplete(
                        () -> {
                            String fullReply = replyBuf.get().toString();
                            if (!fullReply.isBlank()) {
                                chatSessionService.saveAssistantMessage(sessionId, fullReply);
                                chatSessionService.maybeCompressAsync(sessionId);
                            }
                        });
    }

    /**
     * Reset a session: evict its Agent from memory so the next request creates a fresh one.
     * Does NOT delete DB history.
     */
    public void reset(String sessionId) {
        log.info("Resetting ChatBI session: {}", sessionId);
        sessionAgentManager.evict(sessionId);
        planService.unregisterPlanNotebook(sessionId);
    }

    // ─────────────────── Private helpers ───────────────────

    private String loadPromptFile(String fileName) {
        String path = "prompts/" + fileName;
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法加载提示词文件: " + path + "，请检查配置及 resources/prompts/ 目录", e);
        }
    }

    /**
     * Load a prompt file; if not found, fall back to {@code fallback} silently.
     * Sub-Agent prompt files are optional — a missing file falls back to the router prompt.
     */
    private String loadPromptFileSafe(String fileName, String fallback) {
        String path = "prompts/" + fileName;
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.warn("Sub-agent prompt file not found: {}, using fallback prompt", path);
                return fallback;
            }
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to load sub-agent prompt file: {}, using fallback", path);
            return fallback;
        }
    }

    private String resolveApiKey() {
        if (apiKeyFromConfig != null && !apiKeyFromConfig.isBlank()) {
            return apiKeyFromConfig;
        }
        String envKey = System.getenv("OPENAI_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }
        throw new IllegalStateException(
                "OpenAI API key is required. Set 'openai.api-key' in application.yml"
                        + " or the OPENAI_API_KEY environment variable.");
    }

    private String resolveBaseUrl() {
        if (baseUrlFromConfig != null && !baseUrlFromConfig.isBlank()) {
            return baseUrlFromConfig;
        }
        String envUrl = System.getenv("OPENAI_BASE_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            return envUrl;
        }
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
        // Tool result completion: emit progress marker for frontend real-time display
        if (event.getType() == EventType.TOOL_RESULT && event.isLast()) {
            List<ToolResultBlock> toolBlocks =
                    event.getMessage().getContentBlocks(ToolResultBlock.class);
            if (!toolBlocks.isEmpty()) {
                String toolName = toolBlocks.get(0).getName();
                if (toolName != null && !toolName.isBlank()) {
                    return "[TOOL:" + toolName + "]";
                }
            }
            return "";
        }
        if (event.isLast()) {
            return "";
        }
        // Extract thinking content from ThinkingBlock (reasoning_content)
        // Mark with [THINKING] prefix for frontend to distinguish from regular text
        List<ThinkingBlock> thinkingBlocks = event.getMessage().getContentBlocks(ThinkingBlock.class);
        if (!thinkingBlocks.isEmpty()) {
            String thinking = thinkingBlocks.get(0).getThinking();
            if (thinking != null && !thinking.isBlank()) {
                return "[THINKING]" + thinking;
            }
            return "";
        }
        // Extract text content from TextBlock (primary output content)
        List<TextBlock> textBlocks = event.getMessage().getContentBlocks(TextBlock.class);
        if (!textBlocks.isEmpty()) {
            return textBlocks.get(0).getText();
        }
        return "";
    }

    /**
     * Returns true when the error is caused by the SSE client disconnecting
     * (browser refresh / tab close).  In this case the Reactor pipeline is
     * cancelled and we should silently discard the error rather than pushing
     * an error chunk back into the already-closed stream.
     */
    private static boolean isClientDisconnected(Throwable err) {
        if (err instanceof CancellationException) {
            return true;
        }
        // Reactor wraps client-disconnect as a reactor.core.publisher.Operators$CancelException
        // or a plain RuntimeException whose message mentions "cancel".
        String msg = err.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("cancel") || lower.contains("connection reset")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true when the error is caused by an {@link InterruptedException} being
     * propagated up through the JDK HTTP transport.
     *
     * <p>This typically happens when:
     * <ul>
     *   <li>The 120-second {@link io.agentscope.core.model.ExecutionConfig} timeout fires
     *       and Reactor cancels the subscription, interrupting the blocking JDK send call.</li>
     *   <li>A concurrent task executing the HTTP request is cancelled externally.</li>
     * </ul>
     */
    private static boolean isInterrupted(Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            // ModelException / HttpTransportException message heuristic
            String msg = cause.getMessage();
            if (msg != null && msg.toLowerCase().contains("interrupted")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
