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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.examples.chatbi.client.SupersonicApiClient;
import io.agentscope.examples.chatbi.dto.DatasetInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that dynamically injects the available dataset catalogue into the DataQueryAgent's
 * system message before each LLM reasoning step.
 *
 * <p>Mirrors the {@code DatasetInjectionHook} from the data-analysis module but uses
 * {@link SupersonicApiClient} instead of DataApiClient. Datasets are fetched asynchronously
 * on the first reasoning call and cached for the lifetime of the session.
 *
 * <p>Priority {@value #PRIORITY} — runs after ContextTrimHook (10) so the system message
 * is already trimmed before the dataset catalogue is appended.
 */
public class DataQueryDatasetInjectionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(DataQueryDatasetInjectionHook.class);

    static final int PRIORITY = 20;

    private final SupersonicApiClient supersonicClient;
    private final String baseSysPrompt;
    private final String agentId;

    /** Cached enriched system prompt (base + dataset catalogue). Null = not yet fetched. */
    private final AtomicReference<String> cachedSysPrompt = new AtomicReference<>(null);

    public DataQueryDatasetInjectionHook(
            SupersonicApiClient supersonicClient, String baseSysPrompt, String agentId) {
        this.supersonicClient = supersonicClient;
        this.baseSysPrompt = baseSysPrompt;
        this.agentId = agentId;
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

        String cached = cachedSysPrompt.get();
        if (cached != null) {
            injectSysPrompt(pre, cached);
            return Mono.just(event);
        }

        // First call: fetch datasets, register them, build and cache enriched prompt
        return supersonicClient
                .listDatasets(agentId)
                .doOnNext(
                        datasets ->
                                datasets.forEach(
                                        ds ->
                                                supersonicClient.registerDataset(
                                                        ds.getName(), ds.getAgentId())))
                .map(this::buildSysPrompt)
                .onErrorReturn(baseSysPrompt)
                .doOnNext(
                        sysPrompt -> {
                            cachedSysPrompt.set(sysPrompt);
                            log.info(
                                    "[DataQueryDatasetInjectionHook] Initialized DataQueryAgent"
                                            + " system prompt with dataset catalogue, agentId={}",
                                    agentId);
                        })
                .map(
                        sysPrompt -> {
                            injectSysPrompt(pre, sysPrompt);
                            return (T) pre;
                        });
    }

    // ─────────────────── Internal helpers ───────────────────

    private void injectSysPrompt(PreReasoningEvent event, String sysPrompt) {
        List<Msg> messages = event.getInputMessages();
        List<Msg> modified = new ArrayList<>(messages.size());

        if (!messages.isEmpty() && MsgRole.SYSTEM.equals(messages.get(0).getRole())) {
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

    private String buildSysPrompt(List<DatasetInfo> datasets) {
        StringBuilder sb = new StringBuilder(baseSysPrompt);

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
            sb.append("\n\n---\n").append("## Available Datasets (pre-loaded)\n").append(catalogue);
        }

        return sb.toString();
    }
}
