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
package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Locks the prompt view-catalog statement rendering (specs/020): the statement line must survive
 * as one folded, capped line so the view catalog layout and the rest of
 * [KNOWLEDGE_BASE_OVERVIEW] stay intact.
 */
class DatasetServiceTest {

    @Test
    void flattenStatementFoldsWhitespaceOntoOneLine() {
        String sql =
                "SELECT\n  user_segment,\n\tCOUNT(*)  AS cnt\nFROM 点击详情数据\nGROUP BY user_segment";

        assertThat(DatasetService.flattenStatement(sql))
                .isEqualTo(
                        "SELECT user_segment, COUNT(*) AS cnt FROM 点击详情数据 GROUP BY user_segment");
    }

    @Test
    void flattenStatementCapsLengthWithEllipsis() {
        String sql = "SELECT " + "a".repeat(600);

        String out = DatasetService.flattenStatement(sql);

        assertThat(out).hasSize(401).endsWith("…").startsWith("SELECT aaa");
    }

    @Test
    void flattenStatementSkipsBlankStatements() {
        assertThat(DatasetService.flattenStatement(null)).isEmpty();
        assertThat(DatasetService.flattenStatement("  \n\t ")).isEmpty();
    }
}
