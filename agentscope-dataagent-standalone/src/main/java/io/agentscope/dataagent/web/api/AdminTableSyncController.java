package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.tablesync.TableSyncWorker;
import io.agentscope.dataagent.runtime.tablesync.TableSyncWorker.Action;
import java.util.Map;
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

@RestController
@RequestMapping("/api/admin/table-sync")
public class AdminTableSyncController {
    private static final TypeReference<Map<String, Object>> RESPONSE = new TypeReference<>() {};
    private static final Map<String, Action> OPERATIONS =
            Map.of(
                    "tables",
                    Action.TABLES,
                    "columns",
                    Action.COLUMNS,
                    "preview",
                    Action.PREVIEW,
                    "test-date",
                    Action.TEST_DATE,
                    "create",
                    Action.CREATE,
                    "schedule",
                    Action.SCHEDULE,
                    "run-now",
                    Action.RUN_NOW,
                    "rerun",
                    Action.RERUN,
                    "target",
                    Action.SET_TARGET);
    private static final Map<String, String> MESSAGES =
            Map.ofEntries(
                    Map.entry("NOT_CONFIGURED", "请在同步页面先保存目标库连接。"),
                    Map.entry("INVALID_INPUT", "输入不完整或格式不正确。"),
                    Map.entry("INVALID_TARGET", "目标表名须以 sync_ 开头，且只能包含小写字母、数字和下划线。"),
                    Map.entry("SOURCE_NOT_ALLOWED", "此源地址未加入表同步允许列表。"),
                    Map.entry("SOURCE_QUERY", "源库连接或读取失败，请检查账号权限、网络和 TLS 配置。"),
                    Map.entry("SOURCE_TIME_ZONE", "源连接的 TIMESTAMP 时区必须为 UTC 或固定东八区。"),
                    Map.entry("UNSUPPORTED_SOURCE", "源库仅支持 MySQL 5.7 或 MySQL 8。"),
                    Map.entry("UNSUPPORTED_TARGET", "目标库须为 MySQL 8。"),
                    Map.entry("TARGET_CONNECTION", "无法连接目标 MySQL 的 data_agent，请检查账号、网络和 TLS 设置。"),
                    Map.entry("MIGRATION_REQUIRED", "请先在 BI 数据开发手工执行 0152，创建平台同步元数据表。"),
                    Map.entry("CONTROL_ACCESS", "同步账号无法访问平台同步元数据表，请检查表级权限。"),
                    Map.entry("TARGET_TABLE_REQUIRED", "请在 BI 数据开发手工执行预览中的目标表 SQL。"),
                    Map.entry("TARGET_NOT_EMPTY", "首次同步需要一个空目标表。"),
                    Map.entry("TARGET_OWNERSHIP", "目标表结构或同步标记与预览不符。"),
                    Map.entry("TARGET_ENDPOINT_LOCKED", "现有同步任务使用已保存的目标实例。此处可更新账号和密码。"),
                    Map.entry("TARGET_SETTINGS_ERROR", "读取或保存目标连接失败，请检查平台数据库权限。"),
                    Map.entry("UNSUPPORTED_TABLE", "请选择受支持的 InnoDB 基础表。"),
                    Map.entry("UNSUPPORTED_COLUMN", "已选字段含不支持的类型或生成列，请调整字段。"),
                    Map.entry("DATE_INDEX_REQUIRED", "日期字段须是已选字段，并有以该字段开头的源表索引。"),
                    Map.entry("DATE_FIELD_REQUIRED", "请从已勾选的列中选择实际的业务日期字段。"),
                    Map.entry("UNINDEXED_SCAN_LIMIT", "无源索引时，整张源表最多扫描 10 万行或估算 128 MiB。"),
                    Map.entry("UNINDEXED_BUFFER_LIMIT", "选定日期范围匹配的数据超过 64 MiB 缓冲上限，请缩小同步范围。"),
                    Map.entry("INVALID_FORMAT", "日期格式与选定字段的类型或同步粒度不匹配。"),
                    Map.entry("INVALID_PERIOD", "请选择有效的历史范围。按月时请选择完整月份。"),
                    Map.entry("NULL_DATE", "日期字段包含空值，请选用能覆盖数据的日期字段。"),
                    Map.entry("SCHEMA_CHANGED", "源字段结构已变化，请重新核对字段和建表预览。"),
                    Map.entry("TARGET_EXISTS", "目标表名已存在或已分配给其他任务。"),
                    Map.entry("INITIAL_REQUIRED", "首次同步完成后才能启用计划。"),
                    Map.entry("ALREADY_QUEUED", "该任务已有排队或运行中的同步。"),
                    Map.entry("CREDENTIAL_ERROR", "无法读取旧任务的同步凭证。"),
                    Map.entry("WORKER_TIMEOUT", "同步服务响应超时，请稍后重试。"),
                    Map.entry("NOT_FOUND", "未找到同步任务。"));

    private final TableSyncWorker worker;
    private final ObjectMapper mapper;

    public AdminTableSyncController(TableSyncWorker worker, ObjectMapper mapper) {
        this.worker = worker;
        this.mapper = mapper;
    }

    @GetMapping
    public Mono<Map<String, Object>> list(Authentication auth) {
        return run(Action.LIST, mapper.createObjectNode(), requiredActor(auth));
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> detail(@PathVariable String id, Authentication auth) {
        return run(Action.DETAIL, mapper.createObjectNode().put("id", id), requiredActor(auth));
    }

    @PostMapping("/{operation}")
    public Mono<Map<String, Object>> operation(
            @PathVariable String operation,
            @RequestBody Map<String, Object> request,
            Authentication auth) {
        String actor = requiredActor(auth);
        Action action = OPERATIONS.get(operation);
        if (action == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return run(action, mapper.valueToTree(request), actor);
    }

    private Mono<Map<String, Object>> run(Action action, JsonNode input, String actor) {
        return Mono.fromCallable(() -> worker.invoke(action, input, actor))
                .map(response -> mapper.convertValue(response, RESPONSE))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(TableSyncWorker.Failure.class, AdminTableSyncController::responseError);
    }

    private static String requiredActor(Authentication auth) {
        if (auth == null || !auth.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅全局管理员可管理表同步。");
        return auth.getName();
    }

    private static ResponseStatusException responseError(TableSyncWorker.Failure failure) {
        String code = failure.getMessage();
        HttpStatus status =
                switch (code) {
                    case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "TARGET_EXISTS",
                            "ALREADY_QUEUED",
                            "SCHEMA_CHANGED",
                            "TARGET_ENDPOINT_LOCKED" ->
                            HttpStatus.CONFLICT;
                    case "NOT_CONFIGURED",
                            "CREDENTIAL_ERROR",
                            "TARGET_SETTINGS_ERROR",
                            "DATABASE_ERROR",
                            "WORKER_UNAVAILABLE",
                            "WORKER_TIMEOUT" ->
                            HttpStatus.SERVICE_UNAVAILABLE;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return new ResponseStatusException(
                status, MESSAGES.getOrDefault(code, "无法连接表同步服务，请检查执行环境与数据库配置。"));
    }
}
