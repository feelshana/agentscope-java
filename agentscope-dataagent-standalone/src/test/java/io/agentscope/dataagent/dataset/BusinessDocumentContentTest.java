package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** spec117: the workbench reads one document's text, only by a listed name and with MANAGE. */
class BusinessDocumentContentTest {
    @TempDir Path home;

    @Test
    void readsOnlyListedDocumentsOfAManagedKnowledgeBase() throws Exception {
        var workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""));
        DatasetGroupService groups = mock(DatasetGroupService.class);
        var service = new BusinessDocumentService(workspace, groups);
        Path docs = workspace.workspaceRoot("g1").resolve(BusinessDocumentService.DIRECTORY);
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("业务口径.md"), "# 口径\n净销售额=实付-退款", StandardCharsets.UTF_8);
        Files.writeString(
                workspace.workspaceRoot("g1").resolve("secret.md"), "x", StandardCharsets.UTF_8);

        assertEquals("# 口径\n净销售额=实付-退款", service.content("u1", "g1", "业务口径.md").content());
        assertEquals(
                404,
                assertThrows(
                                DatasetException.class,
                                () -> service.content("u1", "g1", "../../secret.md"))
                        .status());
        assertEquals(
                404,
                assertThrows(DatasetException.class, () -> service.content("u1", "g1", "无.md"))
                        .status());
        when(groups.getGroup("u2", "g1")).thenThrow(new DatasetException("无权管理该知识库", 403));
        assertEquals(
                403,
                assertThrows(DatasetException.class, () -> service.content("u2", "g1", "业务口径.md"))
                        .status());
    }
}
