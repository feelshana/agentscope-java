package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.session.SessionTurnParser.TurnEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnswerQueryMemoryTest {
    @TempDir Path home;

    @Test
    void officialReceiptSelectsOnlySuccessfulMarkedQueriesOfCurrentOwner() {
        var groups = mock(DatasetGroupService.class);
        when(groups.listGroups("alice"))
                .thenReturn(List.of(new DatasetGroupEntity("g", "alice", "销售", "")));
        var service =
                new AnswerQueryMemory(
                        new WrenProperties("wren", home.toString(), "test", "duckdb", 30),
                        (cwd, timeout, args) -> new WrenCli.Result(0, ""),
                        groups);
        var turns = new ArrayList<TurnEntry>();
        turns.add(new TurnEntry("u", null, "USER", "全年营收是多少", 1, null, null, null));
        String input =
                "{\"group_id\":\"g\",\"tool_name\":\"run_sql\",\"arguments\":{\"sql\":\"SELECT 1"
                        + " LIMIT 1\"},\"answer_question\":\"全年营收是多少\"}";
        String receipt =
                "[WREN_QUERY_RECEIPT]{\"groupId\":\"g\",\"question\":\"全年营收是多少\",\"sql\":\"SELECT 1"
                        + " LIMIT 1\",\"limit\":1000,\"tool\":\"run_sql\"}\n";
        turns.add(
                new TurnEntry("q", null, "TOOL", null, 1, "wren_call_tool", input, null, "query1"));
        turns.add(
                new TurnEntry(
                        "r",
                        null,
                        "TOOL",
                        null,
                        1,
                        "wren_call_tool",
                        null,
                        "{\"rows\":[1]}\n" + receipt,
                        "query1"));
        turns.add(new TurnEntry("a", null, "ASSISTANT", "结果为 1", 1, null, null, null));
        assertEquals(1, service.candidates("alice", "a", turns).size());
        assertEquals(
                "SELECT 1 LIMIT 1", service.candidates("alice", "a", turns).get(0).memorySql());
        turns.set(
                2,
                new TurnEntry(
                        "r",
                        null,
                        "TOOL",
                        null,
                        1,
                        "wren_call_tool",
                        null,
                        receipt.replace(
                                "\"tool\":\"run_sql\"",
                                "\"tool\":\"run_sql\",\"queryType\":\"DIAGNOSTIC\""),
                        "query1"));
        assertTrue(service.candidates("alice", "a", turns).isEmpty());
        turns.set(
                2,
                new TurnEntry(
                        "r",
                        null,
                        "TOOL",
                        null,
                        1,
                        "wren_call_tool",
                        null,
                        "error: " + receipt,
                        "query1"));
        assertTrue(service.candidates("alice", "a", turns).isEmpty());
        turns.set(
                2,
                new TurnEntry(
                        "r",
                        null,
                        "TOOL",
                        null,
                        1,
                        "wren_call_tool",
                        null,
                        receipt.replace("\"groupId\":\"g\"", "\"groupId\":\"foreign\""),
                        "query1"));
        assertTrue(service.candidates("alice", "a", turns).isEmpty());
        turns.set(
                2,
                new TurnEntry(
                        "r",
                        null,
                        "TOOL",
                        null,
                        1,
                        "wren_call_tool",
                        null,
                        receipt,
                        "different-call"));
        assertTrue(service.candidates("alice", "a", turns).isEmpty());
        turns.set(
                2,
                new TurnEntry(
                        "r", null, "TOOL", null, 1, "wren_call_tool", null, receipt, "query1"));
        turns.set(
                1,
                new TurnEntry(
                        "q",
                        null,
                        "TOOL",
                        null,
                        1,
                        "wren_call_tool",
                        input.replace(",\"answer_question\":\"全年营收是多少\"", ""),
                        null,
                        "query1"));
        assertTrue(service.candidates("alice", "a", turns).isEmpty());
    }

    @Test
    void successfulBusinessChineseQueryIsSelectedWithoutDeclarationTool() {
        DatasetGroupService groups = mock(DatasetGroupService.class);
        var group = new DatasetGroupEntity("g", "alice", "销售", "");
        when(groups.listGroups("alice")).thenReturn(List.of(group));
        var service =
                new AnswerQueryMemory(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""),
                        groups);
        String sql = "SELECT SUM(amount) FROM orders";
        String id = AnswerQueryMemory.receiptId("g", "销售额是多少", sql, 10, "BUSINESS");
        var turns = new ArrayList<TurnEntry>();
        turns.add(new TurnEntry("u", null, "USER", "请查销售额", 1, null, null, null));
        turns.add(
                new TurnEntry(
                        "q",
                        null,
                        "TOOL",
                        null,
                        1,
                        "wren_run_sql",
                        "{\"question\":\"销售额是多少\",\"sql\":\""
                                + sql
                                + "\",\"limit\":10,\"query_type\":\"BUSINESS\"}",
                        null));
        turns.add(
                new TurnEntry(
                        "r",
                        null,
                        "TOOL",
                        null,
                        1,
                        "wren_run_sql",
                        null,
                        "## wren 语义查询结果\n答案查询编号：`" + id + "`"));
        turns.add(new TurnEntry("a", null, "ASSISTANT", "结果为 100", 1, null, null, null));
        assertEquals(1, service.candidates("alice", "a", turns).size());
    }

    @Test
    void diagnosticAndNonChineseQuestionsAreExcluded() {
        DatasetGroupService groups = mock(DatasetGroupService.class);
        when(groups.listGroups("alice"))
                .thenReturn(List.of(new DatasetGroupEntity("g", "alice", "销售", "")));
        var service =
                new AnswerQueryMemory(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""),
                        groups);
        String sql = "SELECT 1";
        var turns = new ArrayList<TurnEntry>();
        turns.add(new TurnEntry("u", null, "USER", "test", 1, null, null, null));
        turns.add(
                new TurnEntry(
                        "q",
                        null,
                        "TOOL",
                        null,
                        1,
                        "wren_run_sql",
                        "{\"question\":\"test\",\"sql\":\"SELECT"
                                + " 1\",\"query_type\":\"DIAGNOSTIC\"}",
                        null));
        turns.add(
                new TurnEntry("r", null, "TOOL", null, 1, "wren_run_sql", null, "## wren 语义查询结果"));
        turns.add(new TurnEntry("a", null, "ASSISTANT", "完成", 1, null, null, null));
        assertTrue(service.candidates("alice", "a", turns).isEmpty());
    }
}
