package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.WrenQueryPreparation;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Exposes advisory preparation state only after agent and knowledge-base authorization. */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/query-preparation")
public class QueryPreparationController {
    private final AgentAccessGuard guard;
    private final WrenQueryPreparation preparation;

    public QueryPreparationController(AgentAccessGuard guard, WrenQueryPreparation preparation) {
        this.guard = guard;
        this.preparation = preparation;
    }

    @GetMapping
    public Mono<WrenQueryPreparation.Status> status(
            @PathVariable String groupId, Authentication auth) {
        String actor = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            guard.require(actor, "data-agent", Tier.RUN);
                            return preparation.status(actor, groupId);
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }
}
