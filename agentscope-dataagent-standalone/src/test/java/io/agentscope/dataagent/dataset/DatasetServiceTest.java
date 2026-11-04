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

import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import java.util.ArrayList;
import java.util.List;
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

    /** ADR0135: date spans are measured once at upload and kept in the column description. */
    @Test
    void uploadWritesEachDateColumnsSpanIntoItsDescription() {
        List<String> asked = new ArrayList<>();
        var columns =
                DatasetService.withDateRanges(
                        List.of(
                                new ColumnSchema("order_time", "下单时间", "DATETIME", true, "下单时间"),
                                new ColumnSchema("paid_at", "支付日期", "DATE", true, ""),
                                new ColumnSchema("same_day", "当天", "DATE", true, "当天"),
                                new ColumnSchema("unread", "读不到", "DATETIME", true, "读不到"),
                                new ColumnSchema("amount", "金额", "DECIMAL(18,2)", true, "金额")),
                        column -> {
                            asked.add(column);
                            return switch (column) {
                                case "order_time" -> new String[] {"2026-05-01", "2026-10-31"};
                                case "paid_at" -> new String[] {"2026-05-02", "2026-11-01"};
                                case "same_day" -> new String[] {"2026-10-07", "2026-10-07"};
                                default -> null;
                            };
                        });
        assertThat(asked).containsExactly("order_time", "paid_at", "same_day", "unread");
        assertThat(columns.get(0).description())
                .isEqualTo("下单时间。上传数据覆盖 2026-05-01 至 2026-10-31（平台上传时统计）");
        assertThat(columns.get(1).description())
                .isEqualTo("上传数据覆盖 2026-05-02 至 2026-11-01（平台上传时统计）");
        assertThat(columns.get(2).description()).endsWith("覆盖 2026-10-07（平台上传时统计）");
        assertThat(columns.get(3).description()).isEqualTo("读不到");
        assertThat(columns.get(4).description()).isEqualTo("金额");
    }
}
