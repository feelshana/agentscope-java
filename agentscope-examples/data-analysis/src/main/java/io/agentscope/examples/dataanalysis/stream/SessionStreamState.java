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
package io.agentscope.examples.dataanalysis.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents the streaming state for a session.
 *
 * <p>This class holds:
 *
 * <ul>
 *   <li>The current generation status (RUNNING, COMPLETED, etc.)</li>
 *   <li>All chunks generated so far (for replay on reconnect)</li>
 *   <li>Metadata for the current turn (requestId, messageId, timestamps)</li>
 * </ul>
 *
 * <p>Thread-safe for concurrent access from generation and replay threads.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionStreamState {

    /** Session identifier. */
    private final String sessionId;

    /** Current request ID (for deduplication). */
    private volatile String requestId;

    /** Current generation status. */
    private volatile StreamStatus status;

    /** All chunks generated in this turn. Uses CopyOnWriteArrayList for thread safety. */
    private final CopyOnWriteArrayList<StreamChunk> chunks;

    /** Timestamp when this turn started. */
    private volatile long startedAt;

    /** Timestamp of the last update. */
    private volatile long updatedAt;

    /** Current draft message ID (for incremental DB updates). */
    private volatile Long messageId;

    /** Current sequence number for the next chunk. */
    private volatile int nextSeq;

    public SessionStreamState(String sessionId) {
        this.sessionId = sessionId;
        this.status = StreamStatus.IDLE;
        this.chunks = new CopyOnWriteArrayList<>();
        this.nextSeq = 0;
    }

    /**
     * Starts a new generation turn.
     *
     * @param requestId the request ID for this turn
     */
    public synchronized void startTurn(String requestId) {
        this.requestId = requestId;
        this.status = StreamStatus.RUNNING;
        this.startedAt = System.currentTimeMillis();
        this.updatedAt = startedAt;
        this.chunks.clear();
        this.nextSeq = 0;
        this.messageId = null;
    }

    /**
     * Adds a chunk to the state.
     *
     * @param chunk the chunk to add
     * @return the added chunk (with seq set if not already set)
     */
    public synchronized StreamChunk addChunk(StreamChunk chunk) {
        if (status != StreamStatus.RUNNING) {
            return null;
        }
        chunks.add(chunk);
        nextSeq++;
        updatedAt = System.currentTimeMillis();
        return chunk;
    }

    /**
     * Marks the current turn as completed.
     */
    public synchronized void completeTurn() {
        this.status = StreamStatus.COMPLETED;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Marks the current turn as failed.
     */
    public synchronized void failTurn() {
        this.status = StreamStatus.FAILED;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Resets the state to IDLE.
     */
    public synchronized void reset() {
        this.status = StreamStatus.IDLE;
        this.chunks.clear();
        this.requestId = null;
        this.messageId = null;
        this.nextSeq = 0;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Returns chunks from the specified sequence number (exclusive) onwards.
     *
     * @param fromSeq the sequence number to start from (exclusive)
     * @return list of chunks with seq > fromSeq
     */
    public List<StreamChunk> getChunksAfter(int fromSeq) {
        if (chunks.isEmpty() || fromSeq >= chunks.size()) {
            return Collections.emptyList();
        }
        // Chunks are ordered by seq, so we can use subList
        int startIdx = fromSeq + 1;
        if (startIdx >= chunks.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(chunks.subList(startIdx, chunks.size()));
    }

    /**
     * Returns the current chunk count.
     */
    public int getChunkCount() {
        return chunks.size();
    }

    /**
     * Returns the next sequence number to use.
     */
    public int getNextSeq() {
        return nextSeq;
    }

    // Getters
    public String getSessionId() {
        return sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public StreamStatus getStatus() {
        return status;
    }

    public List<StreamChunk> getChunks() {
        return Collections.unmodifiableList(chunks);
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    @Override
    public String toString() {
        return "SessionStreamState{"
                + "sessionId='"
                + sessionId
                + '\''
                + ", requestId='"
                + requestId
                + '\''
                + ", status="
                + status
                + ", chunkCount="
                + chunks.size()
                + ", nextSeq="
                + nextSeq
                + ", startedAt="
                + startedAt
                + '}';
    }
}
