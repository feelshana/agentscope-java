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

import io.agentscope.examples.dataanalysis.mapper.SuggestedQuestionMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for retrieving suggested questions from the database.
 * Questions are managed directly via database and take effect immediately without redeployment.
 */
@Service
public class SuggestedQuestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestedQuestionService.class);

    private final SuggestedQuestionMapper suggestedQuestionMapper;

    public SuggestedQuestionService(SuggestedQuestionMapper suggestedQuestionMapper) {
        this.suggestedQuestionMapper = suggestedQuestionMapper;
    }

    /**
     * Returns the text of all enabled suggested questions, ordered by sort_order ascending.
     */
    public List<String> listEnabledQuestions() {
        try {
            return suggestedQuestionMapper.findAllEnabled().stream()
                    .map(q -> q.getQuestion())
                    .toList();
        } catch (Exception e) {
            log.error("Failed to load suggested questions from database", e);
            return List.of();
        }
    }
}
