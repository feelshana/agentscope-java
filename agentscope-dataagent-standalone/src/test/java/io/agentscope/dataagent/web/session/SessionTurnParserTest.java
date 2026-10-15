package io.agentscope.dataagent.web.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SessionTurnParserTest {
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
