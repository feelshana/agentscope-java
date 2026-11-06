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
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminConversationController(
            SessionHistoryRepository histories,
            SessionRegistryRepository sessions,
            SessionMessageRepository messages) {
        this.histories = histories;
        this.sessions = sessions;
        this.messages = messages;
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
                            sessions.findById(sessionKey)
                                    .filter(
                                            s ->
                                                    userId.equals(s.getUserId())
                                                            && "main".equals(s.getKind()))
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND, "找不到该用户的会话"));
                            return readTurns(sessionKey, before);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // Read backwards so one page never divides a question from its last assistant segment.
    private TurnPage readTurns(String key, int before)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        List<ConversationTurn> items = new ArrayList<>();
        TurnEntry answer = null;
        int cursor = before;
        while (true) {
            var batch =
                    messages.findBySessionKeyAndPositionLessThanOrderByPositionDesc(
                            key, cursor, PageRequest.of(0, MESSAGE_BATCH_SIZE));
            for (var row : batch) {
                cursor = row.position;
                var entry = mapper.readValue(row.payload, TurnEntry.class);
                if ("ASSISTANT".equalsIgnoreCase(entry.role()) && answer == null) {
                    answer = entry;
                } else if ("USER".equalsIgnoreCase(entry.role())) {
                    if (items.size() == PAGE_SIZE) {
                        int next = items.get(items.size() - 1).position();
                        Collections.reverse(items);
                        return new TurnPage(items, next);
                    }
                    items.add(
                            new ConversationTurn(
                                    row.position,
                                    entry.timestampMs(),
                                    publicText(entry.content()),
                                    answer == null ? null : publicText(answer.content()),
                                    answer == null ? null : answer.timestampMs()));
                    answer = null;
                }
            }
            if (batch.size() < MESSAGE_BATCH_SIZE) {
                Collections.reverse(items);
                return new TurnPage(items, null);
            }
        }
    }

    private static String publicText(String text) {
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
            int position, long askedAtMs, String question, String answer, Long answeredAtMs) {}

    public record TurnPage(List<ConversationTurn> items, Integer nextBefore) {}
}
