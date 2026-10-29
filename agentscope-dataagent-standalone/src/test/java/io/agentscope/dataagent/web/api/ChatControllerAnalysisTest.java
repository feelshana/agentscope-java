package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** spec102: how a query request carries the user's analysis choice. */
class ChatControllerAnalysisTest {

    private static ChatController.ChatRequest request(String method, Map<String, Object> params) {
        return new ChatController.ChatRequest(
                "问题", "conv", List.of("g"), false, null, method, params);
    }

    @Test
    void onlyBuiltInMethodsBecomeAnAnalysisTurn() {
        assertThat(ChatController.analysisTurn(request(null, null))).isNull();
        assertThat(ChatController.analysisTurn(request("usage", null))).isNull();
        var pending = ChatController.analysisTurn(request("attribution", null));
        assertThat(pending.method()).isEqualTo("attribution");
        assertThat(pending.confirmed()).isFalse();
    }

    @Test
    void confirmedParametersAreKeptAsJson() {
        var turn =
                ChatController.analysisTurn(
                        request("forecast", Map.of("metric", "访问次数", "horizon", 7)));
        assertThat(turn.confirmed()).isTrue();
        assertThat(turn.confirmedParams()).contains("\"metric\":\"访问次数\"", "\"horizon\":7");
        assertThatThrownBy(
                        () ->
                                ChatController.analysisTurn(
                                        request("forecast", Map.of("x", "长".repeat(5000)))))
                .hasMessageContaining("分析参数过长");
    }

    /** Existing pages send no analysis fields; the body must still bind (Jackson 3). */
    @Test
    void requestsWithoutAnalysisFieldsStillBind() {
        var req =
                JsonMapper.builder()
                        .build()
                        .readValue(
                                "{\"message\":\"9月访问量\",\"sessionKey\":\"c\",\"groupIds\":[\"g\"]}",
                                ChatController.ChatRequest.class);
        assertThat(req.analysis()).isNull();
        assertThat(ChatController.analysisTurn(req)).isNull();
    }
}
