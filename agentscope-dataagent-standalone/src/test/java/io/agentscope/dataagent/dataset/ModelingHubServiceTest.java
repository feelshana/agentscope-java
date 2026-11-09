package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelingHubServiceTest {
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final KnowledgeBaseBrowseService browse = mock(KnowledgeBaseBrowseService.class);
    private final DraftReviewService draft = mock(DraftReviewService.class);
    private final ModelingConfirmationService confirmations =
            mock(ModelingConfirmationService.class);
    private final ModelingHubService hub =
            new ModelingHubService(access, browse, draft, confirmations);
    private final DatasetGroupEntity group = new DatasetGroupEntity("g", "owner", "Example", null);

    @Test
    void queryAccessGetsPublishedCountsOnlyAndPrivateGroupsAreOmitted() throws Exception {
        when(access.requireQuery("reader", "g")).thenReturn(group);
        when(access.requireQuery("reader", "private"))
                .thenThrow(new DatasetException("Denied", 404));
        when(access.role("reader", group)).thenReturn(KnowledgeBaseAccessService.Role.QUERY);
        var counts = new KnowledgeBaseBrowseService.Summary("g", 3, 1, 1);
        when(browse.publishedSummary(group)).thenReturn(counts);

        assertThat(hub.summaries("reader", List.of("g", "private", "g")))
                .containsExactly(new ModelingHubService.Summary("g", counts, null, null));
        verifyNoInteractions(draft, confirmations);
    }

    @Test
    void revokingManageDuringReadDropsDraftInformation() {
        when(access.requireQuery("member", "g")).thenReturn(group);
        when(access.role("member", group))
                .thenReturn(
                        KnowledgeBaseAccessService.Role.MANAGE,
                        KnowledgeBaseAccessService.Role.QUERY);
        when(draft.summary("member", "g")).thenReturn(new DraftReviewService.Summary(2, true));
        when(confirmations.list("member", "g")).thenReturn(List.of());

        assertThat(hub.summaries("member", List.of("g")))
                .containsExactly(new ModelingHubService.Summary("g", null, null, null));
    }

    @Test
    void rejectsBatchesLargerThanOnePage() {
        assertThatThrownBy(() -> hub.summaries("reader", Collections.nCopies(11, "g")))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("10");
        verifyNoInteractions(browse, draft, confirmations);
    }
}
