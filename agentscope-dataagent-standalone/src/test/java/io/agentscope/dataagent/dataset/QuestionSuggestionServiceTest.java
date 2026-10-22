package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.Model;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class QuestionSuggestionServiceTest {

    @Test
    void parseKeepsAtMostThreeNewDistinctQuestions() throws Exception {
        String raw = "说明文字 {\"questions\":[\"营收\",\"A\",\"A\",\"\",\"B\",\"C\",\"D\",\"E\",\"F\"]}";
        assertEquals(List.of("A", "B", "C"), QuestionSuggestionService.parse(raw, List.of("营收")));
    }

    @Test
    void parseToleratesMissingOrBrokenOutput() throws Exception {
        assertTrue(QuestionSuggestionService.parse(null, List.of()).isEmpty());
        assertTrue(QuestionSuggestionService.parse("无法回答", List.of()).isEmpty());
        assertTrue(QuestionSuggestionService.parse("{\"other\":1}", List.of()).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableModelOrReadFailureFallsBackToEmpty() {
        ObjectProvider<Model> none = mock(ObjectProvider.class);
        var reader = mock(MdlWorkspaceReader.class);
        var service =
                new QuestionSuggestionService(
                        none, reader, mock(MdlWorkspaceService.class), null, null);
        assertTrue(service.suggest("g1", List.of()).isEmpty());

        ObjectProvider<Model> some = mock(ObjectProvider.class);
        when(some.getIfAvailable()).thenReturn(mock(Model.class));
        when(reader.read("g1")).thenThrow(new DatasetException("broken", 500));
        var failing =
                new QuestionSuggestionService(
                        some, reader, mock(MdlWorkspaceService.class), null, null);
        assertTrue(failing.suggest("g1", List.of()).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sampledTimeRangesComeFromTheLimitedTablesOwnSamples() {
        var datasets = mock(io.agentscope.dataagent.web.persistence.jpa.DatasetRepository.class);
        var orders = new io.agentscope.dataagent.web.persistence.jpa.DatasetEntity();
        orders.setName("orders");
        orders.setSampleSummaryJson(
                DatasetSampling.encode(
                        new DatasetSampling.Summary(
                                1,
                                "OK",
                                "2026-10-21T00:00:00Z",
                                3,
                                1000,
                                "LIMIT",
                                List.of(
                                        new DatasetSampling.ColumnSample(
                                                "order_time",
                                                List.of(
                                                        "2025-02-10 15:00:00",
                                                        "2025-01-15 10:00:00",
                                                        "2025-02-08 11:30:00"),
                                                0,
                                                3,
                                                false,
                                                false),
                                        new DatasetSampling.ColumnSample(
                                                "amount", List.of("99"), 0, 1, false, false)))));
        var customers = new io.agentscope.dataagent.web.persistence.jpa.DatasetEntity();
        customers.setName("customers");
        when(datasets.findByGroupId("g1")).thenReturn(List.of(orders, customers));
        var service =
                new QuestionSuggestionService(
                        mock(ObjectProvider.class),
                        mock(MdlWorkspaceReader.class),
                        mock(MdlWorkspaceService.class),
                        null,
                        datasets);
        var ranges =
                service.sampledRanges(
                        "g1", List.of(new SqlWindowGate.Rule("orders", List.of("order_time"), 31)));
        assertEquals(
                java.util.Map.of(
                        "orders",
                        java.util.Map.of(
                                "order_time",
                                List.of("2025-01-15 10:00:00", "2025-02-10 15:00:00"))),
                ranges);
        assertTrue(service.sampledRanges("g1", List.of()).isEmpty());
    }
}
