package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.CaliberService;
import io.agentscope.dataagent.dataset.DatasetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Caliber lists of one knowledge base (spec149). {@link CaliberService} requires MANAGE, so
 * query-only users never see draft calibers; review and verdicts are a person's own clicks.
 */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/calibers")
public class CaliberController {
    private final CaliberService calibers;

    public CaliberController(CaliberService calibers) {
        this.calibers = calibers;
    }

    public record ReviewRequest(String listPath, boolean scenario, List<String> calibers) {}

    public record VerifyRequest(String listPath, List<String> calibers) {}

    public record VerdictRequest(String listPath, Map<String, String> verdicts) {}

    public record EditRequest(String listPath, String name, CaliberService.RowEdit row) {}

    @GetMapping
    public Mono<List<CaliberService.Table>> overview(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> calibers.overview(userId, groupId));
    }

    @PostMapping("/review")
    public Mono<CaliberService.Table> review(
            @PathVariable String groupId, @RequestBody ReviewRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(
                () ->
                        calibers.review(
                                userId, groupId, req.listPath(), req.scenario(), req.calibers()));
    }

    @PostMapping("/verify")
    public Mono<List<CaliberService.Check>> verify(
            @PathVariable String groupId, @RequestBody VerifyRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> calibers.verify(userId, groupId, req.listPath(), req.calibers()));
    }

    @PostMapping("/verdict")
    public Mono<CaliberService.Table> verdict(
            @PathVariable String groupId, @RequestBody VerdictRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> calibers.verdict(userId, groupId, req.listPath(), req.verdicts()));
    }

    /** spec153 P1.5: a person edits one caliber from the review page. */
    @PostMapping("/row")
    public Mono<CaliberService.Table> edit(
            @PathVariable String groupId, @RequestBody EditRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(
                () -> calibers.edit(userId, groupId, req.listPath(), req.name(), req.row()));
    }

    private static <T> Mono<T> blocking(Callable<T> call) {
        return Mono.fromCallable(call)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }
}
