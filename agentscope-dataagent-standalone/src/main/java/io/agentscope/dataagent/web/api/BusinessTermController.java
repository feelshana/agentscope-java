package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.BusinessTermService;
import io.agentscope.dataagent.dataset.DatasetException;
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
 * Business terms of one knowledge base (spec095): read with QUERY (published version only),
 * edited with MANAGE into the draft. Access checks live in {@link BusinessTermService}.
 */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/terms")
public class BusinessTermController {
    private final BusinessTermService terms;

    public BusinessTermController(BusinessTermService terms) {
        this.terms = terms;
    }

    /**
     * {@code original} is the bullet text being edited or deleted, as returned by the list. {@code
     * delete} is boxed: the page omits it, and Jackson 3 rejects a missing primitive.
     */
    public record Change(
            String original,
            String term,
            String synonyms,
            String explanation,
            String text,
            Boolean delete) {}

    @GetMapping
    public Mono<BusinessTermService.Terms> list(@PathVariable String groupId, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(() -> terms.list(user, groupId));
    }

    @PostMapping
    public Mono<BusinessTermService.Terms> change(
            @PathVariable String groupId, @RequestBody Change body, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(
                () -> {
                    if (body == null) throw new DatasetException("请填写术语", 400);
                    var input =
                            new BusinessTermService.Input(
                                    body.term(), body.synonyms(), body.explanation(), body.text());
                    if (body.original() == null || body.original().isBlank())
                        return terms.add(user, groupId, input);
                    return Boolean.TRUE.equals(body.delete())
                            ? terms.delete(user, groupId, body.original())
                            : terms.update(user, groupId, body.original(), input);
                });
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DatasetException.class,
                        e ->
                                new ResponseStatusException(
                                        HttpStatus.valueOf(e.status()), e.getMessage(), e));
    }
}
