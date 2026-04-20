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

/**
 * Represents a single chunk in the stream output.
 *
 * <p>Each chunk has a sequence number for ordering and deduplication,
 * content, type, and timestamp. This structure enables:
 *
 * <ul>
 *   <li>Ordered replay of historical chunks</li>
 *   <li>Deduplication on client reconnect</li>
 *   <li>Different rendering strategies for different chunk types</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamChunk {

    /** Sequence number, starting from 0, monotonically increasing. */
    private final int seq;

    /** The content of this chunk. */
    private final String content;

    /** The type of this chunk. */
    private final StreamChunkType type;

    /** Timestamp when this chunk was created. */
    private final long timestamp;

    /** Whether this chunk is historical (replayed) or live. Set during resume. */
    private Boolean isHistory;

    public StreamChunk(int seq, String content, StreamChunkType type, long timestamp) {
        this.seq = seq;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp;
    }

    /**
     * Creates a text chunk with the current timestamp.
     */
    public static StreamChunk text(int seq, String content) {
        return new StreamChunk(seq, content, StreamChunkType.TEXT, System.currentTimeMillis());
    }

    /**
     * Creates a tool status chunk.
     */
    public static StreamChunk toolStatus(int seq, String content) {
        return new StreamChunk(
                seq, content, StreamChunkType.TOOL_STATUS, System.currentTimeMillis());
    }

    /**
     * Creates a plan update chunk.
     */
    public static StreamChunk planUpdate(int seq, String content) {
        return new StreamChunk(
                seq, content, StreamChunkType.PLAN_UPDATE, System.currentTimeMillis());
    }

    /**
     * Creates a done chunk.
     */
    public static StreamChunk done(int seq) {
        return new StreamChunk(seq, "", StreamChunkType.DONE, System.currentTimeMillis());
    }

    /**
     * Creates an error chunk.
     */
    public static StreamChunk error(int seq, String message) {
        return new StreamChunk(seq, message, StreamChunkType.ERROR, System.currentTimeMillis());
    }

    /**
     * Marks this chunk as historical (for client-side rendering optimization).
     */
    public StreamChunk markAsHistory() {
        this.isHistory = true;
        return this;
    }

    // Getters
    public int getSeq() {
        return seq;
    }

    public String getContent() {
        return content;
    }

    public StreamChunkType getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Boolean getIsHistory() {
        return isHistory;
    }

    @Override
    public String toString() {
        return "StreamChunk{"
                + "seq="
                + seq
                + ", type="
                + type
                + ", content='"
                + (content != null && content.length() > 50
                        ? content.substring(0, 50) + "..."
                        : content)
                + '\''
                + ", timestamp="
                + timestamp
                + ", isHistory="
                + isHistory
                + '}';
    }
}
