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
package io.agentscope.dataagent.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guards the built-in default system prompt ({@code dataagent.agent.sys-prompt} default value):
 * the cross-table JOIN-first rules and the batch prepare guidance from ADR 0006 must stay
 * present unless the prompt is deliberately re-designed.
 */
class DataAgentConfigTest {

    @Test
    void defaultSysPromptContainsJoinFirstRules() {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("禁止把上一步查询结果作为字面量复制进 IN")
                .contains("JOIN")
                .contains("CTE")
                .contains("一个关联键对应多行")
                .contains("SELECT DISTINCT")
                .contains("防止扇出导致聚合值膨胀");
    }

    @Test
    void defaultSysPromptDescribesBatchPrepare() {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("一次性确认直接相关的 1–3 张表")
                .contains("tables 参数一次批量取回");
    }
}
