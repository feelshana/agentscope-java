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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.dataanalysis.client.DataApiClient;
import io.agentscope.examples.dataanalysis.tool.DataAnalysisTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
 * Core service that manages the data analysis ReActAgent lifecycle.
 *
 * <p>The agent follows a ReAct loop:
 *
 * <ol>
 *   <li>Receive user question.
 *   <li>Call {@code list_datasets} to understand available data sources.
 *   <li>Create a plan (via PlanNotebook) and decompose into sub-tasks.
 *   <li>Execute each sub-task by calling {@code query_dataset(datasetId, question)}.
 *   <li>Analyze the returned data – if the result satisfies the user's requirement, immediately
 *       call {@code finish_plan} and produce the final answer. Otherwise, add more sub-tasks to
 *       cover missing data.
 * </ol>
 */
@Service
public class DataAnalysisAgentService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisAgentService.class);

    private final DataApiClient dataApiClient;
    private final AnalysisPlanService analysisPlanService;

    @Value("${openai.api-key:#{null}}")
    private String apiKeyFromConfig;

    @Value("${openai.base-url:#{null}}")
    private String baseUrlFromConfig;

    @Value("${agent.system-prompt-file:prompt-V3.txt}")
    private String systemPromptFile;

    private ReActAgent agent;
    private InMemoryMemory memory;

    public DataAnalysisAgentService(
            DataApiClient dataApiClient, AnalysisPlanService analysisPlanService) {
        this.dataApiClient = dataApiClient;
        this.analysisPlanService = analysisPlanService;
    }

    @Override
    public void afterPropertiesSet() {
        String apiKey = resolveApiKey();
        initializeAgent(apiKey);
        log.info("DataAnalysisAgentService initialized successfully");
    }

    private String resolveApiKey() {
        // Priority: config file > environment variable
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
        // Priority: config file > environment variable
        if (baseUrlFromConfig != null && !baseUrlFromConfig.isBlank()) {
            return baseUrlFromConfig;
        }
        String envUrl = System.getenv("OPENAI_BASE_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            return envUrl;
        }
        // Default to official OpenAI endpoint
        return null;
    }

    private void initializeAgent(String apiKey) {
        memory = new InMemoryMemory();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DataAnalysisTool(dataApiClient));

        PlanNotebook planNotebook = PlanNotebook.builder().build();
        analysisPlanService.setPlanNotebook(planNotebook);
        planNotebook.addChangeHook(
                "planBroadcast", (notebook, plan) -> analysisPlanService.broadcastPlanChange());

        String baseUrl = resolveBaseUrl();
        OpenAIChatModel.Builder modelBuilder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("deepseek-chat").stream(true)
                        .formatter(new OpenAIChatFormatter());
        if (baseUrl != null) {
            modelBuilder.baseUrl(baseUrl);
        }

        agent =
                ReActAgent.builder()
                        .name("DataAnalysisAgent")
                        .sysPrompt(loadSystemPrompt())
                        .model(modelBuilder.build())
                        .memory(memory)
                        .toolkit(toolkit)
                        .planNotebook(planNotebook)
                        .maxIters(40)
                        .build();
    }

    /**
     * Load system prompt from classpath file configured by {@code agent.system-prompt-file}.
     * The file must be placed under {@code resources/prompts/}.
     */
    private String loadSystemPrompt() {
        String path = "prompts/" + systemPromptFile;
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法加载系统提示词文件: " + path
                            + "，请检查 agent.system-prompt-file 配置及 resources/prompts/ 目录",
                    e);
        }
    }

    /**
     * Send a user message to the agent and receive a streaming response.
     *
     * @param message the user's question or analysis request
     * @return Flux of streaming text chunks (SSE-friendly)
     */
    public Flux<String> chat(String message) {
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(message).build())
                        .build();

        return agent.stream(userMsg, buildStreamOptions())
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::eventToString)
                .filter(text -> !text.isEmpty());
    }

    /**
     * Reset the agent – clears memory and re-creates a fresh agent instance.
     */
    public void reset() {
        log.info("Resetting DataAnalysisAgent");
        String apiKey = resolveApiKey();
        initializeAgent(apiKey);
        dataApiClient.resetChatSessions();
        analysisPlanService.broadcastPlanChange();
        log.info("DataAnalysisAgent reset complete");
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
        if (event.isLast()) {
            return "";
        }
        List<TextBlock> blocks = event.getMessage().getContentBlocks(TextBlock.class);
        return blocks.isEmpty() ? "" : blocks.get(0).getText();
    }
}
