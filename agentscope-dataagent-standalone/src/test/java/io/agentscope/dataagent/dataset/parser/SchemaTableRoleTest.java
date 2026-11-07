package io.agentscope.dataagent.dataset.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SchemaTableRoleTest {
    @Test
    void roleIsReturnedByTheExistingSchemaModelCall() {
        for (String role : List.of("FACT", "DIMENSION", "UNKNOWN", "not-a-role")) {
            Model model = mock(Model.class);
            String json =
                    "{\"columns\":[{\"original\":\"id\",\"english\":\"id\",\"displayName\":\"记录编号\",\"description\":\"标识\"}],\"tableRole\":\""
                            + role
                            + "\"}";
            when(model.stream(anyList(), any(), any()))
                    .thenReturn(
                            Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(TextBlock.builder().text(json).build()))
                                            .build()));
            var result =
                    new SchemaGenerationService(Optional.of(model))
                            .generateSchema(List.of("id"), Map.of());
            assertThat(result.tableRole()).isEqualTo(role.equals("not-a-role") ? "UNKNOWN" : role);
            assertThat(result.columns().get(0).displayName()).isEqualTo("记录编号");
            verify(model, times(1)).stream(anyList(), any(), any());
        }
    }

    @Test
    void fallbackDoesNotGuessFactFromDateColumn() {
        var result =
                new SchemaGenerationService(Optional.empty())
                        .generateSchema(
                                List.of("customer_id", "register_time", "地区"),
                                Map.of(),
                                Map.of("customer_id", "客户编号，客户的唯一标识"),
                                null);
        assertThat(result.tableRole()).isEqualTo("UNKNOWN");
        assertThat(result.columns())
                .extracting(SchemaGenerationService.ColumnInfo::displayName)
                .containsExactly("客户编号", null, "地区");
    }
}
