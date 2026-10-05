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
package io.agentscope.dataagent.web.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import reactor.core.publisher.Flux;

/**
 * Guards the transcript of direct model calls.
 *
 * <p>{@code AgentDraftService} bypasses the agent middleware chain, so its prompts and replies
 * (semantic-modeling AI suggestions, knowledge-graph extraction, agent drafts) would not reach any
 * transcript file unless written here. These tests pin the same {@code role / type / text} record
 * shape used by {@code DebugLoggingMiddleware}, and the split between the shared {@code LLM_DEBUG}
 * transcript and the dedicated modeling transcript ({@code LLM_MODELING_DEBUG}).
 */
class AgentDraftServiceTest {

    private static final String RECORD_SHAPE = "role: %s\ntype: text\ntext:\n%s\n";

    @Test
    void chatBlockingRecordsPromptAndReplyInLlmTranscript() {
        AgentDraftService service = serviceReplyingWith("回复内容");

        ListAppender<ILoggingEvent> appender = attachLogger("LLM_DEBUG");
        try {
            String reply = service.chatBlocking("请给出关系建议");

            assertThat(reply).isEqualTo("回复内容");
            assertThat(records(appender))
                    .containsExactly(record("USER", "请给出关系建议"), record("ASSISTANT", "回复内容"));
        } finally {
            detachLogger("LLM_DEBUG", appender);
        }
    }

    @Test
    void chatBlockingModelingTranscriptsToTheModelingLog() {
        AgentDraftService service = serviceReplyingWith("Cube 建议");

        ListAppender<ILoggingEvent> defaultLog = attachLogger("LLM_DEBUG");
        ListAppender<ILoggingEvent> modelingLog = attachLogger("LLM_MODELING_DEBUG");
        try {
            String reply = service.chatBlockingModeling("请给出 Cube 建议");

            assertThat(reply).isEqualTo("Cube 建议");
            assertThat(records(modelingLog))
                    .containsExactly(record("USER", "请给出 Cube 建议"), record("ASSISTANT", "Cube 建议"));
            assertThat(records(defaultLog))
                    .as("modeling transcripts must not leak into the shared LLM.log")
                    .isEmpty();
        } finally {
            detachLogger("LLM_DEBUG", defaultLog);
            detachLogger("LLM_MODELING_DEBUG", modelingLog);
        }
    }

    @Test
    void transcriptMatchesTheAgentMiddlewareRecordShape() {
        assertThat(AgentDraftService.transcript("USER", "你好")).isEqualTo(record("USER", "你好"));
        assertThat(AgentDraftService.transcript("ASSISTANT", null))
                .isEqualTo(record("ASSISTANT", ""));
    }

    private static AgentDraftService serviceReplyingWith(String text) {
        Model model = mock(Model.class);
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "id-1",
                                        List.of(TextBlock.builder().text(text).build()),
                                        null,
                                        Map.of(),
                                        "stop")));
        return new AgentDraftService(Optional.of(model), new DefaultResourceLoader());
    }

    private static ListAppender<ILoggingEvent> attachLogger(String name) {
        Logger logger = (Logger) LoggerFactory.getLogger(name);
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static List<String> records(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static void detachLogger(String name, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(name)).detachAppender(appender);
    }

    private static String record(String role, String text) {
        return String.format(RECORD_SHAPE, role, text);
    }
}
