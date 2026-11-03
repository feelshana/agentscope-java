package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelingConfirmationServiceTest {
    @TempDir Path home;
    private MdlWorkspaceService workspace;
    private DatasetGroupService groups;
    private ModelingConfirmationService service;

    @BeforeEach
    void setup() {
        workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""));
        groups = mock(DatasetGroupService.class);
        service = new ModelingConfirmationService(workspace, groups);
    }

    private ModelingConfirmationService.Confirmation ask(String question) {
        return service.create(
                "g1",
                question,
                "文档只在推广场景要求排除外协",
                List.of("只在推广场景排除", "所有统计都排除"),
                List.of("推广类需求排除外协人员；一般统计不排除。", "所有用户统计默认排除外协人员。"));
    }

    @Test
    void questionsArePrivateToThePlatformAndNeverPublished() {
        var created = ask("一般统计是否排除外协人员？");
        Path stored =
                workspace
                        .workspaceRoot("g1")
                        .resolve(".platform/confirmations/" + created.id() + ".json");
        assertTrue(Files.isRegularFile(stored));
        assertEquals("只在推广场景排除", created.options().get(0).label());
        assertEquals(1, service.list("alice", "g1").size());
    }

    @Test
    void optionsAreValidatedAndDuplicatesRefused() {
        assertThrows(
                DatasetException.class,
                () -> service.create("g1", "问题", "", List.of("唯一选项"), List.of("规则")));
        assertThrows(
                DatasetException.class,
                () -> service.create("g1", "问题", "", List.of("a", "b"), List.of("规则")));
        assertThrows(
                DatasetException.class,
                () -> service.create("g1", "问题", "", List.of("a", " "), List.of("x", "y")));
        ask("一般统计是否排除外协人员？");
        assertThrows(DatasetException.class, () -> ask("一般统计是否排除外协人员？"));
        // 2026-10-22 log: the same relationship was asked twice with different bracketed notes.
        ask("report_visit.project_name → project.project_name 应声明为哪种关系（及多值展开）？");
        assertThrows(
                DatasetException.class,
                () ->
                        ask(
                                "report_visit.project_name → project.project_name"
                                        + " 应声明为哪种关系（user.project 为多值）?"));
        for (int i = 2; i < ModelingConfirmationService.MAX_PENDING; i++) ask("问题 " + i);
        assertThrows(DatasetException.class, () -> ask("再多一个"));
    }

    /** spec110: asking about a question's meaning withdraws the SQL written on a guess. */
    @Test
    void askingAboutAQuestionWithdrawsItsGuessedSql() throws Exception {
        Path question = workspace.workspaceRoot("g1").resolve("knowledge/questions/q-coach-2.yml");
        Files.createDirectories(question.getParent());
        Files.writeString(
                question,
                "question: 活跃用户最常看哪些报表\ndefinition: |-\n  指标：访问次数。\n  假设：活跃用户指全部访问人。\n"
                        + "sql: SELECT report_name, COUNT(*) FROM visits GROUP BY 1\n");
        service.create(
                "g1",
                "活跃用户如何界定？",
                "规则和文档都没有定义",
                List.of("暂不确定，先记为待确认", "区间内有访问的全部用户"),
                List.of("活跃用户定义待确认。", "活跃用户 = 区间内有访问记录的用户。"),
                List.of("q-coach-2"));
        String written = Files.readString(question);
        assertTrue(written.contains("sql: \"\""), written);
        assertTrue(written.contains("假设：待确认口径"), written);
        assertFalse(written.contains("全部访问人"), written);
    }

    /** spec110: a card asked for coach questions hands their ids back with the decision. */
    @Test
    void linkedQuestionsTravelWithTheCardAndItsDecision() {
        var linked =
                service.create(
                        "g1",
                        "高频用户如何界定？",
                        "规则和文档都没有定义",
                        List.of("暂不确定，先记为待确认", "最近 30 天访问超过 10 次"),
                        List.of("高频用户定义待确认。", "高频用户 = 最近 30 天访问次数大于 10 的账号。"),
                        List.of("q-coach-1", "q-coach-1", "../bad"));
        assertEquals(List.of("q-coach-1"), service.list("alice", "g1").get(0).questionIds());
        var decisions =
                service.resolve(
                        "alice",
                        "g1",
                        List.of(
                                new ModelingConfirmationService.Answer(
                                        linked.id(), "高频用户 = 最近 30 天访问次数大于 10 的账号。", false)));
        assertEquals(List.of("q-coach-1"), decisions.get(0).questionIds());
        // Cards asked outside the coach carry no linked questions.
        assertEquals(List.of(), ask("报表是否只算报表类型？").questionIds());
    }

    @Test
    void resolvingReturnsDecisionsAndRemovesQuestions() {
        var chosen = ask("一般统计是否排除外协人員？");
        var dismissed = ask("报表是否只算报表类型？");
        var skipped = ask("时区按北京时间？");
        var decisions =
                service.resolve(
                        "alice",
                        "g1",
                        List.of(
                                new ModelingConfirmationService.Answer(
                                        chosen.id(), "推广类需求排除外协人员；一般统计不排除。", false),
                                new ModelingConfirmationService.Answer(dismissed.id(), null, true),
                                new ModelingConfirmationService.Answer("unknown", "x", false)));
        assertEquals(1, decisions.size());
        assertEquals(chosen.question(), decisions.get(0).question());
        assertEquals(
                List.of(skipped.id()),
                service.list("alice", "g1").stream()
                        .map(ModelingConfirmationService.Confirmation::id)
                        .toList());
        assertThrows(
                DatasetException.class,
                () ->
                        service.resolve(
                                "alice",
                                "g1",
                                List.of(
                                        new ModelingConfirmationService.Answer(
                                                skipped.id(), " ", false))));
    }

    @Test
    void anotherUserCannotSeeOrAnswerTheQuestions() {
        var created = ask("一般统计是否排除外协人员？");
        when(groups.getGroup("mallory", "g1")).thenThrow(new DatasetException("不存在", 404));
        assertThrows(DatasetException.class, () -> service.list("mallory", "g1"));
        assertThrows(
                DatasetException.class,
                () ->
                        service.resolve(
                                "mallory",
                                "g1",
                                List.of(
                                        new ModelingConfirmationService.Answer(
                                                created.id(), "x", false))));
        assertFalse(service.list("alice", "g1").isEmpty());
    }
}
