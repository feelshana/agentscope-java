package io.agentscope.dataagent.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.dataagent.dataset.ModelingConfirmationService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesContextTest {
    @TempDir Path root;

    private void touch(String path) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }

    @Test
    void modelingFilesAreListedSoTheAssistantNeedNotWalkDirectories() throws Exception {
        touch("models/user/metadata.yml");
        touch("relationships.yml");
        touch("knowledge/rules/general.md");
        touch("knowledge/rules/.gitkeep");
        touch("raw/docs/业务规则.md");
        touch(".platform/draft-changes.jsonl");
        touch("target/mdl.json");
        touch("cubes/c.yml");
        String text = WrenProjectContextMiddleware.projectFiles(root);
        assertThat(text)
                .contains(
                        "当前工程文件清单",
                        "models/user/metadata.yml",
                        "relationships.yml",
                        "knowledge/rules/general.md",
                        "raw/docs/业务规则.md",
                        "无需再 list_files")
                .doesNotContain(".platform", "target/", "cubes/", ".gitkeep", "没有业务文档");
        // 2026-10-26: say which folders are empty and that no sampling file exists, so the
        // assistant stops listing folders and globbing for them.
        assertThat(text)
                .contains("空目录（无需查看）：knowledge/caveats、knowledge/glossary", "没有单独的采样文件")
                .doesNotContain("knowledge/rules、");
        Files.delete(root.resolve("raw/docs/业务规则.md"));
        assertThat(WrenProjectContextMiddleware.projectFiles(root)).contains("没有业务文档");
        assertThat(WrenProjectContextMiddleware.projectFiles(root.resolve("missing"))).isEmpty();
    }

    @Test
    void longOptionLabelsAreShortenedInsteadOfRefused() {
        String longLabel = "按 report_visit.account = user.account 建立多对多关系（用户账号唯一性未验证）并据此统计";
        assertThat(ModelingConfirmationService.label(longLabel)).hasSize(40).endsWith("…");
        assertThat(ModelingConfirmationService.label(" 多对一 ")).isEqualTo("多对一");
    }
}
