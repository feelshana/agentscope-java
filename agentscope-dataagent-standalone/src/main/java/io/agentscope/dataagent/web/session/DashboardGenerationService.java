package io.agentscope.dataagent.web.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.DashboardGeneration;
import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Owns the native stream subscription, not an agent loop. Browser polling never starts execution. */
@Service
public class DashboardGenerationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DashboardService dashboards;
    private final DashboardDeletionService deletion;
    private final Map<String, Live> live = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    public record Request(String requestId, String revision, String message, String parentRunId) {}

    public record View(
            String id,
            String status,
            String phase,
            String startedAt,
            String finishedAt,
            String baseRevision,
            String checkpointRevision,
            String resultRevision,
            int autoAttempt,
            String message,
            String answer,
            String error) {}

    private static final class Live {
        final Path file;
        final Sinks.One<Boolean> stop = Sinks.one();
        DashboardGeneration record;
        boolean doneFrame, stopped, nextAnswer;
        long lastSaved;

        Live(Path file, DashboardGeneration record) {
            this.file = file;
            this.record = record;
        }
    }

    public DashboardGenerationService(
            DashboardService dashboards, DashboardDeletionService deletion) {
        this.dashboards = dashboards;
        this.deletion = deletion;
    }

    public View current(String user, String id) {
        return snapshot(user, id).task();
    }

    public record Snapshot(View task, boolean busyElsewhere, boolean checkingFile) {}

    public Snapshot snapshot(String user, String id) {
        return dashboards.withGeneration(
                user,
                id,
                workspace -> {
                    Live running = live.get(id);
                    DashboardGeneration record = null;
                    boolean busyElsewhere = false;
                    if (running != null)
                        synchronized (running) {
                            if (user.equals(running.record.userId())) record = running.record;
                            else busyElsewhere = running.record.active();
                        }
                    if (record == null) record = ownRecord(workspace.file(), user);
                    if (record == null || !user.equals(record.userId()))
                        return new Snapshot(null, busyElsewhere, dashboards.checkingDraft(id));
                    record = reconcile(workspace.file(), record, user, id);
                    if (running != null
                            && user.equals(running.record.userId())
                            && running.record.id().equals(record.id())
                            && !record.active())
                        synchronized (running) {
                            try {
                                saveCurrent(running.file, record);
                                live.remove(id, running);
                            } catch (RuntimeException ignored) {
                                /* Keep terminal status visible until persistence recovers. */
                            }
                        }
                    return new Snapshot(view(record), busyElsewhere, dashboards.checkingDraft(id));
                });
    }

    /** Admission and initial journal write share the asset lock, including idempotency. */
    public View start(
            String user,
            String id,
            Request request,
            BiFunction<String, String, Flux<String>> execution) {
        if (request == null
                || request.requestId() == null
                || !request.requestId().matches("[a-fA-F0-9-]{36}")
                || request.message() == null
                || request.message().isBlank()
                || request.message().length() > 16000)
            throw new DatasetException("请提供有效的生成请求，需求最多16000字", 400);
        final Live[] admitted = new Live[1];
        final String[] group = new String[1];
        View response =
                dashboards.withGeneration(
                        user,
                        id,
                        workspace -> {
                            var previous = read(workspace.file());
                            if (previous != null)
                                previous = reconcile(workspace.file(), previous, user, id);
                            var own = ownRecord(workspace.file(), user);
                            if (own != null && own.id().equals(request.requestId()))
                                return view(own);
                            if (previous != null
                                    && previous.id().equals(request.requestId())
                                    && previous.userId().equals(user)) return view(previous);
                            if (previous != null && previous.active())
                                throw new DatasetException("此看板正在生成，请等待当前任务完成", 409);
                            dashboards.requireCheckIdle(id);
                            deletion.requireIdle(id);
                            var current = dashboards.get(user, id, true);
                            if (!current.revision().equals(request.revision()))
                                throw new DatasetException("草稿已变化，请刷新后再生成", 409);
                            int attempt = 0;
                            String checkpoint;
                            if (request.parentRunId() != null) {
                                if (previous == null
                                        || !previous.id().equals(request.parentRunId())
                                        || !previous.userId().equals(user)
                                        || !"SUCCEEDED".equals(previous.status())
                                        || previous.autoAttempt() != 0
                                        || !current.revision().equals(previous.resultRevision()))
                                    throw new DatasetException("本轮自动修复已结束，请手动提出新的修改", 409);
                                attempt = 1;
                                checkpoint = previous.checkpointRevision();
                            } else
                                checkpoint =
                                        dashboards
                                                .checkpoint(
                                                        user,
                                                        id,
                                                        current.revision(),
                                                        () -> deletion.requireIdle(id))
                                                .revision();
                            var record =
                                    new DashboardGeneration(
                                            request.requestId(),
                                            user,
                                            dashboards.generationProcess(),
                                            "QUEUED",
                                            "等待开始",
                                            Instant.now().toString(),
                                            null,
                                            current.revision(),
                                            checkpoint,
                                            null,
                                            attempt,
                                            request.message(),
                                            "",
                                            null);
                            write(workspace.file(), record);
                            var running = new Live(workspace.file(), record);
                            live.put(id, running);
                            admitted[0] = running;
                            group[0] = workspace.asset().groupId();
                            return view(record);
                        });
        if (admitted[0] != null) {
            Live running = admitted[0];
            Flux.defer(() -> execution.apply(group[0], running.record.message()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .publishOn(Schedulers.boundedElastic())
                    .takeUntilOther(running.stop.asMono())
                    .subscribe(
                            data -> event(running, data),
                            error -> finish(user, id, running, error),
                            () -> finish(user, id, running, null));
        }
        return response;
    }

    public View stop(String user, String id, String requestId) {
        Live running =
                dashboards.withGeneration(
                        user,
                        id,
                        workspace -> {
                            var record = read(workspace.file());
                            if (record == null || !user.equals(record.userId()))
                                throw new DatasetException("任务不存在", 404);
                            if (!record.id().equals(requestId))
                                throw new DatasetException("任务已变化，请刷新", 409);
                            return live.get(id);
                        });
        if (running != null) {
            synchronized (running) {
                if ("CHECKING".equals(running.record.status()))
                    throw new DatasetException("生成已结束，正在完成草稿文件检查", 409);
                running.stopped = true;
            }
            running.stop.tryEmitValue(true);
        }
        return current(user, id);
    }

    private void event(Live running, String data) {
        if (data == null) return;
        synchronized (running) {
            if (!running.record.active()) return;
            try {
                JsonNode event = JSON.readTree(data);
                String type = event.path("type").asText();
                var record = running.record;
                String phase = record.phase(), answer = record.answer(), error = record.error();
                if ("token".equals(type)) {
                    if (running.nextAnswer) {
                        answer = "";
                        running.nextAnswer = false;
                    }
                    answer += event.path("data").asText("");
                    if (answer.length() > 64000) answer = answer.substring(0, 64000);
                } else if ("capacity".equals(type))
                    phase = limit(event.path("message").asText("正在等待执行名额…"), 100);
                else if ("tool_call".equals(type)) {
                    running.nextAnswer = true;
                    String tool = event.path("toolName").asText();
                    phase =
                            tool.equals("write_file") || tool.equals("edit_file")
                                    ? "编写或修改页面"
                                    : tool.equals("wren_genbi") ? "处理看板工程" : "检查数据与查询";
                } else if ("error".equals(type))
                    error = limit(event.path("error").asText("生成失败，请重试"), 500);
                else if ("done".equals(type)) running.doneFrame = true;
                running.record = record.progress("RUNNING", phase, answer, error, null, null);
                long now = System.currentTimeMillis();
                if (!"token".equals(type) || now - running.lastSaved > 1000) {
                    saveCurrent(running.file, running.record);
                    running.lastSaved = now;
                }
            } catch (IOException e) {
                throw new DatasetException("读取任务进度失败", e);
            }
        }
    }

    private void finish(String user, String id, Live running, Throwable failure) {
        String revision = null;
        boolean checking = false;
        try {
            var draft = dashboards.get(user, id, true);
            revision = draft.revision();
            synchronized (running) {
                var task = running.record;
                if (!task.active()) return;
                checking =
                        failure == null
                                && task.error() == null
                                && running.doneFrame
                                && !running.stopped
                                && !shuttingDown
                                && !draft.verified()
                                && !revision.equals(task.baseRevision());
                if (checking) {
                    running.record =
                            task.progress(
                                    "CHECKING", "正在检查草稿文件", task.answer(), null, null, revision);
                    saveCurrent(running.file, running.record);
                }
            }
            if (checking
                    && !dashboards
                            .checkGeneratedDraft(user, id, revision, running.record.id())
                            .verified()) throw new DatasetException("当前草稿尚未通过文件检查，请查看草稿状态", 409);
        } catch (RuntimeException e) {
            if (failure == null) failure = e;
        }
        synchronized (running) {
            if (!running.record.active()) return;
            String error =
                    failure == null ? running.record.error() : limit(failure.getMessage(), 500);
            String status =
                    shuttingDown
                            ? "INTERRUPTED"
                            : running.stopped
                                    ? "CANCELLED"
                                    : error != null || !running.doneFrame
                                            ? (checking ? "CHECK_FAILED" : "FAILED")
                                            : "SUCCEEDED";
            if ("FAILED".equals(status) && error == null) error = "执行连接结束，本轮未完成，请查看草稿后重试";
            if ("INTERRUPTED".equals(status)) error = "服务已停止，本轮生成中断，请查看当前草稿后重试。";
            String phase =
                    switch (status) {
                        case "INTERRUPTED" -> "服务重启，本轮已中断";
                        case "CANCELLED" -> "本轮已停止";
                        case "CHECK_FAILED" -> "草稿文件检查未通过";
                        case "SUCCEEDED" ->
                                !running.record.baseRevision().equals(revision)
                                        ? "草稿已通过文件检查"
                                        : "本轮对话已结束";
                        default -> "本轮生成失败";
                    };
            running.record =
                    running.record.progress(
                            status,
                            phase,
                            running.record.answer(),
                            error,
                            Instant.now().toString(),
                            revision);
            try {
                saveCurrent(running.file, running.record);
                live.remove(id, running);
            } catch (RuntimeException persistFailure) {
                running.record =
                        running.record.progress(
                                "FAILED",
                                "任务状态保存失败",
                                running.record.answer(),
                                "任务已结束，但状态保存失败。请先查看草稿，稍后重新读取任务。",
                                Instant.now().toString(),
                                revision);
            }
        }
    }

    private DashboardGeneration reconcile(
            Path file, DashboardGeneration record, String user, String id) {
        if (record.active() && !dashboards.generationProcess().equals(record.processId())) {
            String revision = null;
            try {
                revision = dashboards.get(user, id, true).revision();
            } catch (DatasetException ignored) {
                // An unreadable draft must not keep an abandoned task active.
            }
            record =
                    record.progress(
                            "INTERRUPTED",
                            "服务重启，本轮已中断",
                            record.answer(),
                            "服务已重启，请查看当前草稿后重试。",
                            Instant.now().toString(),
                            revision);
            saveCurrent(file, record);
        }
        return record;
    }

    private static DashboardGeneration read(Path file) {
        WrenProjectAccess.assertNoLinks(file);
        if (!Files.isRegularFile(file)) return null;
        try {
            if (Files.size(file) > DashboardService.MAX_HTML_BYTES)
                throw new DatasetException("生成任务记录过大", 409);
            return JSON.readValue(Files.readString(file), DashboardGeneration.class);
        } catch (IOException e) {
            throw new DatasetException("读取任务记录失败", e);
        }
    }

    private static DashboardGeneration ownRecord(Path file, String user) {
        var record = read(file);
        return record != null && user.equals(record.userId())
                ? record
                : read(historyFile(file, user));
    }

    private static Path historyFile(Path file, String user) {
        try {
            String key =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    user.getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));
            return file.resolveSibling("generation-" + key + ".json");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void saveCurrent(Path file, DashboardGeneration record) {
        write(file, record);
        if (!record.active()) write(historyFile(file, record.userId()), record);
    }

    private static void write(Path file, DashboardGeneration record) {
        WrenProjectAccess.assertNoLinks(file);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        WrenProjectAccess.assertNoLinks(temporary);
        try {
            if (!Files.isDirectory(file.getParent()))
                throw new DatasetException("看板已删除，无法更新任务", 404);
            Files.writeString(temporary, JSON.writeValueAsString(record));
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new DatasetException("保存任务状态失败", e);
        }
    }

    private static View view(DashboardGeneration record) {
        return new View(
                record.id(),
                record.status(),
                record.phase(),
                record.startedAt(),
                record.finishedAt(),
                record.baseRevision(),
                record.checkpointRevision(),
                record.resultRevision(),
                record.autoAttempt(),
                record.message(),
                record.answer(),
                record.error());
    }

    private static String limit(String text, int size) {
        return text == null ? "生成失败，请重试" : text.substring(0, Math.min(text.length(), size));
    }

    @PreDestroy
    void close() {
        shuttingDown = true;
        for (Live running : live.values()) running.stop.tryEmitValue(true);
    }
}
