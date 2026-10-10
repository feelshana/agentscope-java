package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.ModelingWorkflowService;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Tenant-scoped, read-only guidance for the modeling workbench. */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/modeling/workflow")
public class ModelingWorkflowController {
    private final ModelingWorkflowService workflow;
    private final AgentAccessGuard guard;

    public ModelingWorkflowController(ModelingWorkflowService workflow, AgentAccessGuard guard) {
        this.workflow = workflow;
        this.guard = guard;
    }

    @GetMapping
    public Mono<ModelingWorkflowService.Workflow> snapshot(
            @PathVariable String groupId, Authentication auth) {
        String owner = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            guard.require(owner, "modeling-agent", Tier.RUN);
                            return workflow.snapshot(
                                    new DatasetScope(owner, List.of(groupId)), groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }
}
