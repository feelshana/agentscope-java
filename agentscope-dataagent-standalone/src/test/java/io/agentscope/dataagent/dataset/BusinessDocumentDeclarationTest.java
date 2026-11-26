package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BusinessDocumentDeclarationTest {
    @TempDir Path root;

    @Test
    void declaredModelsComeFromTheOpeningLinesOnly() {
        assertThat(BusinessDocumentService.declaredModels("# 口径\n\n适用模型：`vip_m`。本表按月保存\n"))
                .containsExactly("vip_m");
        assertThat(BusinessDocumentService.declaredModels("> 适用表: `a_m`、`b_m`\n"))
                .containsExactly("a_m", "b_m");
        assertThat(BusinessDocumentService.declaredModels("适用模型：a_m, b_m。其余说明\n"))
                .containsExactly("a_m", "b_m");
        assertThat(BusinessDocumentService.declaredModels("# 通用术语\n没有声明\n")).isEmpty();
        assertThat(BusinessDocumentService.declaredModels("\n".repeat(50) + "适用模型：late\n"))
                .isEmpty();
    }

    @Test
    void documentsForAModelCarryAHashThatChangesWithTheContent() throws Exception {
        Files.createDirectories(root.resolve("raw/docs"));
        Files.writeString(root.resolve("raw/docs/会员.md"), "适用模型：`vip_m`\n");
        Files.writeString(root.resolve("raw/docs/收入.md"), "适用模型：`income_m`、`vip_m`\n");
        Files.writeString(root.resolve("raw/docs/通用.md"), "# 术语\n");

        var before = BusinessDocumentService.documentsFor(root, "VIP_M");
        assertThat(before).containsOnlyKeys("会员.md", "收入.md");
        Files.writeString(root.resolve("raw/docs/会员.md"), "适用模型：`vip_m`\n新增口径\n");
        assertThat(BusinessDocumentService.documentsFor(root, "vip_m").get("会员.md"))
                .isNotEqualTo(before.get("会员.md"));
        assertThat(BusinessDocumentService.documentsFor(root, "income_m"))
                .containsOnlyKeys("收入.md");
    }

    /**
     * 2026-11-25: an uploaded table's model carries the platform prefix; a document naming the
     * original table still declares it. Names meaning no model are reported for the publish hint.
     */
    @Test
    void theOriginalTableNameDeclaresItsUploadedModel() throws Exception {
        String model = "ds_8c6976e5_190cd16b_hhweb_video_vip_more_dimension_m";
        assertThat(BusinessDocumentService.declares("hhweb_video_vip_more_dimension_m", model))
                .isTrue();
        assertThat(BusinessDocumentService.declares(model, model)).isTrue();
        // Only the platform prefix may be left out, not an arbitrary part of the name.
        assertThat(BusinessDocumentService.declares("more_dimension_m", model)).isFalse();
        assertThat(
                        BusinessDocumentService.declares(
                                "vip_more_dimension_m", "x_vip_more_dimension_m"))
                .isFalse();

        Files.createDirectories(root.resolve("raw/docs"));
        Files.writeString(
                root.resolve("raw/docs/会员规模.md"), "适用模型：`hhweb_video_vip_more_dimension_m`。\n");
        Files.writeString(root.resolve("raw/docs/写错.md"), "适用模型：`hhweb_video_vip_m`\n");
        assertThat(BusinessDocumentService.documentsFor(root, model)).containsOnlyKeys("会员规模.md");
        assertThat(BusinessDocumentService.unmatchedDeclarations(root, java.util.List.of(model)))
                .containsOnlyKeys("写错.md")
                .containsEntry("写错.md", java.util.List.of("hhweb_video_vip_m"));
    }
}
