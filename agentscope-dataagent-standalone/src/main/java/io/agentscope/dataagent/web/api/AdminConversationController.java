package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.persistence.jpa.SessionHistoryRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionMessageRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository;
import io.agentscope.dataagent.web.session.SessionTurnParser.TurnEntry;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Read-only projection of the saved user-visible chat history. */
@RestController
@RequestMapping("/api/admin/conversation-history")
public class AdminConversationController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int PAGE_SIZE = 20;
    private static final int MESSAGE_BATCH_SIZE = 100;
    private static final Pattern RESOURCE_REFERENCE =
            Pattern.compile(
                    "(?i)(?:https?://[^\\s<>\"'()]+?)?(?:/api/artifacts/|/api/agents/[^/\\s]+/workspace/file/"
                        + "|/workspace/|(?:chart|artifact|attachment|file)://|sandbox:|data:image/)"
                        + "[^\\s<>\"'()]*");

    private final SessionHistoryRepository histories;
    private final SessionRegistryRepository sessions;
    private final SessionMessageRepository messages;
    private final io.agentscope.dataagent.web.session.AdminHistoryPresentation presentation;
    private final io.agentscope.dataagent.web.artifact.ArtifactStore artifacts;
    private final io.agentscope.dataagent.web.session.ConversationScopeRegistry scopes;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminConversationController(
            SessionHistoryRepository histories,
            SessionRegistryRepository sessions,
            SessionMessageRepository messages,
            io.agentscope.dataagent.web.session.AdminHistoryPresentation presentation,
            io.agentscope.dataagent.web.artifact.ArtifactStore artifacts,
            io.agentscope.dataagent.web.session.ConversationScopeRegistry scopes) {
        this.histories = histories;
        this.sessions = sessions;
        this.messages = messages;
        this.presentation = presentation;
        this.artifacts = artifacts;
        this.scopes = scopes;
    }

    @GetMapping("/sessions")
    public Mono<HistoryPage> list(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "") String title,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(defaultValue = "" + Long.MAX_VALUE) long before,
            @RequestParam(defaultValue = "") String beforeKey,
            Authentication auth) {
        AdminUserController.requireAdmin(auth);
        if (title.length() > 160 || (from != null && to != null && to.isBefore(from))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请检查标题或日期范围");
        }
        return Mono.fromCallable(
                        () -> {
                            long start =
                                    from == null
                                            ? 0
                                            : from.atStartOfDay(BUSINESS_ZONE)
                                                    .toInstant()
                                                    .toEpochMilli();
                            long end =
                                    to == null
                                            ? Long.MAX_VALUE
                                            : to.plusDays(1)
                                                    .atStartOfDay(BUSINESS_ZONE)
                                                    .toInstant()
                                                    .toEpochMilli();
                            var rows =
                                    histories.adminHistory(
                                            userId,
                                            title.strip().toLowerCase(Locale.ROOT),
                                            start,
                                            end,
                                            before,
                                            beforeKey,
                                            PageRequest.of(0, PAGE_SIZE + 1));
                            var items =
                                    rows.stream()
                                            .limit(PAGE_SIZE)
                                            .map(
                                                    row ->
                                                            new HistoryItem(
                                                                    (String) row[0],
                                                                    (String) row[1],
                                                                    publicText((String) row[2]),
                                                                    ((Number) row[3]).longValue(),
                                                                    ((Number) row[4]).intValue()
                                                                            > 0,
                                                                    new HistoryOwner(
                                                                            (String) row[5],
                                                                            (String) row[6],
                                                                            (String) row[7],
                                                                            (String) row[8])))
                                            .toList();
                            return new HistoryPage(items, rows.size() > PAGE_SIZE);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{userId}/messages")
    public Mono<TurnPage> detail(
            @PathVariable String userId,
            @RequestParam String sessionKey,
            @RequestParam(defaultValue = "" + Integer.MAX_VALUE) int before,
            Authentication auth) {
        AdminUserController.requireAdmin(auth);
        return Mono.fromCallable(
                        () -> {
                            presentation.session(userId, sessionKey);
                            return readTurns(userId, sessionKey, before);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // Read backwards so one page never divides a question from its last assistant segment.
    private TurnPage readTurns(String owner, String key, int before)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        List<ConversationTurn> items = new ArrayList<>();
        TurnEntry answer = null;
        int cursor = before;
        List<io.agentscope.dataagent.web.session.AdminHistoryPresentation.PositionedTool> tools =
                new ArrayList<>();
        while (true) {
            var batch =
                    messages.findBySessionKeyAndPositionLessThanOrderByPositionDesc(
                            key, cursor, PageRequest.of(0, MESSAGE_BATCH_SIZE));
            for (var row : batch) {
                cursor = row.position;
                var entry = mapper.readValue(row.payload, TurnEntry.class);
                if ("TOOL".equalsIgnoreCase(entry.role())) {
                    tools.add(
                            new io.agentscope.dataagent.web.session.AdminHistoryPresentation
                                    .PositionedTool(row.position, entry));
                } else if ("ASSISTANT".equalsIgnoreCase(entry.role()) && answer == null) {
                    answer = entry;
                } else if ("USER".equalsIgnoreCase(entry.role())) {
                    if (items.size() == PAGE_SIZE) {
                        int next = items.get(items.size() - 1).position();
                        Collections.reverse(items);
                        return enrich(owner, key, items, next);
                    }
                    items.add(
                            new ConversationTurn(
                                    row.position,
                                    entry.timestampMs(),
                                    io.agentscope.dataagent.web.session.AdminHistoryPresentation
                                            .displayText(entry.content()),
                                    answer == null
                                            ? null
                                            : io.agentscope.dataagent.web.session
                                                    .AdminHistoryPresentation.displayText(
                                                    answer.content()),
                                    answer == null ? null : answer.timestampMs(),
                                    entry.id(),
                                    answer == null ? null : answer.id(),
                                    entry.knowledgeScope(),
                                    presentation.visuals(
                                            owner,
                                            key,
                                            tools,
                                            answer == null ? null : answer.content()),
                                    null,
                                    true));
                    answer = null;
                    tools.clear();
                }
            }
            if (batch.size() < MESSAGE_BATCH_SIZE) {
                Collections.reverse(items);
                return enrich(owner, key, items, null);
            }
        }
    }

    private TurnPage enrich(String owner, String key, List<ConversationTurn> items, Integer next) {
        var questions =
                items.stream()
                        .map(
                                t ->
                                        new TurnEntry(
                                                t.questionId(),
                                                null,
                                                "USER",
                                                t.question(),
                                                t.askedAtMs(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                t.knowledgeScope()))
                        .toList();
        var enriched = scopes.enrichTurns(owner, questions);
        var votes =
                presentation.votes(
                        owner,
                        key,
                        items.stream()
                                .map(ConversationTurn::answerId)
                                .filter(java.util.Objects::nonNull)
                                .toList());
        List<ConversationTurn> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            var t = items.get(i);
            result.add(
                    new ConversationTurn(
                            t.position(),
                            t.askedAtMs(),
                            t.question(),
                            t.answer(),
                            t.answeredAtMs(),
                            t.questionId(),
                            t.answerId(),
                            enriched.get(i).knowledgeScope(),
                            t.visuals(),
                            t.answerId() == null ? null : votes.values().get(t.answerId()),
                            votes.available()));
        }
        return new TurnPage(result, next);
    }

    @GetMapping("/{userId}/charts/{position}")
    public Mono<org.springframework.http.ResponseEntity<java.util.Map<String, Object>>> chart(
            @PathVariable String userId,
            @PathVariable int position,
            @RequestParam String sessionKey,
            Authentication auth) {
        AdminUserController.requireAdmin(auth);
        return Mono.fromCallable(
                        () ->
                                org.springframework.http.ResponseEntity.ok()
                                        .cacheControl(
                                                org.springframework.http.CacheControl.noStore()
                                                        .cachePrivate())
                                        .body(
                                                presentation.chartOption(
                                                        userId, sessionKey, position)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{userId}/artifacts/{id}")
    public Mono<
                    org.springframework.http.ResponseEntity<
                            org.springframework.core.io.FileSystemResource>>
            image(
                    @PathVariable String userId,
                    @PathVariable String id,
                    @RequestParam String sessionKey,
                    Authentication auth) {
        AdminUserController.requireAdmin(auth);
        return Mono.fromCallable(
                        () -> {
                            var session = presentation.session(userId, sessionKey);
                            var artifact = artifacts.require(userId, id);
                            if (!java.util.Objects.equals(
                                    session.getSessionId(), artifact.sessionId))
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不属于该会话");
                            String name = artifact.filename.toLowerCase(java.util.Locale.ROOT);
                            String type =
                                    name.endsWith(".png")
                                            ? "image/png"
                                            : name.endsWith(".jpg") || name.endsWith(".jpeg")
                                                    ? "image/jpeg"
                                                    : name.endsWith(".svg")
                                                            ? "image/svg+xml"
                                                            : null;
                            if (type == null)
                                throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "仅支持查看已保存图片");
                            var path = artifacts.path(id);
                            if (!java.nio.file.Files.isRegularFile(path))
                                throw new ResponseStatusException(HttpStatus.GONE, "历史图片已不可用");
                            return org.springframework.http.ResponseEntity.ok()
                                    .contentType(
                                            org.springframework.http.MediaType.parseMediaType(type))
                                    .cacheControl(
                                            org.springframework.http.CacheControl.noStore()
                                                    .cachePrivate())
                                    .header("X-Content-Type-Options", "nosniff")
                                    .header(
                                            "Content-Security-Policy",
                                            "sandbox; default-src 'none'")
                                    .body(new org.springframework.core.io.FileSystemResource(path));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public static String publicText(String text) {
        return text == null ? null : RESOURCE_REFERENCE.matcher(text).replaceAll("[附件不可查看]");
    }

    public record HistoryItem(
            String sessionKey,
            String agentId,
            String title,
            long lastActivityMs,
            boolean hasMessages,
            HistoryOwner owner) {}

    public record HistoryOwner(
            String userId, String username, String displayName, String department) {}

    public record HistoryPage(List<HistoryItem> items, boolean hasMore) {}

    public record ConversationTurn(
            int position,
            long askedAtMs,
            String question,
            String answer,
            Long answeredAtMs,
            String questionId,
            String answerId,
            io.agentscope.dataagent.web.session.ConversationScopeRegistry.Selection knowledgeScope,
            io.agentscope.dataagent.web.session.AdminHistoryPresentation.Visuals visuals,
            String feedbackVote,
            boolean feedbackAvailable) {}

    public record TurnPage(List<ConversationTurn> items, Integer nextBefore) {}
}
