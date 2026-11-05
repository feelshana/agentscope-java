package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.BusinessDocumentService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DraftItemService;
import io.agentscope.dataagent.dataset.DraftReviewService;
import io.agentscope.dataagent.dataset.ModelingConfirmationService;
import io.agentscope.dataagent.dataset.parser.DocxDescriptionExtractor;
import io.agentscope.dataagent.tools.data.ModelingToolkitRegistrar;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Draft review before publication and business documents of a knowledge base (spec092). */
@RestController
@RequestMapping("/api/dataset-groups/{groupId}/modeling")
public class DraftReviewController {
    private static final String AGENT = "modeling-agent";
    private final DraftReviewService review;
    private final DraftItemService items;
    private final BusinessDocumentService documents;
    private final ModelingConfirmationService confirmations;
    private final AgentAccessGuard guard;
    private final ModelingToolkitRegistrar modeling;

    public DraftReviewController(
            DraftReviewService review,
            DraftItemService items,
            BusinessDocumentService documents,
            ModelingConfirmationService confirmations,
            AgentAccessGuard guard,
            ModelingToolkitRegistrar modeling) {
        this.review = review;
        this.items = items;
        this.documents = documents;
        this.confirmations = confirmations;
        this.guard = guard;
        this.modeling = modeling;
    }

    /**
     * spec117: the agent's write gates (YAML parse, strict validate, build, dry-plan) on a YAML
     * file's next content, run inside the workspace lock before a page edit is written.
     */
    private DraftItemService.Gate gate(String groupId) {
        return (path, content) -> {
            if (!path.endsWith(".yml") && !path.endsWith(".yaml")) return null;
            var result =
                    modeling.toolkit()
                            .previewChange(
                                    groupId,
                                    "write_file",
                                    Map.of("path", path, "content", content));
            return result.ok() ? null : result.error();
        };
    }

    public record Revert(String path) {}

    public record Edit(String path, String content) {}

    /** One item edit: a model's descriptions, a relationship, or a rule/note bullet. */
    public record ItemEdit(
            String category,
            String path,
            String model,
            String tableDescription,
            Map<String, String> columns,
            String name,
            String joinType,
            String condition,
            String original,
            String text,
            Boolean delete,
            // spec117 page edits
            String column,
            String columnType,
            String displayName,
            String semanticType,
            List<String> synonyms,
            String expression,
            Boolean create,
            String heading,
            String leftModel,
            String leftColumn,
            String rightModel,
            String rightColumn,
            String description) {
        boolean deleting() {
            return Boolean.TRUE.equals(delete);
        }
    }

    public record Resolve(List<ModelingConfirmationService.Answer> answers) {}

    /** A new bullet (no original), a move to another section, or an edit/delete in place. */
    private List<DraftItemService.Item> bullet(String user, String groupId, ItemEdit body) {
        boolean hasHeading = body.heading() != null && !body.heading().isBlank();
        if (body.original() == null || body.original().isBlank()) {
            if (!hasHeading) throw new DatasetException("请选择小节", 400);
            items.appendBullet(user, groupId, body.path(), body.heading(), body.text());
            return items.items(user, groupId, null);
        }
        if (hasHeading && !body.deleting()) {
            String original = body.original();
            if (body.text() != null
                    && !body.text().isBlank()
                    && !body.text().strip().equals(original)) {
                items.updateBullet(user, groupId, body.path(), original, body.text());
                original = body.text().strip();
            }
            return items.moveBullet(user, groupId, body.path(), original, body.heading());
        }
        return items.updateBullet(
                user, groupId, body.path(), body.original(), body.deleting() ? null : body.text());
    }

    @PostMapping("/draft/edit")
    public Mono<DraftReviewService.Review> edit(
            @PathVariable String groupId, @RequestBody Edit body, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(
                user,
                () -> {
                    if (body == null || body.path() == null || body.path().isBlank())
                        throw new DatasetException("请提供要修改的文件", 400);
                    return review.edit(user, groupId, body.path(), body.content(), gate(groupId));
                });
    }

    /** "AI 建模结果": draft items vs. the published snapshot; {@code since} narrows to a turn. */
    @GetMapping("/draft/items")
    public Mono<List<DraftItemService.Item>> items(
            @PathVariable String groupId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String since,
            Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(user, () -> items.items(user, groupId, since));
    }

