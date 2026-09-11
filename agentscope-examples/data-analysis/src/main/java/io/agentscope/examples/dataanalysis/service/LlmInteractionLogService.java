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

import io.agentscope.examples.dataanalysis.entity.LlmInteractionLog;
import io.agentscope.examples.dataanalysis.mapper.LlmInteractionLogMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Service for persisting LLM interaction logs to MySQL.
 *
 * <p>All writes are fire-and-forget via {@link Async} so they never block the reactive pipeline.
 */
@Service
public class LlmInteractionLogService {

    private static final Logger log = LoggerFactory.getLogger(LlmInteractionLogService.class);

    private final LlmInteractionLogMapper mapper;

    public LlmInteractionLogService(LlmInteractionLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Persist one LLM interaction log record asynchronously.
     *
     * @param sessionId    chat session ID
     * @param iter         zero-based ReAct iteration index
     * @param userQuestion original user question for this session turn
     * @param messagesJson JSON array of messages sent to LLM
     * @param llmResponse  serialized LLM response (may be null if not yet available)
     */
    @Async
    public void save(
            String sessionId,
            int iter,
            String userQuestion,
            String messagesJson,
            String llmResponse) {
        try {
            LlmInteractionLog record = new LlmInteractionLog();
            record.setSessionId(sessionId);
            record.setIter(iter);
            record.setUserQuestion(userQuestion);
            record.setMessagesJson(messagesJson);
            record.setLlmResponse(llmResponse);
            record.setCreatedAt(LocalDateTime.now());
            mapper.insert(record);
        } catch (Exception e) {
            log.warn(
                    "Failed to persist LLM interaction log for session={} iter={}: {}",
                    sessionId,
                    iter,
                    e.getMessage());
        }
    }

    /**
     * Update the llm_response field of an existing log record by its primary key.
     * Called after PostReasoningEvent to fill in the LLM response.
     */
    @Async
    public void updateResponse(Long id, String llmResponse) {
        try {
            LlmInteractionLog record = new LlmInteractionLog();
            record.setId(id);
            record.setLlmResponse(llmResponse);
            mapper.updateById(record);
        } catch (Exception e) {
            log.warn("Failed to update LLM response for log id={}: {}", id, e.getMessage());
        }
    }
}
