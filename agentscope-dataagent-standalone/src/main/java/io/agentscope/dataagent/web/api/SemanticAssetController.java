package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.AssetExampleService;
import io.agentscope.dataagent.dataset.AssetProposalStore;
import io.agentscope.dataagent.dataset.AssetReviewStore;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.SemanticAssetService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Views and ref_sql models of one knowledge base (ADR0128, spec111). Every call requires MANAGE
 * in {@link SemanticAssetService}, so query-only users never see draft content.
 */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/assets")
public class SemanticAssetController {
    private final SemanticAssetService assets;
    private final AssetExampleService examples;

    public SemanticAssetController(SemanticAssetService assets, AssetExampleService examples) {
        this.assets = assets;
        this.examples = examples;
    }

    public record SaveRequest(String file, String content, String expectedHash) {}

    public record ReviewRequest(List<String> timeColumns) {}

    public record GenerateRequest(String question) {}

    public record GenerateResponse(String sql) {}

    public record VerifyRequest(List<SemanticAssetService.VerifyCase> cases) {}

    /** {@code save}: cases judged as expected to keep as confirmed questions (spec112). */
    public record VerdictRequest(
            Map<String, String> verdicts, List<SemanticAssetService.VerifyCase> save) {}

    @GetMapping
    public Mono<List<AssetReviewStore.Asset>> list(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> assets.list(userId, groupId));
    }

    public record ProposalDecision(String status) {}

    /** spec113: query template proposals the assistant made in the modeling conversation. */
    @GetMapping("/proposals")
    public Mono<List<AssetProposalStore.Proposal>> proposals(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> assets.proposals(userId, groupId));
    }

    /** Marks a proposal drafted (the page then starts the asset turn) or dismissed. */
    @PostMapping("/proposals/{proposalId}")
    public Mono<AssetProposalStore.Proposal> decideProposal(
            @PathVariable String groupId,
            @PathVariable String proposalId,
            @RequestBody ProposalDecision req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> assets.decideProposal(userId, groupId, proposalId, req.status()));
    }

    /** What deleting the asset would touch: rule entries naming it and questions using it. */
    @GetMapping("/{kind}/{name}/impact")
    public Mono<SemanticAssetService.Impact> impact(
            @PathVariable String groupId,
            @PathVariable String kind,
            @PathVariable String name,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> assets.impact(userId, groupId, kind, name));
    }

    /** Deletes the asset and the rule entries naming it from the draft. */
    @DeleteMapping("/{kind}/{name}")
    public Mono<Map<String, Boolean>> delete(
            @PathVariable String groupId,
            @PathVariable String kind,
            @PathVariable String name,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    assets.delete(userId, groupId, kind, name);
                    return Map.of("deleted", true);
                });
    }

    /** Example requests written from this knowledge base's draft model; requires MANAGE. */
    @GetMapping("/examples")
    public Mono<List<AssetExampleService.Example>> examples(
            @PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> examples.examples(userId, groupId));
    }

    @GetMapping("/{kind}/{name}")
    public Mono<SemanticAssetService.Detail> get(
            @PathVariable String groupId,
            @PathVariable String kind,
            @PathVariable String name,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> assets.get(userId, groupId, kind, name));
    }

    @PutMapping("/{kind}/{name}")
    public Mono<SemanticAssetService.Detail> save(
            @PathVariable String groupId,
            @PathVariable String kind,
            @PathVariable String name,
            @RequestBody SaveRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(
                () ->
                        assets.save(
                                userId,
                                groupId,
                                kind,
                                name,
                                req.file(),
                                req.content(),
                                req.expectedHash()));
    }

    @PostMapping("/{kind}/{name}/review")
    public Mono<SemanticAssetService.Detail> review(
            @PathVariable String groupId,
            @PathVariable String kind,
            @PathVariable String name,
            @RequestBody ReviewRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> assets.review(userId, groupId, kind, name, req.timeColumns()));
    }

    @PostMapping("/{kind}/{name}/generate")
    public Mono<GenerateResponse> generate(
            @PathVariable String groupId,
            @PathVariable String kind,
            @PathVariable String name,
            @RequestBody GenerateRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(
                () ->
                        new GenerateResponse(
                                assets.generate(userId, groupId, kind, name, req.question())));
    }

    @PostMapping("/{kind}/{name}/verify")
    public Mono<List<SemanticAssetService.Check>> verify(
            @PathVariable String groupId,
            @PathVariable String kind,
            @PathVariable String name,
            @RequestBody VerifyRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(() -> assets.verify(userId, groupId, kind, name, req.cases()));
    }

    @PostMapping("/{kind}/{name}/verdict")
    public Mono<SemanticAssetService.Detail> verdict(
            @PathVariable String groupId,
            @PathVariable String kind,
            @PathVariable String name,
            @RequestBody VerdictRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return blocking(
                () -> assets.verdict(userId, groupId, kind, name, req.verdicts(), req.save()));
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
