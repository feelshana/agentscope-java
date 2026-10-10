package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Platform-owned, fingerprinted engineering checks used for guidance, never to bypass publication. */
@Component
public class ModelingWorkflowStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MdlWorkspaceService workspace;
    private final MdlQuestionStore questions;

    public ModelingWorkflowStore(MdlWorkspaceService workspace, MdlQuestionStore questions) {
        this.workspace = workspace;
        this.questions = questions;
    }

    public record Check(
            String modelHash,
            boolean ok,
            String checkedAt,
            List<MdlPublishService.MdlIssue> issues) {}

    public void record(String groupId, MdlPublishService.MdlValidation validation) {
        Path target = path(groupId);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".workflow-", ".tmp");
            try {
                JSON.writeValue(
                        temporary.toFile(),
                        new Check(
                                questions.modelHash(workspace.workspaceRoot(groupId)),
                                        validation.ok(),
                                Instant.now().toString(), validation.issues()));
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new DatasetException("保存工程校验状态失败：" + e.getMessage(), e);
        }
    }

    public Check current(String groupId) {
        Path target = path(groupId);
        if (!Files.isRegularFile(target)) return null;
        try {
            Check check = JSON.readValue(target.toFile(), Check.class);
            return questions.modelHash(workspace.workspaceRoot(groupId)).equals(check.modelHash())
                    ? check
                    : null;
        } catch (IOException e) {
            throw new DatasetException("读取工程校验状态失败：" + e.getMessage(), e);
        }
    }

    private Path path(String groupId) {
        return workspace.workspaceRoot(groupId).resolve(".platform/workflow/engineering.json");
    }
}
