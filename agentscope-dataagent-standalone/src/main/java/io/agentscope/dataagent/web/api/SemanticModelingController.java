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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.BaselineMdlService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetService;
import io.agentscope.dataagent.dataset.DocEnhanceService;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.MdlSuggestionService;
import io.agentscope.dataagent.dataset.MdlWorkspaceService;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.tools.data.ModelingToolkit;
import io.agentscope.dataagent.tools.data.ModelingToolkitRegistrar;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.DocEnhanceProposalEntity;
import io.agentscope.dataagent.web.persistence.jpa.DocEnhanceTaskEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticCubeEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Semantic-modeling API for a knowledge base (specs/010 M1/M2): overview, suggestion triggers,
 * relation review (confirm / redirect / reject / manual add), cube CRUD and the MDL channel
 * (YAML preview / validate / publish). Modeling mutations flip a published KB to DIRTY so the UI
 * can prompt a republish; every endpoint resolves the group through
 * {@link DatasetGroupService#getGroup} so cross-tenant access yields 404.
 */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/modeling")
public class SemanticModelingController {

    private final DatasetGroupService groupService;
    private final DatasetService datasetService;
    private final MdlSuggestionService modelingService;
    private final MdlPublishService mdlPublishService;
    private final DocEnhanceService docEnhanceService;
    private final WrenQueryGateway wrenGateway;
    private final BaselineMdlService baselineMdlService;
    private final MdlWorkspaceService workspace;
    private final ModelingToolkitRegistrar modelingToolkitRegistrar;
    private final ObjectMapper mapper;

    public SemanticModelingController(
            DatasetGroupService groupService,
            DatasetService datasetService,
            MdlSuggestionService modelingService,
            MdlPublishService mdlPublishService,
            DocEnhanceService docEnhanceService,
            WrenQueryGateway wrenGateway,
            BaselineMdlService baselineMdlService,
            MdlWorkspaceService workspace,
            ModelingToolkitRegistrar modelingToolkitRegistrar,
            ObjectMapper mapper) {
        this.groupService = groupService;
        this.datasetService = datasetService;
        this.modelingService = modelingService;
        this.mdlPublishService = mdlPublishService;
        this.docEnhanceService = docEnhanceService;
        this.wrenGateway = wrenGateway;
        this.baselineMdlService = baselineMdlService;
        this.workspace = workspace;
        this.modelingToolkitRegistrar = modelingToolkitRegistrar;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ VOs

    public record GroupStateVO(
            String mdlState, int mdlVersion, String mdlPublishedAt, String mdlLastError) {}

    public record RelationVO(
            String id,
            String sourceDatasetId,
            String sourceColumn,
            String targetDatasetId,
            String targetColumn,
            String relationType,
            String description,
            double confidence,
            String origin,
            String joinType,
            String status) {

        public static RelationVO from(DatasetRelationEntity e) {
            return new RelationVO(
                    e.getId(),
                    e.getSourceDatasetId(),
                    e.getSourceColumn(),
                    e.getTargetDatasetId(),
                    e.getTargetColumn(),
                    e.getRelationType(),
                    e.getDescription(),
                    e.getConfidence(),
                    e.getOrigin(),
                    e.getJoinType(),
                    e.getStatus());
        }
    }

    public record CubeVO(
            String id,
            String name,
            String baseDatasetId,
            String description,
            String status,
            JsonNode measures,
            JsonNode dimensions,
            JsonNode timeDimensions,
            String createdAt,
            String updatedAt) {}

    /** One named SQL view (specs/011 M2) shown on the modeling page. */
    public record ViewVO(
            String id,
            String name,
            String baseDatasetId,
            String sqlText,
            String description,
            String status,
            String createdAt,
            String updatedAt) {}

    /** One business term bound to the knowledge base; read-only here (managed via SemanticTermController). */
    public record TermVO(String id, String term, String explanation, String synonyms) {}

    public record OverviewVO(
            GroupStateVO group,
            List<DatasetController.DatasetVO> datasets,
            List<RelationVO> relations,
            List<CubeVO> cubes,
            List<ViewVO> views,
            List<TermVO> terms) {}

    public record EnhanceTaskVO(
            String id,
            String sourceType,
            String sourceLabel,
            String status,
            String errorMessage,
            String createdAt,
            String finishedAt) {}

    public record EnhanceProposalVO(
            String id,
            String type,
            String classification,
            String title,
            String summary,
            JsonNode payload,
            String sourceQuote,
            String confidence,
            String status,
            String decisionError,
            String createdAt,
            String decidedAt) {}

    public record EnhanceOverviewVO(EnhanceTaskVO task, List<EnhanceProposalVO> proposals) {}

    // ------------------------------------------------------------------ requests

    public record RelationUpdateRequest(String status, String joinType, Boolean swap) {}

    public record ManualRelationRequest(
            String sourceDatasetId,
            String sourceColumn,
            String targetDatasetId,
            String targetColumn,
            String joinType) {}

    /** HITL file-change preview request: which write tool and its raw input map (specs/019 §5). */
    public record WorkspacePreviewRequest(String toolName, Map<String, Object> input) {}

    /** Merged before/after content plus the gate ①+② verdict for the file-change card. */
    public record WorkspacePreviewVO(
            boolean ok, String error, String oldContent, String newContent) {}

    public record CubeRequest(
            String name,
            String baseDatasetId,
            String description,
            List<Map<String, Object>> measures,
            List<Map<String, Object>> dimensions,
            List<Map<String, Object>> timeDimensions) {}

    public record ViewRequest(
            String name, String baseDatasetId, String sqlText, String description) {}

    // ------------------------------------------------------------------ endpoints

    /** Current modeling state: group MDL state, model card data, reviewed relations, cubes. */
    @GetMapping
    public Mono<OverviewVO> overview(@PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            DatasetGroupEntity group = groupService.getGroup(userId, groupId);
                            return new OverviewVO(
                                    new GroupStateVO(
                                            group.getMdlState(),
                                            group.getMdlVersion(),
                                            group.getMdlPublishedAt() == null
                                                    ? null
                                                    : group.getMdlPublishedAt().toString(),
                                            group.getMdlLastError()),
                                    groupService.listDatasets(userId, groupId).stream()
                                            .map(
                                                    d ->
                                                            DatasetController.toVOStatic(
                                                                    d,
                                                                    datasetService.readColumns(d)))
                                            .toList(),
                                    modelingService.listRelations(groupId).stream()
                                            .map(RelationVO::from)
                                            .toList(),
                                    modelingService.listCubes(groupId).stream()
                                            .map(this::toCubeVO)
                                            .toList(),
                                    modelingService.listViews(groupId).stream()
                                            .map(this::toViewVO)
                                            .toList(),
                                    modelingService.listTerms(groupId).stream()
                                            .map(
                                                    t ->
                                                            new TermVO(
                                                                    t.getId(),
                                                                    t.getTerm(),
                                                                    t.getExplanation(),
                                                                    t.getSynonyms()))
                                            .toList());
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /** Recomputes suggestions: rule edges + LLM candidates + joinType probing. */
    @PostMapping("/suggest/relations")
    public Mono<List<RelationVO>> suggestRelations(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return modelingService.refreshRelations(groupId).stream()
                                    .map(RelationVO::from)
                                    .toList();
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /** LLM cube proposals (nothing persisted until adopted via POST /cubes). */
    @PostMapping("/suggest/cubes")
    public Mono<List<MdlSuggestionService.CubeSuggestion>> suggestCubes(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.<List<MdlSuggestionService.CubeSuggestion>>fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            throw new DatasetException("Cube 已停用，请使用逻辑模型或视图", 410);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /** Human review: confirm (optionally redirecting via swap/joinType) or reject an edge. */
    @PutMapping("/relations/{relationId}")
    public Mono<RelationVO> updateRelation(
            @PathVariable String groupId,
            @PathVariable String relationId,
            @RequestBody RelationUpdateRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            String status =
                                    req.status() == null ? "" : req.status().trim().toUpperCase();
                            DatasetRelationEntity e;
                            if ("REJECTED".equals(status)) {
                                e = modelingService.rejectRelation(groupId, relationId);
                            } else if ("CONFIRMED".equals(status)) {
                                e =
                                        modelingService.confirmRelation(
                                                groupId,
                                                relationId,
                                                req.joinType(),
                                                Boolean.TRUE.equals(req.swap()));
                            } else {
                                throw new DatasetException(
                                        "status must be CONFIRMED or REJECTED", 400);
                            }
                            groupService.markMdlDirty(userId, groupId);
                            return RelationVO.from(e);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /** Hand-written relation (origin=manual, confirmed immediately). */
    @PostMapping("/relations")
    public Mono<RelationVO> addRelation(
            @PathVariable String groupId,
            @RequestBody ManualRelationRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            RelationVO vo =
                                    RelationVO.from(
                                            modelingService.addManualRelation(
                                                    groupId,
                                                    new MdlSuggestionService.ManualRelationPayload(
                                                            req.sourceDatasetId(),
                                                            req.sourceColumn(),
                                                            req.targetDatasetId(),
                                                            req.targetColumn(),
                                                            req.joinType())));
                            groupService.markMdlDirty(userId, groupId);
                            return vo;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    // ------------------------------------------------------------------ cubes

    @PostMapping("/cubes")
    public Mono<CubeVO> createCube(
            @PathVariable String groupId, @RequestBody CubeRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.<CubeVO>fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            throw new DatasetException("Cube 已停用，请使用逻辑模型或视图", 410);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @PutMapping("/cubes/{cubeId}")
    public Mono<CubeVO> updateCube(
            @PathVariable String groupId,
            @PathVariable String cubeId,
            @RequestBody CubeRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.<CubeVO>fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            throw new DatasetException("Cube 已停用，请使用逻辑模型或视图", 410);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @DeleteMapping("/cubes/{cubeId}")
    public Mono<ResponseEntity<Void>> deleteCube(
            @PathVariable String groupId, @PathVariable String cubeId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            throw new DatasetException("Cube 已停用，请使用逻辑模型或视图", 410);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    // ------------------------------------------------------------------ views (specs/011 M2)

    @PostMapping("/views")
    public Mono<ViewVO> createView(
            @PathVariable String groupId, @RequestBody ViewRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            ViewVO vo =
                                    toViewVO(
                                            modelingService.createView(
                                                    groupId,
                                                    new MdlSuggestionService.ViewPayload(
                                                            req.name(),
                                                            req.baseDatasetId(),
                                                            req.sqlText(),
                                                            req.description())));
                            groupService.markMdlDirty(userId, groupId);
                            return vo;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @PutMapping("/views/{viewId}")
    public Mono<ViewVO> updateView(
            @PathVariable String groupId,
            @PathVariable String viewId,
            @RequestBody ViewRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            ViewVO vo =
                                    toViewVO(
                                            modelingService.updateView(
                                                    groupId,
                                                    viewId,
                                                    new MdlSuggestionService.ViewPayload(
                                                            req.name(),
                                                            req.baseDatasetId(),
                                                            req.sqlText(),
                                                            req.description())));
                            groupService.markMdlDirty(userId, groupId);
                            return vo;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @DeleteMapping("/views/{viewId}")
    public Mono<ResponseEntity<Void>> deleteView(
            @PathVariable String groupId, @PathVariable String viewId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            modelingService.deleteView(groupId, viewId);
                            groupService.markMdlDirty(userId, groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    // ------------------------------------------------------------------ document semantic
    // enhancement

    @PostMapping("/enhance")
    public Mono<EnhanceTaskVO> triggerEnhance(@PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return toEnhanceTask(docEnhanceService.triggerAnalyze(userId, groupId));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @GetMapping("/enhance")
    public Mono<EnhanceOverviewVO> enhanceOverview(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            EnhanceTaskVO task =
                                    docEnhanceService
                                            .latestTask(userId, groupId)
                                            .map(this::toEnhanceTask)
                                            .orElse(null);
                            List<EnhanceProposalVO> proposals =
                                    task == null
                                            ? List.of()
                                            : docEnhanceService
                                                    .latestProposals(userId, groupId)
                                                    .stream()
                                                    .map(this::toEnhanceProposal)
                                                    .toList();
                            return new EnhanceOverviewVO(task, proposals);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @PostMapping("/enhance/proposals/{proposalId}/adopt")
    public Mono<EnhanceProposalVO> adoptEnhanceProposal(
            @PathVariable String groupId, @PathVariable String proposalId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return toEnhanceProposal(
                                    docEnhanceService.adopt(userId, groupId, proposalId));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    @PostMapping("/enhance/proposals/{proposalId}/ignore")
    public Mono<EnhanceProposalVO> ignoreEnhanceProposal(
            @PathVariable String groupId, @PathVariable String proposalId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return toEnhanceProposal(
                                    docEnhanceService.ignore(userId, groupId, proposalId));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    // ------------------------------------------------------------------ workspace file access
    //  (specs/019 §5: the HITL file-change card needs the current file and a gated preview)

    /** Reads one workspace file for the HITL card editor and asset browsers; path-confined. */
    @GetMapping("/workspace/file")
    public Mono<Map<String, Object>> workspaceFile(
            @PathVariable String groupId, @RequestParam String path, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            Path ws = workspace.workspaceRoot(groupId).toAbsolutePath().normalize();
                            Path target = ws.resolve(path).normalize();
                            if (!target.startsWith(ws)) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "工作区路径越界");
                            }
                            if (!isModelingAssetPath(path)) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "仅允许读取工程文件（yml/yaml/md/sql）");
                            }
                            if (!Files.isRegularFile(target)) {
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
                            }
                            return Map.<String, Object>of(
                                    "path",
                                    path,
                                    "content",
                                    Files.readString(target, StandardCharsets.UTF_8));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /**
     * Modeling-page asset browsers read engineering files only (specs/030): platform runtime
     * directories (.platform/, target/) stay off-limits even though they live inside the
     * workspace. specs/035: {@code .sql} joined the whitelist so the derived-model tab can render
     * {@code models/<name>/ref_sql.sql}.
     */
    private static boolean isModelingAssetPath(String path) {
        String norm = path.replace('\\', '/');
        if (norm.startsWith(".platform/") || norm.startsWith("target/")) {
            return false;
        }
        String lower = norm.toLowerCase();
        return lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".md")
                || lower.endsWith(".sql");
    }

    /**
     * Runs the write pipeline's gates ①②③ (YAML parse + scratch {@code context validate --strict}
     * + scratch {@code context build} / {@code dry-plan "SELECT 1"}, specs/023) for a
     * write_file/patch_file draft without touching the real workspace, returning the merged
     * before/after content so the HITL card can render the diff and the verdict pre-confirm.
     */
    @PostMapping("/workspace/preview")
    public Mono<WorkspacePreviewVO> workspacePreview(
            @PathVariable String groupId,
            @RequestBody WorkspacePreviewRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        if (req.toolName() == null
                || !Set.of("write_file", "patch_file").contains(req.toolName())
                || req.input() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "仅支持 write_file / patch_file 预检");
        }
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            ModelingToolkit.PreviewResult r =
                                    modelingToolkitRegistrar
                                            .toolkit()
                                            .previewChange(groupId, req.toolName(), req.input());
                            return new WorkspacePreviewVO(
                                    r.ok(), r.error(), r.oldContent(), r.newContent());
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    // ------------------------------------------------------------------ mdl

    /** YAML preview: freshly assembled files, published snapshot, issues and MDL state. */
    @GetMapping("/mdl")
    public Mono<MdlPublishService.MdlPreview> mdlPreview(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return mdlPublishService.preview(groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /** Read-only structured MDL view for visualization (specs/013 M2): draft + publish state. */
    @GetMapping("/mdl/view")
    public Mono<MdlPublishService.MdlView> mdlView(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return mdlPublishService.view(groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /** Runs assemble + wren context validate --strict; local issues short-circuit the subprocess. */
    @PostMapping("/mdl/validate")
    public Mono<MdlPublishService.MdlValidation> mdlValidate(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return mdlPublishService.validate(groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /** Rebuilds the table/column baseline without publishing semantic drafts. */
    @PostMapping("/mdl/initialize")
    public Mono<MdlPublishService.MdlPublishResult> mdlInitialize(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return baselineMdlService.publishAfterDatasetChange(userId, groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /** validate -> build -> snapshot -> version+1; any failure keeps the previous snapshot. */
    @PostMapping("/mdl/publish")
    public Mono<MdlPublishService.MdlPublishResult> mdlPublish(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            MdlPublishService.MdlPublishResult result =
                                    mdlPublishService.publish(groupId);
                            // Publish = rebuild the instance (ADR 0018 D8): the running wren engine
                            // froze the previous manifest at startup, so it is closed here — the
                            // next query respawns against the fresh snapshot.
                            wrenGateway.invalidate(groupId);
                            return result;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    // ------------------------------------------------------------------ helpers

    private static MdlSuggestionService.CubePayload toPayload(CubeRequest req) {
        return new MdlSuggestionService.CubePayload(
                req.name(),
                req.baseDatasetId(),
                req.description(),
                req.measures(),
                req.dimensions(),
                req.timeDimensions());
    }

    private CubeVO toCubeVO(SemanticCubeEntity e) {
        return new CubeVO(
                e.getId(),
                e.getName(),
                e.getBaseDatasetId(),
                e.getDescription(),
                e.getStatus(),
                readJson(e.getMeasuresJson()),
                readJson(e.getDimensionsJson()),
                readJson(e.getTimeDimensionsJson()),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
                e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
    }

    private ViewVO toViewVO(SemanticViewEntity e) {
        return new ViewVO(
                e.getId(),
                e.getName(),
                e.getBaseDatasetId(),
                e.getSqlText(),
                e.getDescription(),
                e.getStatus(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
                e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
    }

    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "[]" : json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    private EnhanceTaskVO toEnhanceTask(DocEnhanceTaskEntity task) {
        return new EnhanceTaskVO(
                task.getId(),
                task.getSourceType(),
                task.getSourceLabel(),
                task.getStatus(),
                task.getErrorMessage(),
                task.getCreatedAt() == null ? null : task.getCreatedAt().toString(),
                task.getFinishedAt() == null ? null : task.getFinishedAt().toString());
    }

    private EnhanceProposalVO toEnhanceProposal(DocEnhanceProposalEntity proposal) {
        return new EnhanceProposalVO(
                proposal.getId(),
                proposal.getProposalType(),
                proposal.getClassification(),
                proposal.getTitle(),
                proposal.getSummary(),
                readObjectJson(proposal.getPayloadJson()),
                proposal.getSourceQuote(),
                proposal.getConfidence(),
                proposal.getStatus(),
                proposal.getDecisionError(),
                proposal.getCreatedAt() == null ? null : proposal.getCreatedAt().toString(),
                proposal.getDecidedAt() == null ? null : proposal.getDecidedAt().toString());
    }

    private JsonNode readObjectJson(String json) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private Throwable toStatus(Throwable t) {
        if (t instanceof DatasetException de) {
            return new ResponseStatusException(
                    HttpStatus.valueOf(de.status()), de.getMessage(), de);
        }
        return t;
    }
}
