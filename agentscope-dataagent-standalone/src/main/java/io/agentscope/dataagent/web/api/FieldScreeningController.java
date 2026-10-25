package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.FieldScreeningService;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** spec093 field screening: choose the columns of each table that enter the semantic model. */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/fields")
public class FieldScreeningController {
    private static final String AGENT = "modeling-agent";
    private final FieldScreeningService screening;
    private final AgentAccessGuard guard;

    public FieldScreeningController(FieldScreeningService screening, AgentAccessGuard guard) {
        this.screening = screening;
        this.guard = guard;
    }

    public record Save(
            List<FieldScreeningService.Update> updates, boolean confirm, boolean force) {}

    @GetMapping
    public Mono<List<FieldScreeningService.TableStatus>> status(
            @PathVariable String groupId, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(user, () -> screening.status(user, groupId));
    }

    @GetMapping("/{datasetId}")
    public Mono<FieldScreeningService.TableFields> fields(
            @PathVariable String groupId, @PathVariable String datasetId, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(user, () -> screening.fields(user, groupId, datasetId));
    }

    @PutMapping("/{datasetId}")
    public Mono<FieldScreeningService.SaveResult> save(
            @PathVariable String groupId,
            @PathVariable String datasetId,
            @RequestBody Save body,
            Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(
                user,
                () ->
                        screening.save(
                                user,
                                groupId,
                                datasetId,
                                body == null ? List.of() : body.updates(),
                                body != null && body.confirm(),
                                body != null && body.force()));
    }

    private <T> Mono<T> blocking(String user, Callable<T> action) {
        return Mono.fromCallable(
                        () -> {
                            guard.require(user, AGENT, Tier.RUN);
                            return action.call();
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }
}
