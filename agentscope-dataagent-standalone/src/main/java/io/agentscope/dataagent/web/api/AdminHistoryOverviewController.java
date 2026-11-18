package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.web.session.AdminHistoryQueryService;
import io.agentscope.dataagent.web.session.AdminHistoryQueryService.Filter;
import io.agentscope.dataagent.web.session.HistoryAssistant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/admin/conversation-history")
public class AdminHistoryOverviewController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final AdminHistoryQueryService queries;

    public AdminHistoryOverviewController(AdminHistoryQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/directory")
    public Mono<java.util.List<AdminConversationController.HistoryOwner>> directory(
            @RequestParam(defaultValue = "") String q, Authentication auth) {
        AdminUserController.requireAdmin(auth);
        if (q.length() > 191) throw badRequest();
        return Mono.fromCallable(() -> queries.directory(q.strip().toLowerCase(Locale.ROOT)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/overview")
    public Mono<AdminHistoryQueryService.Page> overview(
            @RequestParam(defaultValue = "query") String assistantType,
            @RequestParam(defaultValue = "") String userId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "activity") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            Authentication auth) {
        AdminUserController.requireAdmin(auth);
        if (page < 0 || page > 1000000 || !(pageSize == 10 || pageSize == 20 || pageSize == 50))
            throw badRequest();
        Filter filter = filter(userId, query, from, to, sort, direction, assistantType);
        return Mono.fromCallable(() -> queries.page(filter, page, pageSize))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/users")
    public Mono<AdminHistoryQueryService.Users> users(
            @RequestParam(defaultValue = "query") String assistantType,
            @RequestParam(defaultValue = "") String userId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "0") int page,
            Authentication auth) {
        AdminUserController.requireAdmin(auth);
        if (page < 0 || page > 1000000) throw badRequest();
        Filter filter = filter(userId, query, from, to, "activity", "desc", assistantType);
        return Mono.fromCallable(() -> queries.users(filter, page))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public Mono<ResponseEntity<String>> export(
            @RequestParam(defaultValue = "query") String assistantType,
            @RequestParam(defaultValue = "") String userId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "activity") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            Authentication auth) {
        AdminUserController.requireAdmin(auth);
        Filter filter = filter(userId, query, from, to, sort, direction, assistantType);
        return Mono.fromCallable(
                        () -> {
                            var rows = queries.export(filter);
                            if (rows.size() > 5000)
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "匹配会话超过 5000 条，请缩小日期或用户范围后导出。");
                            StringBuilder csv =
                                    new StringBuilder(
                                            "\uFEFF助手类型,会话标题,用户姓名,用户账号,"
                                                    + countLabel(assistantType)
                                                    + ",最近活动时间（北京时间）\r\n");
                            var format =
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                            .withZone(ZONE);
                            for (var s : rows)
                                csv.append(cell(HistoryAssistant.label(s.assistantType())))
                                        .append(',')
                                        .append(cell(s.title()))
                                        .append(',')
                                        .append(cell(s.owner().displayName()))
                                        .append(',')
                                        .append(cell(s.owner().username()))
                                        .append(',')
                                        .append(s.questionCount())
                                        .append(',')
                                        .append(
                                                cell(
                                                        format.format(
                                                                java.time.Instant.ofEpochMilli(
                                                                        s.lastActivityMs()))))
                                        .append("\r\n");
                            return ResponseEntity.ok()
                                    .header(
                                            "Content-Disposition",
                                            "attachment; filename=conversation-history.csv")
                                    .header("Cache-Control", "no-store")
                                    .body(csv.toString());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String countLabel(String type) {
        return switch (type) {
            case "query" -> "本会话提问数";
            case "modeling", "dashboard" -> "本会话对话轮次";
            default -> "本会话用户消息数";
        };
    }

    static String cell(String value) {
        String text = value == null ? "" : value;
        String significant = text.stripLeading();
        if ((!significant.isEmpty() && "=+-@".indexOf(significant.charAt(0)) >= 0)
                || (!text.isEmpty() && "\t\r\n".indexOf(text.charAt(0)) >= 0)) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static Filter filter(
            String user,
            String query,
            String from,
            String to,
            String sort,
            String direction,
            String assistantType) {
        try {
            if (!HistoryAssistant.validFilter(assistantType)
                    || user.length() > 128
                    || query.length() > 160
                    || !("activity".equals(sort) || "questions".equals(sort))
                    || !("asc".equals(direction) || "desc".equals(direction))) throw badRequest();
            long start =
                    from.isEmpty()
                            ? 0
                            : LocalDate.parse(from).atStartOfDay(ZONE).toInstant().toEpochMilli();
            long end =
                    to.isEmpty()
                            ? Long.MAX_VALUE
                            : LocalDate.parse(to)
                                    .plusDays(1)
                                    .atStartOfDay(ZONE)
                                    .toInstant()
                                    .toEpochMilli();
            if (end <= start) throw badRequest();
            return new Filter(
                    user,
                    query.strip().toLowerCase(Locale.ROOT),
                    start,
                    end,
                    sort,
                    "asc".equals(direction),
                    assistantType);
        } catch (java.time.DateTimeException invalid) {
            throw badRequest();
        }
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "请检查搜索词、日期或分页参数");
    }
}
