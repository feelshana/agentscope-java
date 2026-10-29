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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.BaselineMdlService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.DocEnhanceService;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.MdlSuggestionService;
import io.agentscope.dataagent.dataset.MdlWorkspaceService;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.tools.data.ModelingToolkit;
import io.agentscope.dataagent.tools.data.ModelingToolkitRegistrar;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DocEnhanceProposalEntity;
import io.agentscope.dataagent.web.persistence.jpa.DocEnhanceTaskEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticCubeEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Locks the modeling API shell: every endpoint resolves the group through
 * {@link DatasetGroupService#getGroup} first, so a foreign user's request yields 404 (specs/010
 * M1 acceptance #3) and a same-tenant overview assembles the group state the UI header shows.
 */
class SemanticModelingControllerTest {

    private DatasetGroupService groupService;
    private DatasetService datasetService;
    private MdlSuggestionService modelingService;
    private MdlPublishService mdlPublishService;
    private DocEnhanceService docEnhanceService;
    private WrenQueryGateway wrenGateway;
    private BaselineMdlService baselineMdlService;
    private MdlWorkspaceService workspace;
    private ModelingToolkitRegistrar registrar;
    private SemanticModelingController controller;

    private final TestingAuthenticationToken alice = new TestingAuthenticationToken("alice", "n/a");

    @BeforeEach
    void setUp() {
        groupService = mock(DatasetGroupService.class);
        datasetService = mock(DatasetService.class);
        modelingService = mock(MdlSuggestionService.class);
        mdlPublishService = mock(MdlPublishService.class);
        docEnhanceService = mock(DocEnhanceService.class);
        wrenGateway = mock(WrenQueryGateway.class);
        baselineMdlService = mock(BaselineMdlService.class);
        workspace = mock(MdlWorkspaceService.class);
        registrar = mock(ModelingToolkitRegistrar.class);
        when(registrar.toolkit()).thenReturn(mock(ModelingToolkit.class));
        controller =
                new SemanticModelingController(
                        groupService,
                        datasetService,
                        modelingService,
                        mdlPublishService,
                        docEnhanceService,
                        wrenGateway,
                        baselineMdlService,
                        workspace,
                        registrar,
                        new ObjectMapper());
    }

    @Test
    void foreignGroupYields404OnEveryEndpoint() {
        when(groupService.getGroup(eq("bob"), any()))
                .thenThrow(new DatasetException("Knowledge base not found: gA", 404));
        TestingAuthenticationToken bob = new TestingAuthenticationToken("bob", "n/a");
        SemanticModelingController.CubeRequest cubeReq =
                new SemanticModelingController.CubeRequest("n", "ds_a", null, null, null, null);

        List<Mono<?>> calls =
                List.of(
                        controller.overview("gA", bob),
                        controller.suggestRelations("gA", bob),
                        controller.suggestCubes("gA", bob),
                        controller.updateRelation(
                                "gA",
                                "r1",
                                new SemanticModelingController.RelationUpdateRequest(
                                        "CONFIRMED", null, false),
                                bob),
                        controller.addRelation(
                                "gA",
                                new SemanticModelingController.ManualRelationRequest(
                                        "ds_a", "x", "ds_b", "y", null),
                                bob),
                        controller.createCube("gA", cubeReq, bob),
                        controller.updateCube("gA", "c1", cubeReq, bob),
                        controller.deleteCube("gA", "c1", bob),
                        controller.triggerEnhance("gA", bob),
                        controller.enhanceOverview("gA", bob),
                        controller.adoptEnhanceProposal("gA", "p1", bob),
                        controller.ignoreEnhanceProposal("gA", "p1", bob),
                        controller.mdlPreview("gA", bob),
                        controller.mdlView("gA", bob),
                        controller.mdlValidate("gA", bob),
                        controller.mdlInitialize("gA", bob),
                        controller.mdlPublish("gA", bob),
                        controller.workspaceFile("gA", "models/orders.yml", bob),
                        controller.workspacePreview(
                                "gA",
                                new SemanticModelingController.WorkspacePreviewRequest(
                                        "write_file",
                                        Map.of("path", "models/orders.yml", "content", "x: 1")),
                                bob));

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
    }

    @Test
    void overviewAssemblesGroupStateAndDatasets() {
        DatasetGroupEntity group = new DatasetGroupEntity();
        group.setOwnerId("alice");
        group.setMdlState("PUBLISHED");
        group.setMdlVersion(2);
        group.setMdlPublishedAt(Instant.parse("2026-09-25T10:00:00Z"));

        DatasetEntity dataset = new DatasetEntity();
        dataset.setId("ds_a");
        dataset.setOwnerId("alice");
        dataset.setName("订单表");
        dataset.setTableName("ds_orders");

        when(groupService.getGroup("alice", "gA")).thenReturn(group);
        when(groupService.listDatasets("alice", "gA")).thenReturn(List.of(dataset));
        when(datasetService.readColumns(dataset))
                .thenReturn(List.of(new ColumnSchema("id", "订单id", "VARCHAR", true, "")));
        when(modelingService.listRelations("gA")).thenReturn(List.of());
        when(modelingService.listCubes("gA")).thenReturn(List.of());
        when(modelingService.listViews("gA")).thenReturn(List.of());

        SemanticModelingController.OverviewVO vo = controller.overview("gA", alice).block();

        assertThat(vo).isNotNull();
        assertThat(vo.group().mdlState()).isEqualTo("PUBLISHED");
        assertThat(vo.group().mdlVersion()).isEqualTo(2);
        assertThat(vo.group().mdlPublishedAt()).startsWith("2026-09-25T10:00:00");
        assertThat(vo.datasets()).hasSize(1);
        assertThat(vo.datasets().get(0).name()).isEqualTo("订单表");
        assertThat(vo.datasets().get(0).columns()).hasSize(1);
        assertThat(vo.relations()).isEmpty();
        assertThat(vo.cubes()).isEmpty();
        verify(groupService).getGroup("alice", "gA");
    }

    @Test
    void enhanceEndpointsDelegateAndMapLatestTask() {
        DatasetGroupEntity group = new DatasetGroupEntity();
        group.setOwnerId("alice");
        when(groupService.getGroup("alice", "gA")).thenReturn(group);

        DocEnhanceTaskEntity task =
                new DocEnhanceTaskEntity("t1", "alice", "gA", "UPLOAD", "业务口径.md");
        task.setStatus(DocEnhanceTaskEntity.STATUS_READY);
        DocEnhanceProposalEntity proposal = new DocEnhanceProposalEntity();
        proposal.setId("p1");
        proposal.setTaskId("t1");
        proposal.setOwnerId("alice");
        proposal.setGroupId("gA");
        proposal.setProposalType("BUSINESS_RULE");
        proposal.setClassification("NEW");
        proposal.setTitle("有效订单");
        proposal.setPayloadJson("{\"name\":\"有效订单\",\"content\":\"排除已删除订单\"}");
        proposal.setFingerprint("fp1");
        when(docEnhanceService.triggerAnalyze("alice", "gA")).thenReturn(task);
        when(docEnhanceService.latestTask("alice", "gA")).thenReturn(Optional.of(task));
        when(docEnhanceService.latestProposals("alice", "gA")).thenReturn(List.of(proposal));
        when(docEnhanceService.adopt("alice", "gA", "p1")).thenReturn(proposal);
        when(docEnhanceService.ignore("alice", "gA", "p1")).thenReturn(proposal);

        SemanticModelingController.EnhanceTaskVO started =
                controller.triggerEnhance("gA", alice).block();
        SemanticModelingController.EnhanceOverviewVO overview =
                controller.enhanceOverview("gA", alice).block();
        SemanticModelingController.EnhanceProposalVO adopted =
                controller.adoptEnhanceProposal("gA", "p1", alice).block();
        SemanticModelingController.EnhanceProposalVO ignored =
                controller.ignoreEnhanceProposal("gA", "p1", alice).block();

        assertThat(started).isNotNull();
        assertThat(started.status()).isEqualTo("READY");
        assertThat(overview).isNotNull();
        assertThat(overview.proposals()).hasSize(1);
        assertThat(overview.proposals().get(0).payload().get("name")).isEqualTo("有效订单");
        assertThat(adopted.type()).isEqualTo("BUSINESS_RULE");
        assertThat(ignored.id()).isEqualTo("p1");
        verify(docEnhanceService).triggerAnalyze("alice", "gA");
        verify(docEnhanceService).adopt("alice", "gA", "p1");
        verify(docEnhanceService).ignore("alice", "gA", "p1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"overview", "adopt", "ignore"})
    void proposalHttpResponsesKeepBusinessJsonWithJackson3(String operation) throws Exception {
        DatasetGroupEntity group = new DatasetGroupEntity();
        group.setOwnerId("alice");
        when(groupService.getGroup("alice", "gA")).thenReturn(group);
        DocEnhanceProposalEntity relationship =
                proposal(
                        "p1",
                        "RELATIONSHIP",
                        """
                        {"sourceDatasetId":"ds_orders","sourceColumns":["customer_id"],
                         "targetDatasetId":"ds_customers","targetColumns":["id"],
                         "joinType":"MANY_TO_ONE","metadata":{"reviewed":true,"score":0.95,
                         "optional":null,"keys":["customer_id",{"name":"id"}]}}
                        """);
        List<DocEnhanceProposalEntity> proposals =
                List.of(
                        relationship,
                        proposal(
                                "p2",
                                "TERM",
                                """
                                {"term":"客户","explanation":"订单所属客户","synonyms":"顾客,买家"}
                                """),
                        proposal(
                                "p3",
                                "BUSINESS_RULE",
                                """
                                {"name":"有效销售","content":"排除\\\"已取消\\\"\\n保留退款"}
                                """),
                        proposal(
                                "p4",
                                "VIEW",
                                """
                                {"name":"销售明细","baseDatasetId":null,
                                 "sqlText":"SELECT * FROM orders WHERE status <> '已取消'","description":"明细视图"}
                                """));
        when(docEnhanceService.latestTask("alice", "gA"))
                .thenReturn(
                        Optional.of(
                                new DocEnhanceTaskEntity(
                                        "t1", "alice", "gA", "UPLOAD", "业务规则.md")));
        when(docEnhanceService.latestProposals("alice", "gA")).thenReturn(proposals);
        when(docEnhanceService.adopt("alice", "gA", "p1")).thenReturn(relationship);
        when(docEnhanceService.ignore("alice", "gA", "p1")).thenReturn(relationship);

        WebTestClient client = jackson3HttpClient();
        WebTestClient.ResponseSpec response =
                "overview".equals(operation)
                        ? client.get().uri("/api/dataset-groups/gA/modeling/enhance").exchange()
                        : client.post()
                                .uri(
                                        "/api/dataset-groups/gA/modeling/enhance/proposals/p1/"
                                                + operation)
                                .exchange();
        byte[] body =
                response.expectStatus()
                        .isOk()
                        .expectHeader()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                        .expectBody()
                        .returnResult()
                        .getResponseBody();
        ObjectMapper reader = new ObjectMapper();
        var root = reader.readTree(body);
        if ("overview".equals(operation)) {
            assertThat(root.path("proposals").size()).isEqualTo(proposals.size());
            for (int i = 0; i < proposals.size(); i++) {
                assertThat(root.path("proposals").get(i).path("payload"))
                        .isEqualTo(reader.readTree(proposals.get(i).getPayloadJson()));
            }
        } else {
            assertThat(root.path("payload"))
                    .isEqualTo(reader.readTree(relationship.getPayloadJson()));
        }
        verify(groupService).getGroup("alice", "gA");
    }

    @Test
    void cubeOverviewHttpResponseKeepsNestedArraysWithJackson3() throws Exception {
        DatasetGroupEntity group = new DatasetGroupEntity();
        group.setOwnerId("alice");
        when(groupService.getGroup("alice", "gA")).thenReturn(group);
        when(groupService.listDatasets("alice", "gA")).thenReturn(List.of());
        when(modelingService.listRelations("gA")).thenReturn(List.of());
        when(modelingService.listViews("gA")).thenReturn(List.of());
        SemanticCubeEntity cube = new SemanticCubeEntity("c1", "gA", "销售额", "ds_orders", "销售指标");
        cube.setMeasuresJson(
                """
                [{"name":"销售额","column":"amount","agg":"sum","options":{"enabled":true,"scale":2}}]
                """);
        cube.setDimensionsJson(
                """
                [{"name":"渠道","column":"channel","values":["小程序","商城"],"description":null}]
                """);
        cube.setTimeDimensionsJson(
                """
                [{"column":"order_date","granularities":["day","month"]}]
                """);
        when(modelingService.listCubes("gA")).thenReturn(List.of(cube));
        WebTestClient client = jackson3HttpClient();
        WebTestClient.ResponseSpec response =
                client.get().uri("/api/dataset-groups/gA/modeling").exchange();
        byte[] body = response.expectStatus().isOk().expectBody().returnResult().getResponseBody();
        ObjectMapper reader = new ObjectMapper();
        var root = reader.readTree(body);
        var returned = root.path("cubes").get(0);
        assertThat(returned.path("measures")).isEqualTo(reader.readTree(cube.getMeasuresJson()));
        assertThat(returned.path("dimensions"))
                .isEqualTo(reader.readTree(cube.getDimensionsJson()));
        assertThat(returned.path("timeDimensions"))
                .isEqualTo(reader.readTree(cube.getTimeDimensionsJson()));
        verify(groupService).getGroup("alice", "gA");
    }

    private WebTestClient jackson3HttpClient() {
        return WebTestClient.bindToController(controller)
                .httpMessageCodecs(
                        codecs ->
                                codecs.defaultCodecs()
                                        .jacksonJsonEncoder(
                                                new JacksonJsonEncoder(
                                                        JsonMapper.builder().build())))
                .webFilter(
                        (exchange, chain) ->
                                chain.filter(exchange.mutate().principal(Mono.just(alice)).build()))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "null", "not-json"})
    void missingOrInvalidStoredJsonReturnsEmptyContainers(String json) throws Exception {
        DatasetGroupEntity group = new DatasetGroupEntity();
        group.setOwnerId("alice");
        when(groupService.getGroup("alice", "gA")).thenReturn(group);
        when(groupService.listDatasets("alice", "gA")).thenReturn(List.of());
        when(modelingService.listRelations("gA")).thenReturn(List.of());
        when(modelingService.listViews("gA")).thenReturn(List.of());
        SemanticCubeEntity cube = new SemanticCubeEntity("c1", "gA", "销售额", "ds_orders", null);
        cube.setMeasuresJson(json);
        cube.setDimensionsJson(json);
        cube.setTimeDimensionsJson(json);
        when(modelingService.listCubes("gA")).thenReturn(List.of(cube));
        when(docEnhanceService.adopt("alice", "gA", "p1")).thenReturn(proposal("p1", "TERM", json));
        WebTestClient client = jackson3HttpClient();
        ObjectMapper reader = new ObjectMapper();
        byte[] cubeBody =
                client.get()
                        .uri("/api/dataset-groups/gA/modeling")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult()
                        .getResponseBody();
        var returned = reader.readTree(cubeBody).path("cubes").get(0);
        for (String field : List.of("measures", "dimensions", "timeDimensions")) {
            assertThat(returned.path(field).isArray()).isTrue();
            assertThat(returned.path(field).isEmpty()).isTrue();
        }
        byte[] proposalBody =
                client.post()
                        .uri("/api/dataset-groups/gA/modeling/enhance/proposals/p1/adopt")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult()
                        .getResponseBody();
        assertThat(reader.readTree(proposalBody).path("payload")).isEqualTo(reader.readTree("{}"));
    }

    private static DocEnhanceProposalEntity proposal(String id, String type, String json) {
        DocEnhanceProposalEntity proposal = new DocEnhanceProposalEntity();
        proposal.setId(id);
        proposal.setProposalType(type);
        proposal.setClassification("NEW");
        proposal.setPayloadJson(json);
        return proposal;
    }

    @Test
    void mdlEndpointsDelegateForOwnedGroup() {
        DatasetGroupEntity group = new DatasetGroupEntity();
        group.setOwnerId("alice");
        when(groupService.getGroup("alice", "gA")).thenReturn(group);

        MdlPublishService.MdlIssue issue =
                new MdlPublishService.MdlIssue("warning", "models > 订单表: 缺少描述", "wren");
        MdlPublishService.MdlPreview preview =
                new MdlPublishService.MdlPreview(
                        List.of(
                                new MdlPublishService.MdlFile(
                                        "wren_project.yml", "schema_version: 5\n")),
                        List.of(),
                        List.of(issue),
                        "PUBLISHED",
                        2,
                        "2026-09-25T10:00:00Z",
                        true);
        MdlPublishService.MdlValidation validation =
                new MdlPublishService.MdlValidation(
                        true, List.of(), "0 warning(s), 0 error(s)", preview.files());
        MdlPublishService.MdlPublishResult result =
                new MdlPublishService.MdlPublishResult(
                        true, List.of(), "build ok", "PUBLISHED", 3, "2026-09-26T10:00:00Z");
        when(mdlPublishService.preview("gA")).thenReturn(preview);
        when(mdlPublishService.validate("gA")).thenReturn(validation);
        when(mdlPublishService.publish("gA")).thenReturn(result);

        MdlPublishService.MdlPreview gotPreview = controller.mdlPreview("gA", alice).block();
        MdlPublishService.MdlValidation gotValidation = controller.mdlValidate("gA", alice).block();
        MdlPublishService.MdlPublishResult gotPublish = controller.mdlPublish("gA", alice).block();

        assertThat(gotPreview).isEqualTo(preview);
        assertThat(gotPreview.files()).hasSize(1);
        assertThat(gotPreview.issues()).hasSize(1);
        assertThat(gotValidation.ok()).isTrue();
        assertThat(gotPublish.mdlState()).isEqualTo("PUBLISHED");
        assertThat(gotPublish.mdlVersion()).isEqualTo(3);
        verify(groupService, times(3)).getGroup("alice", "gA");
        // 发布 = 重建实例 (ADR 0018 D8): a successful publish drops the group's wren instance.
        verify(wrenGateway, org.mockito.Mockito.never()).invalidate("gA");
    }

    @Test
    void mdlViewReturnsDraftAndChangedForOwnedGroup() {
        DatasetGroupEntity group = new DatasetGroupEntity();
        group.setOwnerId("alice");
        group.setMdlState("DIRTY");
        group.setMdlVersion(1);
        group.setMdlPublishedAt(Instant.parse("2026-09-25T10:00:00Z"));
        when(groupService.getGroup("alice", "gA")).thenReturn(group);

        MdlPublishService.MdlView view =
                new MdlPublishService.MdlView(
                        "DIRTY",
                        1,
                        "2026-09-25T10:00:00Z",
                        true,
                        List.of(
                                new MdlPublishService.MdlModelView(
                                        "ds_a",
                                        "订单表",
                                        "t_order",
                                        "订单表",
                                        null,
                                        List.of(),
                                        "models/order/metadata.yml",
                                        null)),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of());
        when(mdlPublishService.view("gA")).thenReturn(view);

        MdlPublishService.MdlView got = controller.mdlView("gA", alice).block();

        assertThat(got).isNotNull();
        assertThat(got.state()).isEqualTo("DIRTY");
        assertThat(got.changed()).isTrue();
        assertThat(got.models()).hasSize(1);
        // specs/030: the workspace-relative YAML path rides along for asset browsers.
        assertThat(got.models().get(0).path()).isEqualTo("models/order/metadata.yml");
        verify(groupService).getGroup("alice", "gA");
    }

    @Test
    void workspaceFileRejectsPlatformRuntimeAndNonAssetPaths() throws Exception {
        DatasetGroupEntity group = new DatasetGroupEntity();
        group.setOwnerId("alice");
        when(groupService.getGroup("alice", "gA")).thenReturn(group);
        when(workspace.workspaceRoot("gA")).thenReturn(Files.createTempDirectory("mdl-ws"));

        List<String> rejected =
                List.of(".platform/state.json", "target/mdl.json", "notes.pdf", "dir/../x.txt");
        for (String p : rejected) {
            StepVerifier.create(controller.workspaceFile("gA", p, alice))
                    .expectErrorSatisfies(
                            error -> {
                                assertThat(error).isInstanceOf(ResponseStatusException.class);
                                assertThat(((ResponseStatusException) error).getStatusCode())
                                        .isEqualTo(HttpStatus.BAD_REQUEST);
                            })
                    .verify();
        }
    }

    @Test
    void workspaceFileReadsEngineeringAssetForOwnedGroup() throws Exception {
        DatasetGroupEntity group = new DatasetGroupEntity();
        group.setOwnerId("alice");
        when(groupService.getGroup("alice", "gA")).thenReturn(group);
        Path ws = Files.createTempDirectory("mdl-ws");
        Files.createDirectories(ws.resolve("models").resolve("orders"));
        Files.writeString(
                ws.resolve("models").resolve("orders").resolve("metadata.yml"), "name: orders\n");
        when(workspace.workspaceRoot("gA")).thenReturn(ws);

        Map<String, Object> file =
                controller.workspaceFile("gA", "models/orders/metadata.yml", alice).block();

        assertThat(file).isNotNull();
        assertThat(file.get("path")).isEqualTo("models/orders/metadata.yml");
        assertThat(file.get("content")).isEqualTo("name: orders\n");
    }
}
