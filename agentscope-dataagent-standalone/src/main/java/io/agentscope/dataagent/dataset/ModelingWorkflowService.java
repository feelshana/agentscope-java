package io.agentscope.dataagent.dataset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Derives the user journey from tenant-owned assets; no independent mutable stage state. */
@Service
public class ModelingWorkflowService {
    private final DatasetGroupService groups;
    private final MdlPublishService publisher;
    private final MdlCatalog catalog;
    private final MdlQuestionStore questions;
    private final ModelingWorkflowStore checks;
    private final MdlWorkspaceService workspace;

    public ModelingWorkflowService(
            DatasetGroupService groups,
            MdlPublishService publisher,
            MdlCatalog catalog,
            MdlQuestionStore questions,
            ModelingWorkflowStore checks,
            MdlWorkspaceService workspace) {
        this.groups = groups;
        this.publisher = publisher;
        this.catalog = catalog;
        this.questions = questions;
        this.checks = checks;
        this.workspace = workspace;
    }

    public record Action(String type, String label, String message) {}

    public record Blocker(String code, String questionId, String message) {}

    public record Change(String path, String label, String kind) {}

    public record QuestionSummary(
            int total,
            int confirmed,
            int incomplete,
            int needsValidation,
            int awaitingConfirmation) {}

    public record Workflow(
            String groupId,
            String stage,
            String mdlState,
            int publishedVersion,
            boolean queryAvailable,
            boolean draftChanged,
            String engineeringStatus,
            String engineeringCheckedAt,
            QuestionSummary questionSummary,
            List<Change> changedAssets,
            List<Blocker> blockers,
            Action nextAction,
            boolean canPublish) {}

    public Workflow snapshot(DatasetScope scope, String groupId) {
        if (scope == null || scope.ownerId() == null || scope.ownerId().isBlank())
            throw new DatasetException("无法确定租户上下文", 403);
        if (scope.groupIds() != null && !scope.groupIds().contains(groupId))
            throw new DatasetException("知识库不在当前会话范围内", 403);
        groups.getGroup(scope.ownerId(), groupId);
        return workspace.withWorkspaceLock(groupId, () -> derive(scope, groupId));
    }