    @PostMapping("/draft/items")
    public Mono<List<DraftItemService.Item>> editItem(
            @PathVariable String groupId, @RequestBody ItemEdit body, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(
                user,
                () -> {
                    if (body == null || body.category() == null)
                        throw new DatasetException("请提供要修改的条目", 400);
                    return switch (body.category()) {
                        case "MODEL" -> {
                            if (body.deleting()) throw new DatasetException("模型只能编辑，不能删除", 400);
                            yield items.updateModel(
                                    user,
                                    groupId,
                                    body.model(),
                                    body.tableDescription(),
                                    body.columns());
                        }
                        case "COLUMN" ->
                                items.updateColumn(
                                        user,
                                        groupId,
                                        body.model(),
                                        new DraftItemService.ColumnChange(
                                                body.column(),
                                                body.columnType(),
                                                body.description(),
                                                body.displayName(),
                                                body.semanticType(),
                                                body.synonyms(),
                                                body.expression(),
                                                Boolean.TRUE.equals(body.create()),
                                                body.deleting()),
                                        gate(groupId));
                        case "RELATIONSHIP" ->
                                body.name() == null || body.name().isBlank()
                                        ? items.addRelationship(
                                                user,
                                                groupId,
                                                body.leftModel(),
                                                body.leftColumn(),
                                                body.rightModel(),
                                                body.rightColumn(),
                                                body.joinType(),
                                                body.description(),
                                                gate(groupId))
                                        : items.updateRelationship(
                                                user,
                                                groupId,
                                                body.name(),
                                                body.joinType(),
                                                body.condition(),
                                                body.description(),
                                                body.deleting());
                        case "SEMANTIC", "NOTE" -> bullet(user, groupId, body);
                        default -> throw new DatasetException("不支持的条目类型", 400);
                    };
                });
    }

    @GetMapping("/confirmations")
    public Mono<List<ModelingConfirmationService.Confirmation>> confirmations(
            @PathVariable String groupId, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(user, () -> confirmations.list(user, groupId));
    }

    /** Records the user's decisions and returns those to hand to the assistant. */
    @PostMapping("/confirmations/resolve")
    public Mono<List<ModelingConfirmationService.Decision>> resolve(
            @PathVariable String groupId, @RequestBody Resolve body, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(
                user,
                () -> confirmations.resolve(user, groupId, body == null ? null : body.answers()));
    }

    @GetMapping("/draft")
    public Mono<DraftReviewService.Review> draft(
            @PathVariable String groupId, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(user, () -> review.review(user, groupId));
    }

    @PostMapping("/draft/revert")
    public Mono<DraftReviewService.Review> revert(
            @PathVariable String groupId, @RequestBody Revert body, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(
                user,
                () -> {
                    if (body == null || body.path() == null || body.path().isBlank())
                        throw new DatasetException("请提供要撤销的文件", 400);
                    return review.revert(user, groupId, body.path());
                });
    }

    @PostMapping("/draft/discard")
    public Mono<DraftReviewService.Review> discard(
            @PathVariable String groupId, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(user, () -> review.discard(user, groupId));
    }

    @GetMapping("/documents")
    public Mono<List<BusinessDocumentService.Document>> documents(
            @PathVariable String groupId, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(user, () -> documents.list(user, groupId));
    }

    @GetMapping("/documents/{name}/content")
    public Mono<BusinessDocumentService.DocumentText> documentContent(
            @PathVariable String groupId, @PathVariable String name, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(user, () -> documents.content(user, groupId, name));
    }

    @PutMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<BusinessDocumentService.Document> upload(
            @PathVariable String groupId, @RequestPart("file") FilePart file, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return DataBufferUtils.join(file.content())
                .map(
                        buffer -> {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            DataBufferUtils.release(buffer);
                            return bytes;
                        })
                .flatMap(
                        bytes ->
                                blocking(
                                        user,
                                        () ->
                                                documents.put(
                                                        user,
                                                        groupId,
                                                        file.filename(),
                                                        text(file.filename(), bytes))));
    }

    @DeleteMapping("/documents/{name}")
    public Mono<Map<String, Boolean>> delete(
            @PathVariable String groupId, @PathVariable String name, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return blocking(
                user,
                () -> {
                    documents.delete(user, groupId, name);
                    return Map.of("deleted", true);
                });
    }

    private static String text(String fileName, byte[] bytes) throws java.io.IOException {
        if (fileName != null && fileName.toLowerCase().endsWith(".docx"))
            return DocxDescriptionExtractor.extract(
                    new ByteArrayInputStream(bytes), BusinessDocumentService.MAX_CHARS);
        return new String(bytes, StandardCharsets.UTF_8);
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
