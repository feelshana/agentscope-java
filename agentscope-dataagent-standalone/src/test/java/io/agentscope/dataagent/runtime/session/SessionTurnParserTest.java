package io.agentscope.dataagent.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SessionTurnParserTest {
    @org.junit.jupiter.api.Test
    void frameworkSummaryIsNotAUserTurnButOrdinarySummaryWordingIsPreserved() {
        String jsonl =
                """
                {"type":"message","id":"human","role":"USER","content":"You are in the middle of a conversation that has been summarized."}
                {"type":"message","id":"__compaction_summary__:generated","role":"USER","content":"private context"}
                {"type":"message","id":"reserved-name","name":"__compaction_summary__","role":"USER","content":"private context"}
                {"type":"message","id":"reply","role":"ASSISTANT","content":"已完成"}
                """;
        var turns = SessionTurnParser.parse(jsonl);
        org.assertj.core.api.Assertions.assertThat(turns).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(turns.get(0).id()).isEqualTo("human");
        org.assertj.core.api.Assertions.assertThat(turns.get(1).id()).isEqualTo("reply");
    }

    @Test
    void unwrapsJsonStringEnvelopeFromPersistedToolResult() throws Exception {
        String receipt =
                "[WREN_QUERY_RECEIPT]{\"groupId\":\"g\",\"question\":\"去年哪个月营收最多\",\"sql\":\"SELECT"
                        + " 1\"}";
        ObjectMapper mapper = new ObjectMapper();
        String persistedOutput = mapper.writeValueAsString(mapper.writeValueAsString(receipt));
        String jsonl =
                "{\"type\":\"tool_result\",\"id\":\"r1\",\"name\":\"wren_call_tool\",\"toolCallId\":\"c1\",\"output\":"
                        + persistedOutput
                        + "}";

        var turns = SessionTurnParser.parse(jsonl);

        assertEquals(receipt, turns.get(0).toolResult());
    }
}
