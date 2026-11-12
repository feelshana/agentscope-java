package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.BaselineMdlService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.MdlTypes;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Publication HTTP boundary, separate from semantic authoring and suggestion endpoints. */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/modeling")
public final class MdlPublicationController {
    private final DatasetGroupService groupService;
    private final MdlPublishService mdlPublishService;
    private final BaselineMdlService baselineMdlService;
    private final io.agentscope.dataagent.dataset.ModelingActivity modelingActivity;

    public MdlPublicationController(
            DatasetGroupService groups,
            MdlPublishService publisher,
            BaselineMdlService baseline,
            io.agentscope.dataagent.dataset.ModelingActivity activity) {
        this.groupService = groups;
        this.mdlPublishService = publisher;
        this.baselineMdlService = baseline;
        this.modelingActivity = activity;
    }

    /** YAML preview: freshly assembled files, published snapshot, issues and MDL state. */
    @GetMapping("/mdl")
    public Mono<MdlTypes.MdlPreview> mdlPreview(@PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return mdlPublishService.preview(groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /** The official compiled MDL of the published version and of the draft's last build. */
    @GetMapping("/mdl/compiled")
    public Mono<MdlTypes.CompiledMdl> mdlCompiled(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            return mdlPublishService.compiled(groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    /** Read-only structured MDL view for visualization (specs/013 M2): draft + publish state. */
    @GetMapping("/mdl/view")
    public Mono<MdlTypes.MdlView> mdlView(@PathVariable String groupId, Authentication auth) {
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
    public Mono<MdlTypes.MdlValidation> mdlValidate(
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
    public Mono<MdlTypes.MdlPublishResult> mdlInitialize(
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
    public Mono<MdlTypes.MdlPublishResult> mdlPublish(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            groupService.getGroup(userId, groupId);
                            // ADR0111: never publish a draft the assistant is still changing.
                            if (modelingActivity != null) modelingActivity.requireIdle(groupId);
                            MdlTypes.MdlPublishResult result = mdlPublishService.publish(groupId);
                            // Publish = rebuild the instance (ADR 0018 D8): the running wren engine
                            // froze the previous manifest at startup, so it is closed here — the
                            // next query respawns against the fresh snapshot.
                            // Publication events invalidate before scheduling retrieval warmup.
                            return result;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::toStatus);
    }

    private Throwable toStatus(Throwable t) {
        if (t instanceof DatasetException de) {
            return new ResponseStatusException(
                    HttpStatus.valueOf(de.status()), de.getMessage(), de);
        }
        return t;
    }
}
