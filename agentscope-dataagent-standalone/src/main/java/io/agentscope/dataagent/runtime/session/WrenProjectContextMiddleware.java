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
package io.agentscope.dataagent.runtime.session;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Injects project identity only; schema and business context are read by the official tools. */
public final class WrenProjectContextMiddleware implements HarnessRuntimeMiddleware {
    private final WrenProjectAccess access;

    public WrenProjectContextMiddleware(WrenProjectAccess access) {
        this.access = access;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.fromCallable(
                        () -> {
                            var project = access.resolve(context);
                            return (prompt == null ? "" : prompt)
                                    + "\n\n# 当前已授权工程\n知识库："
                                    + project.name()
                                    + "\n"
                                    + "草稿工程根目录：/project\n"
                                    + "用户已在页面选择此知识库。官方技能需要确认项目时，只能确认此工程，不能输入其他宿主路径。业务文档（如已上传）位于"
                                    + " raw/business-document.md；可选问题表格位于 knowledge/questions/。"
                                    + "先加载官方技能，再按其要求读取工程；当前工程为草稿，页面发布后才用于正式问数。";
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
