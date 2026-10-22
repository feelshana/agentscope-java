package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftReviewServiceTest {
    @TempDir Path home;
    private Path root;
    private Path published;
    private DatasetGroupService groups;
    private ModelingActivity activity;
    private DraftReviewService service;
    private BusinessDocumentService documents;

    @BeforeEach
    void setup() throws Exception {
        var workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""));
        root = workspace.workspaceRoot("g1");
        published = root.getParent().resolve("published");
        groups = mock(DatasetGroupService.class);
        activity = new ModelingActivity();
        var publisher = mock(MdlPublishService.class);
        when(publisher.preview("g1"))
                .thenAnswer(
                        i ->
                                new MdlPublishService.MdlPreview(
                                        files(root),
                                        files(published),
                                        List.of(),
                                        "PUBLISHED",
                                        1,
                                        null,
                                        true));
        service = new DraftReviewService(groups, workspace, publisher, activity);
        documents = new BusinessDocumentService(workspace, groups);
        write(published, "models/orders/metadata.yml", model("订单"));
        write(published, "knowledge/rules/general.md", "# 规则\n");
        write(root, "models/orders/metadata.yml", model("订单表，一行一笔订单"));
        write(root, "knowledge/rules/general.md", "# 规则\n- 营收排除内部账号\n");
        write(root, "knowledge/caveats/pending.md", "待确认：时区\n");
    }

    private static String model(String description) {
        return "name: orders\ncolumns:\n- name: amount\n  properties:\n    description: "
                + description
                + "\n- name: status\n  properties:\n    description: 状态\n";
    }

    private static void write(Path base, String path, String content) throws IOException {
        Path file = base.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static List<MdlPublishService.MdlFile> files(Path base) throws IOException {
        if (!Files.isDirectory(base)) return List.of();
        List<MdlPublishService.MdlFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = base.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".platform/")) continue;
                out.add(new MdlPublishService.MdlFile(rel, Files.readString(p)));
            }
        }
        return out;
    }

    @Test
    void changesComeFromTheDiffAndCarryJournalSourcesAndAuthors() {
        DraftChangeLog.record(
                root, "knowledge/rules/general.md", ModelingTurnPurpose.CHAT, "alice");
        DraftChangeLog.record(root, "knowledge/rules/general.md", ModelingTurnPurpose.AUTO, "bob");
        var review = service.review("alice", "g1");
        assertEquals(3, review.changes().size());
        var rules = find(review, "knowledge/rules/general.md");
        assertEquals("RULE", rules.category());
        assertEquals("MODIFIED", rules.kind());
        assertEquals(List.of("chat", "auto"), rules.sources());
        assertEquals(List.of("alice", "bob"), rules.authors());
        assertTrue(rules.userContentChanged());
        var model = find(review, "models/orders/metadata.yml");
        assertEquals("修改列：amount", model.summary());
        assertEquals(List.of("platform"), model.sources());
        assertEquals("ADDED", find(review, "knowledge/caveats/pending.md").kind());
        assertEquals(1, review.changesByOthers());
        assertFalse(review.assistantRunning());
    }

    @Test
    void revertRestoresThePublishedFileOrRemovesADraftOnlyFile() throws Exception {
        DraftChangeLog.record(root, "knowledge/caveats/pending.md", ModelingTurnPurpose.AUTO, "a");
        var review = service.revert("alice", "g1", "knowledge/rules/general.md");
        assertEquals("# 规则\n", Files.readString(root.resolve("knowledge/rules/general.md")));
        assertEquals(2, review.changes().size());
        service.revert("alice", "g1", "knowledge/caveats/pending.md");
        assertFalse(Files.exists(root.resolve("knowledge/caveats/pending.md")));
        assertTrue(DraftChangeLog.read(root).isEmpty());
        assertThrows(
                DatasetException.class,
                () -> service.revert("alice", "g1", "knowledge/caveats/pending.md"));
    }

    @Test
    void discardMakesTheDraftEqualToTheSnapshot() {
        DraftChangeLog.record(root, "models/orders/metadata.yml", ModelingTurnPurpose.AUTO, "a");
        var review = service.discard("alice", "g1");
        assertTrue(review.changes().isEmpty());
        assertTrue(DraftChangeLog.read(root).isEmpty());
    }

    @Test
    void runningAssistantBlocksRevertAndDiscard() {
        activity.begin("g1");
        assertTrue(service.review("alice", "g1").assistantRunning());
        assertThrows(
                DatasetException.class,
                () -> service.revert("alice", "g1", "knowledge/rules/general.md"));
        assertThrows(DatasetException.class, () -> service.discard("alice", "g1"));
        activity.end("g1");
        assertFalse(activity.running("g1"));
        service.discard("alice", "g1");
    }

    @Test
    void foreignUserSeesNothingAndChangesNothing() throws Exception {
        when(groups.getGroup("mallory", "g1")).thenThrow(new DatasetException("不存在", 404));
        assertThrows(DatasetException.class, () -> service.review("mallory", "g1"));
        assertThrows(DatasetException.class, () -> service.discard("mallory", "g1"));
        assertThrows(DatasetException.class, () -> documents.put("mallory", "g1", "口径.md", "内容"));
        assertTrue(Files.readString(root.resolve("knowledge/rules/general.md")).contains("营收"));
        assertFalse(Files.exists(root.resolve("raw/docs")));
    }

    @Test
    void documentsAreDraftChangesWithHistoryAndPublishStatus() throws Exception {
        documents.put("alice", "g1", "业务字典.docx", "v1");
        assertTrue(BusinessDocumentService.hasDocuments(root));
        assertEquals("ADDED", documents.list("alice", "g1").get(0).status());
        write(published, "raw/docs/业务字典.md", "v1");
        assertEquals("UNCHANGED", documents.list("alice", "g1").get(0).status());
        var replaced = documents.put("alice", "g1", "业务字典.md", "v2");
        assertEquals("MODIFIED", replaced.status());
        assertEquals(1, replaced.previousVersions());
        var docChange = find(service.review("alice", "g1"), "raw/docs/业务字典.md");
        assertEquals("DOCUMENT", docChange.category());
        assertEquals(List.of("upload"), docChange.sources());
        documents.delete("alice", "g1", "业务字典.md");
        assertEquals("DELETED", documents.list("alice", "g1").get(0).status());
        assertFalse(BusinessDocumentService.hasDocuments(root));
        // Directory parts are stripped, so a name can never leave raw/docs/.
        assertEquals("x.md", BusinessDocumentService.fileName("../x"));
        assertThrows(DatasetException.class, () -> BusinessDocumentService.fileName("a/<x>"));
        assertThrows(DatasetException.class, () -> BusinessDocumentService.fileName(".hidden"));
        assertEquals("指标 口径.md", BusinessDocumentService.fileName("dir/指标 口径.txt"));
    }

    @Test
    void userEditsAreValidatedJournaledAndBlockedWhileTheAssistantRuns() throws Exception {
        var review = service.edit("alice", "g1", "knowledge/rules/general.md", "# 规则\n- 用户数去重\n");
        assertEquals(
                "# 规则\n- 用户数去重\n", Files.readString(root.resolve("knowledge/rules/general.md")));
        var change = find(review, "knowledge/rules/general.md");
        assertEquals(List.of("edit"), change.sources());
        assertThrows(
                DatasetException.class,
                () -> service.edit("alice", "g1", "models/orders/metadata.yml", "name: [broken\n"));
        assertThrows(
                DatasetException.class,
                () -> service.edit("alice", "g1", "wren_project.yml", "name: x\n"));
        assertThrows(
                DatasetException.class, () -> service.edit("alice", "g1", "raw/docs/口径.md", "x"));
        assertThrows(
                DatasetException.class,
                () -> service.edit("alice", "g1", "knowledge/sql/q1.md", "x"));
        activity.begin("g1");
        assertThrows(
                DatasetException.class,
                () -> service.edit("alice", "g1", "knowledge/rules/general.md", "x"));
        activity.end("g1");
        // An automatic turn rewriting the user's edit is flagged for review.
        DraftChangeLog.record(
                root, "knowledge/rules/general.md", ModelingTurnPurpose.DOCUMENT, "bob");
        assertTrue(
                find(service.review("alice", "g1"), "knowledge/rules/general.md")
                        .userContentChanged());
    }

    @Test
    void sampleMaterialAndConfirmedPairsAreListedButNotRevertible() throws Exception {
        write(root, "knowledge/sql/q1.md", "pair");
        write(root, "raw/data-samples.md", "samples");
        var review = service.review("alice", "g1");
        assertFalse(find(review, "knowledge/sql/q1.md").revertible());
        assertEquals("EXAMPLE", find(review, "knowledge/sql/q1.md").category());
        assertFalse(find(review, "raw/data-samples.md").revertible());
        assertThrows(
                DatasetException.class, () -> service.revert("alice", "g1", "raw/data-samples.md"));
    }

    private static DraftReviewService.Change find(DraftReviewService.Review review, String path) {
        return review.changes().stream()
                .filter(c -> c.path().equals(path))
                .findFirst()
                .orElseThrow();
    }
}
