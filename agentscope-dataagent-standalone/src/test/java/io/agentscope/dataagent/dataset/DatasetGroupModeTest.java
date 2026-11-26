package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** spec149 P7-2 (ADR0156): a knowledge base's mode is chosen at creation, one of two values. */
class DatasetGroupModeTest {

    private final DatasetGroupRepository groups = mock(DatasetGroupRepository.class);
    private final DatasetGroupService service =
            new DatasetGroupService(
                    groups,
                    mock(DatasetRepository.class),
                    mock(DatasetKnowledgeRepository.class),
                    null,
                    null,
                    null,
                    null,
                    mock(KnowledgeBaseAccessService.class),
                    null,
                    null);

    @Test
    void modeIsChosenAtCreationAndDefaultsToNormal() {
        when(groups.findByOwnerIdAndName(any(), any())).thenReturn(Optional.empty());
        when(groups.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.createGroup("alice", "会员", "", "CALIBER").getKnowledgeMode())
                .isEqualTo(DatasetGroupEntity.MODE_CALIBER);
        assertThat(service.createGroup("alice", "订单", "", null).getKnowledgeMode())
                .isEqualTo(DatasetGroupEntity.MODE_NORMAL);
        assertThat(service.createGroup("alice", "旧接口", "").isCaliberMode()).isFalse();
        // A group created before modes existed (null) reads as an ordinary knowledge base.
        assertThat(new DatasetGroupEntity("g", "alice", "旧", "").getKnowledgeMode())
                .isEqualTo(DatasetGroupEntity.MODE_NORMAL);
    }

    @Test
    void unknownModesAreRefusedBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> service.createGroup("alice", "会员", "", "MIXED"))
                .hasMessageContaining("普通问数（NORMAL）或口径问数（CALIBER）");
        verify(groups, never()).save(any());
    }
}
