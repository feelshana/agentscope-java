/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.MdlSuggestionService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermEntity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Locks the group-bound term API (specs/026): every endpoint runs the
 * {@link DatasetGroupService#getGroup} owner check first — the previous global dictionary had no
 * tenant checks at all — and CRUD delegates to the group-scoped service methods.
 */
class SemanticTermControllerTest {

    private static final String GROUP = "gA";

    private DatasetGroupService groupService;
    private MdlSuggestionService modelingService;
    private SemanticTermController controller;

    private final TestingAuthenticationToken alice = new TestingAuthenticationToken("alice", "n/a");

    @BeforeEach
    void setUp() {
        groupService = mock(DatasetGroupService.class);
        modelingService = mock(MdlSuggestionService.class);
        controller = new SemanticTermController(modelingService, groupService);
        when(groupService.getGroup("alice", GROUP)).thenReturn(new DatasetGroupEntity());
    }

    @Test
    void foreignGroupYields404OnEveryEndpoint() {
        when(groupService.getGroup(eq("bob"), any()))
                .thenThrow(new DatasetException("Knowledge base not found: " + GROUP, 404));
        TestingAuthenticationToken bob = new TestingAuthenticationToken("bob", "n/a");
        SemanticTermController.TermRequest req =
                new SemanticTermController.TermRequest("VIP", "高价值客户", null);

        List<Mono<?>> calls =
                List.of(
                        controller.list(GROUP, bob),
                        controller.create(GROUP, req, bob),
                        controller.update(GROUP, "t1", req, bob),
                        controller.delete(GROUP, "t1", bob));

        for (Mono<?> call : calls) {
            StepVerifier.create(call)
                    .expectErrorSatisfies(
                            error -> {
                                assertThat(error).isInstanceOf(ResponseStatusException.class);
                                assertThat(((ResponseStatusException) error).getStatusCode())
                                        .isEqualTo(HttpStatus.NOT_FOUND);
                            })
                    .verify();
        }
        verify(modelingService, never()).listTerms(any());
        verify(modelingService, never()).createTerm(any(), any(), any(), any());
        verify(modelingService, never()).deleteTerm(any(), any());
    }

    @Test
    void listDelegatesToGroupScopedService() {
        when(modelingService.listTerms(GROUP))
                .thenReturn(
                        List.of(new SemanticTermEntity("t1", GROUP, "VIP", "累计消费达到一万元的客户", "贵宾")));

        List<SemanticTermController.TermVO> out = controller.list(GROUP, alice).block();

        verify(groupService).getGroup("alice", GROUP);
        verify(modelingService).listTerms(GROUP);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).term()).isEqualTo("VIP");
        assertThat(out.get(0).synonyms()).isEqualTo("贵宾");
    }

    @Test
    void createTrimsTermAndDelegatesWithGroupId() {
        when(modelingService.createTerm(GROUP, "VIP", "高价值客户", null))
                .thenReturn(new SemanticTermEntity("t1", GROUP, "VIP", "高价值客户", null));

        SemanticTermController.TermVO out =
                controller
                        .create(
                                GROUP,
                                new SemanticTermController.TermRequest(" VIP ", "高价值客户", null),
                                alice)
                        .block();

        verify(modelingService).createTerm(GROUP, "VIP", "高价值客户", null);
        assertThat(out).isNotNull();
        assertThat(out.id()).isEqualTo("t1");
    }

    @Test
    void createRejectsOverlongInputsBeforeTouchingData() {
        SemanticTermController.TermRequest tooLong =
                new SemanticTermController.TermRequest("x".repeat(31), "解释", null);
        SemanticTermController.TermRequest longExplanation =
                new SemanticTermController.TermRequest("VIP", "y".repeat(101), null);

        StepVerifier.create(controller.create(GROUP, tooLong, alice))
                .expectErrorSatisfies(
                        error -> {
                            assertThat(error).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) error).getStatusCode())
                                    .isEqualTo(HttpStatus.BAD_REQUEST);
                        })
                .verify();
        StepVerifier.create(controller.create(GROUP, longExplanation, alice))
                .expectErrorSatisfies(
                        error -> {
                            assertThat(error).isInstanceOf(ResponseStatusException.class);
                            assertThat(((ResponseStatusException) error).getStatusCode())
                                    .isEqualTo(HttpStatus.BAD_REQUEST);
                        })
                .verify();
        verify(modelingService, never()).createTerm(any(), any(), any(), any());
    }

    @Test
    void updateReplacesWithinGroupBoundary() {
        when(modelingService.createTerm(GROUP, "VIP", "新口径", null))
                .thenReturn(new SemanticTermEntity("t2", GROUP, "VIP", "新口径", null));

        SemanticTermController.TermVO out =
                controller
                        .update(
                                GROUP,
                                "t1",
                                new SemanticTermController.TermRequest("VIP", "新口径", null),
                                alice)
                        .block();

        verify(modelingService).deleteTerm(GROUP, "t1");
        verify(modelingService).createTerm(GROUP, "VIP", "新口径", null);
        assertThat(out).isNotNull();
        assertThat(out.id()).isEqualTo("t2");
    }

    @Test
    void deleteDelegatesToGroupScopedService() {
        controller.delete(GROUP, "t1", alice).block();

        verify(groupService).getGroup("alice", GROUP);
        verify(modelingService).deleteTerm(GROUP, "t1");
    }
}
