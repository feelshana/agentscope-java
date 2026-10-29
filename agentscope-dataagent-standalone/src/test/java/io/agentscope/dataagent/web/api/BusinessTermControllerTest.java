package io.agentscope.dataagent.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BusinessTermControllerTest {

    /** The page adds a term without a {@code delete} flag; the body must still bind. */
    @Test
    void changeBindsWithoutDeleteFlag() {
        var change =
                JsonMapper.builder()
                        .build()
                        .readValue(
                                "{\"term\":\"净增用户\",\"synonyms\":\"\",\"explanation\":\"首次访问\"}",
                                BusinessTermController.Change.class);
        assertEquals("净增用户", change.term());
        assertNull(change.delete());
    }
}