    private Workflow derive(DatasetScope scope, String groupId) {
        var group = groups.getGroup(scope.ownerId(), groupId);
        boolean hasData = !groups.listDatasets(scope.ownerId(), groupId).isEmpty();
        var preview = publisher.preview(groupId);
        boolean available =
                hasData && group.getMdlVersion() > 0 && catalog.load(groupId).isPresent();
        List<Change> changes = changes(preview);
        var reviews = questions.list(groupId);
        List<Blocker> blockers = new ArrayList<>();
        int confirmed = 0, incomplete = 0, needsValidation = 0, awaiting = 0;
        for (var review : reviews) {
            var question = review.question();
            if (review.coverage() != null && !review.coverage().ready()) {
                incomplete++;
                blockers.add(
                        new Blocker("QUESTION_ASSET", question.id(), review.coverage().message()));
                continue;
            }
            if ("CONFIRMED".equals(review.status())) {
                confirmed++;
                continue;
            }
            if (question.definition().isBlank() || question.sql().isBlank()) {
                incomplete++;
                blockers.add(
                        new Blocker(
                                "QUESTION_INCOMPLETE",
                                question.id(),
                                "请通过对话补齐问题口径与查询：" + question.question()));
            } else if ("EXECUTED".equals(review.status()) && !review.validation().truncated()) {
                awaiting++;
                blockers.add(
                        new Blocker(
                                "QUESTION_CONFIRMATION",
                                question.id(),
                                "请审阅并确认实际结果：" + question.question()));
            } else {
                needsValidation++;
                blockers.add(
                        new Blocker(
                                "QUESTION_VALIDATION",
                                question.id(),
                                "请重新验证问题（" + review.status() + "）：" + question.question()));
            }
        }
        var check = checks.current(groupId);
        String engineering =
                check == null
                        ? (!preview.changed() && available ? "PASSED" : "NOT_CHECKED")
                        : check.ok() ? "PASSED" : "FAILED";
        if (!hasData) blockers.add(0, new Blocker("DATA_REQUIRED", null, "请先选择数据表或上传数据文件"));
        else if (preview.files().isEmpty())
            blockers.add(0, new Blocker("MODEL_REQUIRED", null, "基础模型尚未生成，请初始化或重试"));
        if (hasData && !"PASSED".equals(engineering)) {
            if (check != null && !check.ok()) {
                blockers.add(new Blocker("ENGINEERING_FAILED", null, "模型结构校验未通过，请修正后重新校验"));
                check.issues().stream()
                        .filter(i -> "error".equals(i.severity()))
                        .forEach(
                                i ->
                                        blockers.add(
                                                new Blocker(
                                                        "ENGINEERING_FAILED", null, i.message())));
            } else blockers.add(new Blocker("ENGINEERING_REQUIRED", null, "请校验当前模型结构，再进行结果确认与发布"));
        }
        QuestionSummary summary =
                new QuestionSummary(
                        reviews.size(), confirmed, incomplete, needsValidation, awaiting);
        String stage;
        Action action;
        if (!hasData) {
            stage = "DATA_PREPARATION";
            action = new Action("PREPARE_DATA", "选择表或上传数据", "接入数据后系统自动生成基础模型，成功后即可问数。");
        } else if (!available) {
            stage = "DATA_PREPARATION";
            action = new Action("INITIALIZE", "生成或重试基础模型", "基础模型尚不可查询，请先完成初始化；已有草稿问题可能需要先处理。");
        } else if (!preview.changed() && incomplete == 0 && needsValidation == 0 && awaiting == 0) {
            stage = "COMPLETE";
            action =
                    reviews.isEmpty()
                            ? new Action(
                                    "ADD_QUESTIONS",
                                    "添加分析问题",
                                    "基础模型已可问数。先批量添加希望分析的问题，再由助手澄清口径并构建模型。")
                            : new Action("QUERY", "开始问数", "当前确认的业务模型已发布，问数使用已发布版本。");
        } else if (incomplete > 0) {
            stage = "MODELING";
            action = new Action("MODEL", "继续澄清与建模", "补齐已有问题的口径和查询，不必重新填写问题。");
        } else if (!"PASSED".equals(engineering) || needsValidation > 0) {
            stage = "VALIDATION";
            action = new Action("VALIDATE", "校验模型并验证问题", "先检查模型结构，再执行已有问题；执行成功后仍需人员确认。");
        } else if (awaiting > 0) {
            stage = "CONFIRMATION";
            action = new Action("CONFIRM", "审阅并确认结果", "检查实际表格、业务口径和查询，再确认或退回。");
        } else {
            stage = "PUBLICATION";
            action =
                    new Action(
                            "PUBLISH",
                            "查看发布摘要",
                            reviews.isEmpty()
                                    ? "请审阅本次模型变更并发布；添加分析问题可进一步验证业务口径。"
                                    : "全部分析问题已确认，请审阅本次变更并发布新版本。");
        }
        return new Workflow(
                groupId,
                stage,
                group.getMdlState(),
                group.getMdlVersion(),
                available,
                preview.changed(),
                engineering,
                check == null ? null : check.checkedAt(),
                summary,
                changes,
                blockers,
                action,
                hasData && !preview.files().isEmpty() && blockers.isEmpty());
    }

    private static List<Change> changes(MdlPublishService.MdlPreview preview) {
        Map<String, String> oldFiles = new LinkedHashMap<>();
        preview.publishedFiles().forEach(f -> oldFiles.put(f.path(), f.content()));
        List<Change> changes = new ArrayList<>();
        for (var file : preview.files()) {
            String old = oldFiles.remove(file.path());
            if (!file.content().equals(old))
                changes.add(
                        new Change(
                                file.path(),
                                label(file.path()),
                                old == null ? "ADDED" : "MODIFIED"));
        }
        oldFiles.keySet().forEach(p -> changes.add(new Change(p, label(p), "REMOVED")));
        return changes;
    }

    private static String label(String path) {
        if (path.startsWith("knowledge/questions/")) return "分析问题：" + name(path);
        if (path.startsWith("knowledge/sql/")) return "确认示例：" + name(path);
        if (path.startsWith("knowledge/rules/")) return "业务口径与规则";
        if (path.startsWith("relationships")) return "数据之间的关联关系";
        if (path.startsWith("cubes/")) return "业务指标：" + path.split("/")[1];
        if (path.startsWith("views/")) return "业务视图：" + path.split("/")[1];
        if (path.startsWith("models/")) return "模型字段与定义：" + path.split("/")[1];
        return "模型工程设置";
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.[^.]+$", "");
    }
}
