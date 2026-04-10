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
import io.agentscope.examples.dataanalysis.client.DataApiClient;
import io.agentscope.examples.dataanalysis.dto.DatasetInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A Hook that dynamically injects the available dataset catalogue into the system message
 * before each LLM reasoning step.
 *
 * <p>This avoids calling {@code .block()} on a reactive {@link DataApiClient} during session
 * initialisation (which would throw {@link IllegalStateException} in Reactor NIO threads).
 * Instead, datasets are fetched asynchronously on the first reasoning call and cached for
 * subsequent calls within the same session.
 *
 * <p>Priority is set to {@value #PRIORITY} so it runs after {@link ContextTrimHook} (10)
 * and before the plan-hint hook (100), ensuring the system message is already trimmed before
 * the dataset catalogue is appended.
 */
public class DatasetInjectionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(DatasetInjectionHook.class);

    /** Run after ContextTrimHook (10) but before plan-hint hooks (100). */
    static final int PRIORITY = 20;

    private final DataApiClient dataApiClient;
    private final String baseSysPrompt;

    /**
     * Cache of the fully-built system prompt (base + dataset catalogue).
     * {@code null} means datasets have not been fetched yet.
     */
    private final AtomicReference<String> cachedSysPrompt = new AtomicReference<>(null);

    public DatasetInjectionHook(DataApiClient dataApiClient, String baseSysPrompt) {
        this.dataApiClient = dataApiClient;
        this.baseSysPrompt = baseSysPrompt;
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

        // If we already have a cached sys prompt, inject it synchronously.
        String cached = cachedSysPrompt.get();
        if (cached != null) {
            injectSysPrompt(pre, cached);
            return Mono.just(event);
        }

        // First call: fetch datasets reactively, cache the result, then inject.
        return dataApiClient
                .listDatasets()
                .doOnNext(dataApiClient::registerDatasets)
                .map(this::buildSysPrompt)
                .onErrorReturn(baseSysPrompt)
                .doOnNext(
                        sysPrompt -> {
                            cachedSysPrompt.compareAndSet(null, sysPrompt);
                            log.info(
                                    "[DatasetInjectionHook] Loaded and cached dataset catalogue"
                                            + " into sysPrompt");
                        })
                .map(
                        sysPrompt -> {
                            injectSysPrompt(pre, sysPrompt);
                            return (T) pre;
                        });
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
     * Append the dataset catalogue to the base system prompt.
     */
    private String buildSysPrompt(List<DatasetInfo> datasets) {
        if (datasets == null || datasets.isEmpty()) {
            return baseSysPrompt;
        }
        String catalogue =
                datasets.stream()
                        .map(
                                ds ->
                                        "  - Name: "
                                                + ds.getName()
                                                + "\n    Description: "
                                                + ds.getDescription())
                        .collect(Collectors.joining("\n"));
        return baseSysPrompt
                + "\n\n---\n"
                + "## Available Datasets (pre-loaded — no need to call list_datasets)\n"
                + catalogue;
    }
}
