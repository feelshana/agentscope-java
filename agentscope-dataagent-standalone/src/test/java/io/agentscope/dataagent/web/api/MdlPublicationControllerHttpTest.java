package io.agentscope.dataagent.web.api;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.dataagent.dataset.BaselineMdlService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.ModelingActivity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@Tag("integration")
class MdlPublicationControllerHttpTest {
    final DatasetGroupService groups = mock(DatasetGroupService.class);
    final MdlPublishService publisher = mock(MdlPublishService.class);
    final BaselineMdlService baseline = mock(BaselineMdlService.class);
    final ModelingActivity activity = mock(ModelingActivity.class);
    final WebTestClient client =
            WebTestClient.bindToController(
                            new MdlPublicationController(groups, publisher, baseline, activity))
                    .webFilter(
                            (exchange, chain) ->
                                    chain.filter(
                                            exchange.mutate()
                                                    .principal(
                                                            Mono.just(
                                                                    new UsernamePasswordAuthenticationToken(
                                                                            "alice", "ignored")))
                                                    .build()))
                    .build();

    @Test
    void allSixExistingUrlsRemainMappedToTheSameOperations() {
        for (String path : new String[] {"", "/compiled", "/view"})
            request(HttpMethod.GET, path, 200);
        for (String path : new String[] {"/validate", "/initialize", "/publish"})
            request(HttpMethod.POST, path, 200);
        verify(groups, org.mockito.Mockito.times(6)).getGroup("alice", "g1");
        verify(publisher).preview("g1");
        verify(publisher).compiled("g1");
        verify(publisher).view("g1");
        verify(publisher).validate("g1");
        verify(publisher).publish("g1");
        verify(baseline).publishAfterDatasetChange("alice", "g1");
        verify(activity).requireIdle("g1");
    }

    @Test
    void deniedKnowledgeBaseCannotReachPublisher() {
        doThrow(new DatasetException("denied", 403)).when(groups).getGroup("alice", "g1");
        request(HttpMethod.POST, "/publish", 403);
        verifyNoInteractions(publisher, baseline, activity);
    }

    @Test
    void ongoingModelingPreventsPublicationWithTheOriginalConflictStatus() {
        doThrow(new DatasetException("busy", 409)).when(activity).requireIdle("g1");
        request(HttpMethod.POST, "/publish", 409);
        verifyNoInteractions(publisher);
    }

    void request(HttpMethod method, String suffix, int status) {
        client.method(method)
                .uri("/api/dataset-groups/g1/modeling/mdl" + suffix)
                .exchange()
                .expectStatus()
                .isEqualTo(status);
    }
}
