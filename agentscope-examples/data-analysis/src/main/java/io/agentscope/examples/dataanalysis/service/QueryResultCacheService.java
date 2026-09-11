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

import io.agentscope.examples.dataanalysis.entity.QueryResultCache;
import io.agentscope.examples.dataanalysis.mapper.QueryResultCacheMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing query_dataset result cache.
 *
 * <p>Stores all query results including empty results and errors for troubleshooting.
 * Key fields: session_id, dataset_name, question, query_result, user_name.
 */
@Service
public class QueryResultCacheService {

    private static final Logger log = LoggerFactory.getLogger(QueryResultCacheService.class);

    private final QueryResultCacheMapper cacheMapper;

    public QueryResultCacheService(QueryResultCacheMapper cacheMapper) {
        this.cacheMapper = cacheMapper;
    }

    /**
     * Store a query result in the cache.
     * Stores all results including empty and error results for troubleshooting.
     *
     * @param sessionId the chat session ID
     * @param datasetName the dataset that was queried
     * @param question the question parameter passed to query_dataset
     * @param result the query result (CSV format, empty, or error message)
     * @param userName the user name for data isolation
     * @return the generated cache record ID
     */
    @Transactional
    public Long storeQueryResult(
            String sessionId, String datasetName, String question, String result, String userName) {

        QueryResultCache cache = new QueryResultCache();
        cache.setSessionId(sessionId);
        cache.setDatasetName(datasetName);
        cache.setQuestion(question);
        cache.setQueryResult(result);
        cache.setUserName(userName == null ? "" : userName);
        cache.setCreatedAt(LocalDateTime.now());

        cacheMapper.insert(cache);
        Long cacheId = cache.getId();

        log.info(
                "[QueryResultCache] Stored result: id={}, session={}, dataset={}, user={}",
                cacheId,
                sessionId,
                datasetName,
                userName);

        return cacheId;
    }

    /**
     * Get all cached query results for a session.
     *
     * @param sessionId the chat session ID
     * @return list of cached results, ordered by created_at ascending
     */
    public List<QueryResultCache> getCachedResults(String sessionId) {
        return cacheMapper.findBySessionIdOrderByRound(sessionId);
    }

    /**
     * Get a specific cached result by ID.
     */
    public QueryResultCache getCachedResult(Long cacheId) {
        return cacheMapper.selectById(cacheId);
    }

    /**
     * Delete all cached results for a session.
     * Called when the session is reset/cleared.
     */
    @Transactional
    public void deleteBySessionId(String sessionId) {
        int deleted = cacheMapper.deleteBySessionId(sessionId);
        log.info("[QueryResultCache] Deleted {} cache records for session={}", deleted, sessionId);
    }

    /**
     * Get the CSV data for a specific cached result.
     * Used when the LLM decides to reuse historical data.
     *
     * @param cacheId the cache record ID
     * @return the query result, or null if not found
     */
    public String getCachedCsvData(Long cacheId) {
        QueryResultCache cache = cacheMapper.selectById(cacheId);
        return cache != null ? cache.getQueryResult() : null;
    }
}
