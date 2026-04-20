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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manages stream states for all sessions in memory.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Thread-safe session state management using ConcurrentHashMap</li>
 *   <li>TTL-based cleanup: completed states are retained for 30 minutes</li>
 *   <li>Automatic background cleanup every 5 minutes</li>
 * </ul>
 *
 * <p>This class is the central point for the resume/replay mechanism:
 *
 * <ul>
 *   <li>During generation: chunks are appended to the state</li>
 *   <li>On reconnect: historical chunks can be retrieved</li>
 *   <li>After completion: state is retained for 30 minutes for late reconnections</li>
 * </ul>
 */
@Component
public class StreamStateManager {

    private static final Logger log = LoggerFactory.getLogger(StreamStateManager.class);

    /** TTL for completed states in milliseconds (30 minutes). */
    private static final long COMPLETED_STATE_TTL_MS = 30 * 60 * 1000L;

    /** Interval for cleanup task in milliseconds (5 minutes). */
    private static final long CLEANUP_INTERVAL_MS = 5 * 60 * 1000L;

    /** sessionId -> SessionStreamState */
    private final ConcurrentHashMap<String, SessionStreamState> states = new ConcurrentHashMap<>();

    /** Scheduled executor for TTL cleanup. */
    private ScheduledExecutorService cleanupExecutor;

    /**
     * Gets or creates the stream state for a session.
     *
     * @param sessionId the session ID
     * @return the stream state for this session
     */
    public SessionStreamState getOrCreate(String sessionId) {
        return states.computeIfAbsent(sessionId, SessionStreamState::new);
    }

    /**
     * Gets the stream state for a session if it exists.
     *
     * @param sessionId the session ID
     * @return the stream state, or null if not found
     */
    public SessionStreamState get(String sessionId) {
        return states.get(sessionId);
    }

    /**
     * Starts a new generation turn for a session.
     *
     * @param sessionId the session ID
     * @param requestId the request ID for this turn
     * @return the stream state
     */
    public SessionStreamState startTurn(String sessionId, String requestId) {
        SessionStreamState state = getOrCreate(sessionId);
        state.startTurn(requestId);
        log.debug("Started turn for session={}, requestId={}", sessionId, requestId);
        return state;
    }

    /**
     * Adds a chunk to a session's stream state.
     *
     * @param sessionId the session ID
     * @param chunk the chunk to add
     * @return the added chunk, or null if no active turn
     */
    public StreamChunk addChunk(String sessionId, StreamChunk chunk) {
        SessionStreamState state = states.get(sessionId);
        if (state == null) {
            log.warn("No stream state found for session={}", sessionId);
            return null;
        }
        return state.addChunk(chunk);
    }

    /**
     * Completes a turn for a session.
     *
     * @param sessionId the session ID
     */
    public void completeTurn(String sessionId) {
        SessionStreamState state = states.get(sessionId);
        if (state != null) {
            state.completeTurn();
            log.debug(
                    "Completed turn for session={}, requestId={}, chunkCount={}",
                    sessionId,
                    state.getRequestId(),
                    state.getChunkCount());
        }
    }

    /**
     * Fails a turn for a session.
     *
     * @param sessionId the session ID
     */
    public void failTurn(String sessionId) {
        SessionStreamState state = states.get(sessionId);
        if (state != null) {
            state.failTurn();
            log.debug("Failed turn for session={}, requestId={}", sessionId, state.getRequestId());
        }
    }

    /**
     * Resets (removes) the state for a session.
     *
     * @param sessionId the session ID
     */
    public void reset(String sessionId) {
        SessionStreamState state = states.remove(sessionId);
        if (state != null) {
            state.reset();
            log.debug("Reset stream state for session={}", sessionId);
        }
    }

    /**
     * Returns the stream status for a session.
     *
     * @param sessionId the session ID
     * @return the status, or IDLE if no state exists
     */
    public StreamStatus getStatus(String sessionId) {
        SessionStreamState state = states.get(sessionId);
        return state != null ? state.getStatus() : StreamStatus.IDLE;
    }

    /**
     * Initializes the cleanup scheduler.
     */
    @PostConstruct
    public void init() {
        cleanupExecutor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "stream-state-cleanup");
                            t.setDaemon(true);
                            return t;
                        });

        cleanupExecutor.scheduleAtFixedRate(
                this::cleanupExpiredStates,
                CLEANUP_INTERVAL_MS,
                CLEANUP_INTERVAL_MS,
                TimeUnit.MILLISECONDS);

        log.info(
                "StreamStateManager initialized with TTL={}min, cleanup interval={}min",
                COMPLETED_STATE_TTL_MS / 60000,
                CLEANUP_INTERVAL_MS / 60000);
    }

    /**
     * Shuts down the cleanup scheduler.
     */
    @PreDestroy
    public void destroy() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("StreamStateManager destroyed");
    }

    /**
     * Removes expired completed states.
     */
    private void cleanupExpiredStates() {
        long now = System.currentTimeMillis();
        long threshold = now - COMPLETED_STATE_TTL_MS;

        states.entrySet()
                .removeIf(
                        entry -> {
                            SessionStreamState state = entry.getValue();
                            if ((state.getStatus() == StreamStatus.COMPLETED
                                            || state.getStatus() == StreamStatus.FAILED)
                                    && state.getUpdatedAt() < threshold) {
                                log.debug(
                                        "Cleaning up expired state: session={}, status={},"
                                                + " age={}min",
                                        entry.getKey(),
                                        state.getStatus(),
                                        (now - state.getUpdatedAt()) / 60000);
                                return true;
                            }
                            return false;
                        });
    }

    /**
     * Returns the current number of active states (for monitoring).
     */
    public int getStateCount() {
        return states.size();
    }
}
