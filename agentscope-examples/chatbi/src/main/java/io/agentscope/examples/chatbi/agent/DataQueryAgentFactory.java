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
package io.agentscope.examples.chatbi.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.chatbi.client.ReportDataApiClient;
import io.agentscope.examples.chatbi.client.SupersonicApiClient;
import io.agentscope.examples.chatbi.service.ChatBiPlanService;
import io.agentscope.examples.chatbi.service.ChatLogHook;
import io.agentscope.examples.chatbi.service.ConfirmPlanToHint;
import io.agentscope.examples.chatbi.service.ContextTrimHook;
import io.agentscope.examples.chatbi.service.DataQueryDatasetInjectionHook;
import io.agentscope.examples.chatbi.service.PerfTimingHook;
import io.agentscope.examples.chatbi.tool.DataInterpretTool;
import io.agentscope.examples.chatbi.tool.DataQueryAgentTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Factory for {@code DataQueryAgent} (intent: {@code da}).
 *
 * <p>Handles data querying intent: generates query plans, queries datasets,
 * returns data results and data interpretations.
 * Supports three sub-types: precise data retrieval, analytical insights, and chart data interpretation.
 */
@Component
public class DataQueryAgentFactory implements SubAgentFactory {

    private final SupersonicApiClient supersonicClient;
    private final ChatBiPlanService planService;
    private final ReportDataApiClient reportDataClient;

    private String sysPrompt;

    @Autowired
    public DataQueryAgentFactory(
            SupersonicApiClient supersonicClient,
            ChatBiPlanService planService,
            ReportDataApiClient reportDataClient) {
        this.supersonicClient = supersonicClient;
        this.planService = planService;
        this.reportDataClient = reportDataClient;
    }

    /** Called by {@code SessionAgentManager.configure()} after prompts are loaded. */
    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    @Override
    public ReActAgent create(AgentContext ctx) {
        DataQueryAgentTool dataQueryAgentTool =
                new DataQueryAgentTool(supersonicClient, ctx.agentId());
        DataInterpretTool dataInterpretTool =
                new DataInterpretTool(
                        ctx.chartParam(), ctx.reportId(), ctx.easyBiSession(), reportDataClient);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(dataQueryAgentTool);
        toolkit.registerTool(dataInterpretTool);

        // Configure PlanNotebook with user confirmation support
        ConfirmPlanToHint confirmPlanToHint = new ConfirmPlanToHint();
        PlanNotebook planNotebook =
                PlanNotebook.builder().planToHint(confirmPlanToHint).needUserConfirm(true).build();
        // Register the abandoned-plan detector
        confirmPlanToHint.registerWith(planNotebook);

        // Register PlanNotebook with the plan service for SSE broadcasting
        planService.registerPlanNotebook(ctx.sessionId(), planNotebook);
        // Add change hook to broadcast plan changes
        planNotebook.addChangeHook(
                "planBroadcast", (nb, plan) -> planService.broadcastPlanChange(ctx.sessionId()));

        DataQueryDatasetInjectionHook datasetInjectionHook =
                new DataQueryDatasetInjectionHook(supersonicClient, sysPrompt, ctx.agentId());

        return ReActAgent.builder()
                .name("DataQueryAgent")
                .description("处理问数(da)意图：生成查询计划、查询数据集、返回数据结果和数据解读。" + "支持精确取数、分析洞察和图表数据解读三种子类型。")
                .sysPrompt(sysPrompt)
                .model(ctx.streamModel())
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .planNotebook(planNotebook)
                .maxIters(20)
                .hook(new ContextTrimHook())
                .hook(datasetInjectionHook)
                .hook(confirmPlanToHint) // priority=50: runs before planHintHook(100)
                .hook(
                        new ChatLogHook(
                                ctx.sessionId() + "-da",
                                "【DataQueryAgent】-> 处理问数(da)意图：生成查询计划、查询数据集、返回数据结果和数据解读",
                                sysPrompt))
                .hook(new PerfTimingHook(ctx.sessionId() + "-da", "DataQueryAgent"))
                .build();
    }
}
