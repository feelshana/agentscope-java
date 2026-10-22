package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowEntity;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FactTableWindowServiceTest {
    private final DatasetRepository datasets = mock(DatasetRepository.class);
    private final FactQueryWindowRepository windows = mock(FactQueryWindowRepository.class);
    private final FactTableWindowService service = new FactTableWindowService(datasets, windows);

    private DatasetEntity dataset(String group) {
        var entity = new DatasetEntity();
        entity.setId("d");
        entity.setGroupId(group);
        entity.setName("订单");
        entity.setTableName("orders");
        entity.setOrigin("datasource");
        entity.setSampleSummaryJson(
                """
                {"version":1,"status":"OK","tableRole":"FACT","columns":[
                {"name":"下单日期","values":[],"dateFormat":"yyyy-MM-dd"},
                {"name":"支付日期","values":[],"dateFormat":"yyyy-MM-dd"}]}
                """);
        when(datasets.lockById("d")).thenReturn(Optional.of(entity));
        return entity;
    }

    @Test
    void savesChosenColumnWithoutAnyModelingOrPublicationDependency() {
        dataset("g");
        when(windows.findByGroupIdAndDatasetId("g", "d")).thenReturn(Optional.empty());
        var saved = service.save("alice", "g", "d", "LIMITED", 31, "支付日期");
        assertThat(saved.timeColumn()).isEqualTo("支付日期");
        assertThat(saved.tableName()).isEqualTo("订单");
    }

    @Test
    void validatesSelectionAndMissingDays() {
        dataset("g");
        assertThatThrownBy(() -> service.save("alice", "g", "d", "LIMITED", null, "支付日期"))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> service.save("alice", "g", "d", "LIMITED", 31, "错误字段"))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> service.save("alice", "g", "d", "LIMITED", 31, null))
                .isInstanceOf(DatasetException.class);
        verifyNoInteractions(windows);
    }

    @Test
    void rejectsCrossKnowledgeBaseConfiguration() {
        dataset("other");
        assertThatThrownBy(() -> service.save("alice", "g", "d", "LIMITED", 31, "支付日期"))
                .isInstanceOf(DatasetException.class);
        verifyNoInteractions(windows);
    }

    @Test
    void dimensionWithDateFieldsIsNotAWindowCandidate() {
        var dimension = dataset("g");
        dimension.setSampleSummaryJson(
                dimension.getSampleSummaryJson().replace("FACT", "DIMENSION"));
        when(datasets.findByGroupId("g")).thenReturn(List.of(dimension));
        assertThat(service.candidates("g")).isEmpty();
        assertThatThrownBy(() -> service.save("alice", "g", "d", "LIMITED", 31, "支付日期"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("事实表");
        verifyNoInteractions(windows);
    }

    @Test
    void factAndUnknownAreReportedSeparatelyWithoutReadingMdl() {
        var fact = dataset("g");
        var unknown = new DatasetEntity();
        unknown.setId("u");
        unknown.setGroupId("g");
        unknown.setName("未知表");
        unknown.setOrigin("datasource");
        unknown.setSampleSummaryJson(fact.getSampleSummaryJson().replace("FACT", "UNKNOWN"));
        when(datasets.findByGroupId("g")).thenReturn(List.of(fact, unknown));
        when(windows.findByGroupIdAndDatasetId("g", "d")).thenReturn(Optional.empty());
        when(windows.findByGroupIdAndDatasetId("g", "u")).thenReturn(Optional.empty());
        assertThat(service.candidates("g"))
                .extracting(FactTableWindowService.Candidate::tableRole)
                .containsExactly("FACT", "UNKNOWN");
    }

    @Test
    void confirmingDimensionRemovesPreviouslyRegisteredFactWindow() {
        var entity = dataset("g");
        var window =
                new FactQueryWindowEntity(
                        "w", "g", "d", "订单", "[\"支付日期\"]", "LIMITED", 31, "alice");
        when(windows.findByGroupIdAndDatasetId("g", "d")).thenReturn(Optional.of(window));
        service.confirmRole("alice", "g", "d", "DIMENSION");
        var summary = DatasetSampling.decode(entity.getSampleSummaryJson());
        assertThat(DatasetSampling.role(summary)).isEqualTo("DIMENSION");
        assertThat(summary.tableRoleConfirmed()).isTrue();
        verify(datasets).save(entity);
        verify(windows).delete(window);
    }

    @Test
    void roleConfirmationCannotWriteAnotherKnowledgeBase() {
        dataset("other");
        assertThatThrownBy(() -> service.confirmRole("alice", "g", "d", "FACT"))
                .isInstanceOf(DatasetException.class);
        verifyNoInteractions(windows);
    }

    @Test
    void revocationWhileWaitingForDatasetLockPreventsSharedRoleAndWindowWrites() {
        var permission = mock(KnowledgeBaseAccessService.class);
        var group =
                new io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity(
                        "g", "owner", "库", "");
        var secured = new FactTableWindowService(datasets, windows, permission);
        dataset("g");
        when(permission.requireManage("alice", "g"))
                .thenReturn(group)
                .thenThrow(new DatasetException("管理权限已撤销", 403));
        assertThatThrownBy(() -> secured.confirmRole("alice", "g", "d", "DIMENSION"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("撤销");
        org.mockito.Mockito.reset(permission);
        when(permission.requireManage("alice", "g"))
                .thenReturn(group)
                .thenThrow(new DatasetException("管理权限已撤销", 403));
        assertThatThrownBy(() -> secured.save("alice", "g", "d", "LIMITED", 31, "支付日期"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("撤销");
        verifyNoInteractions(windows);
    }
}
