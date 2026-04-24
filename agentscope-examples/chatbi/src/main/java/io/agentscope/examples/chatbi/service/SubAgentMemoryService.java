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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.chatbi.entity.SubAgentMemory;
import io.agentscope.examples.chatbi.mapper.SubAgentMemoryMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the conversation memory for sub-agents (e.g. DataLineageAgent, ReportQueryAgent).
 *
 * <p>Uses a shared {@code data_lineage_memory} table with one row per session + type combination.
 * The {@code content} column stores the full conversation as a JSON array that is
 * <em>appended</em> on each round:
 * <pre>
 * [
 *   {"role":"user",   "text":"...", "time":"2026-04-14T06:29:30.936811"},
 *   {"role":"assistant","text":"...","time":"2026-04-14T06:29:30.936811"},
 *   // ... more rounds appended here
 * ]
 * </pre>
 *
 * <p>The type field distinguishes different agent memories:
 * <ul>
 *   <li>"dl" — DataLineageAgent (血缘查询)</li>
 *   <li>"re" — ReportQueryAgent (报表推荐)</li>
 * </ul>
 *
 * <p>The user message is appended when the tool is called; the assistant answer
 * is appended by the corresponding round-save hook after the LLM answers.
 */
@Service
public class SubAgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SubAgentMemoryService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final TypeReference<List<Map<String, String>>> LIST_TYPE =
            new TypeReference<>() {};

    private final SubAgentMemoryMapper memoryMapper;

    public SubAgentMemoryService(SubAgentMemoryMapper memoryMapper) {
        this.memoryMapper = memoryMapper;
    }

    // ─────────────────── Read ───────────────────

    /**
     * Load the current conversation history as a JSON string for the {@code memory} param.
     *
     * @param sessionId the session ID
     * @param type      the intent type (e.g. "dl", "re")
     * @return JSON array string of all previous rounds, or {@code null} if none
     */
    public String loadMemoryJson(String sessionId, String type) {
        SubAgentMemory record = memoryMapper.findBySessionIdAndType(sessionId, type);
        if (record == null || record.getContent() == null || record.getContent().isBlank()) {
            return null;
        }
        return record.getContent();
    }

    // ─────────────────── Write ───────────────────

    /**
     * Append the user message to the conversation JSON array (INSERT or UPDATE).
     * Called immediately when the tool is invoked.
     *
     * @param sessionId    the session ID
     * @param type         the intent type (e.g. "dl", "re")
     * @param userQuestion the user's question
     */
    @Transactional
    public void saveUserMessage(String sessionId, String type, String userQuestion) {
        appendMessage(sessionId, type, "user", userQuestion);
        log.debug(
                "[SubAgentMemoryService] Appended {} user message for session={}", type, sessionId);
    }

    /**
     * Append the assistant answer to the conversation JSON array.
     * Called by the round-save hook after the LLM generates its final answer.
     *
     * @param sessionId       the session ID
     * @param type            the intent type (e.g. "dl", "re")
     * @param assistantAnswer the LLM's final answer
     */
    @Transactional
    public void saveAssistantMessage(String sessionId, String type, String assistantAnswer) {
        appendMessage(sessionId, type, "assistant", assistantAnswer);
        log.debug(
                "[SubAgentMemoryService] Appended {} assistant message for session={}",
                type,
                sessionId);
    }

    // ─────────────────── Private helpers ───────────────────

    /**
     * Core append logic:
     * <ol>
     *   <li>Load existing row for the session+type (if any).</li>
     *   <li>Deserialize the JSON array; create empty list if first time.</li>
     *   <li>Append the new message entry.</li>
     *   <li>Serialize back and INSERT (first time) or UPDATE (subsequent).</li>
     * </ol>
     */
    private void appendMessage(String sessionId, String type, String role, String text) {
        SubAgentMemory record = memoryMapper.findBySessionIdAndType(sessionId, type);

        List<Map<String, String>> messages;
        if (record == null) {
            // First message for this session+type — start fresh
            messages = new ArrayList<>();
        } else {
            messages = parseContent(record.getContent(), sessionId, type);
        }

        // Build new entry
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("role", role);
        entry.put("text", text);
        entry.put("time", LocalDateTime.now().format(ISO_FORMATTER));
        messages.add(entry);

        String newContent;
        try {
            newContent = JSON.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            log.error(
                    "[SubAgentMemoryService] Failed to serialize messages for session={},"
                            + " type={}: {}",
                    sessionId,
                    type,
                    e.getMessage());
            return;
        }

        if (record == null) {
            // INSERT new row
            memoryMapper.insert(new SubAgentMemory(sessionId, type, newContent));
            log.info(
                    "[SubAgentMemoryService] Created memory record for session={}, type={}",
                    sessionId,
                    type);
        } else {
            // UPDATE existing row
            memoryMapper.updateContent(sessionId, type, newContent);
        }
    }

    /**
     * Safely parse the stored JSON string into a mutable list.
     * Falls back to an empty list on parse error.
     */
    private List<Map<String, String>> parseContent(String content, String sessionId, String type) {
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(content, LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn(
                    "[SubAgentMemoryService] Corrupted content for session={}, type={},"
                            + " resetting: {}",
                    sessionId,
                    type,
                    e.getMessage());
            return new ArrayList<>();
        }
    }
}
