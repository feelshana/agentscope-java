package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** spec109: proposals are recorded, never written as assets, and decided once by the user. */
class AssetProposalStoreTest {
    @TempDir Path home;
    private AssetProposalStore store;
    private Path root;

    @BeforeEach
    void setup() throws Exception {
        var workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""));
        root = workspace.workspaceRoot("g");
        Files.createDirectories(root.resolve("models/报表访问明细"));
        Files.writeString(root.resolve("models/报表访问明细/metadata.yml"), "name: 报表访问明细\n");
        store = new AssetProposalStore(workspace);
    }

    private AssetProposalStore.Proposal propose(String name) {
        return store.propose("g", "view", name, "按公司分析访问", "拼用户列表，保留账号列", "需要固定拼接");
    }

    @Test
    void proposalIsRecordedWithoutWritingAnyAssetFile() {
        var created = propose("报表访问用户明细");
        assertThat(created.status()).isEqualTo(AssetProposalStore.PENDING);
        assertThat(store.list("g")).singleElement().matches(p -> p.name().equals("报表访问用户明细"));
        assertThat(Files.exists(root.resolve("views"))).isFalse();
        assertThat(Files.exists(root.resolve(".platform/asset-proposals.json"))).isTrue();
    }

    @Test
    void namesMustBeNewAndKindsLimitedToQueryTemplates() {
        propose("报表访问用户明细");
        assertThatThrownBy(() -> propose("报表访问用户明细")).hasMessageContaining("不要重复提议");
        assertThatThrownBy(() -> propose("报表访问明细")).hasMessageContaining("同名的模型或资产");
        assertThatThrownBy(() -> store.propose("g", "cube", "访问指标", "a", "b", "c"))
                .hasMessageContaining("尚未开放");
        assertThatThrownBy(() -> store.propose("g", "view", "x", "", "b", "c"))
                .hasMessageContaining("need");
    }

    @Test
    void aProposalIsDecidedOnce() {
        var created = propose("报表访问用户明细");
        var dismissed = store.decide("g", created.id(), AssetProposalStore.DISMISSED, "alice");
        assertThat(dismissed.status()).isEqualTo(AssetProposalStore.DISMISSED);
        assertThat(dismissed.decidedBy()).isEqualTo("alice");
        assertThatThrownBy(
                        () -> store.decide("g", created.id(), AssetProposalStore.DRAFTED, "alice"))
                .hasMessageContaining("已经处理过");
        assertThatThrownBy(() -> store.decide("g", created.id(), "PENDING", "alice"))
                .isInstanceOf(DatasetException.class);
        // A dismissed name can be proposed again.
        assertThat(propose("报表访问用户明细").status()).isEqualTo(AssetProposalStore.PENDING);
    }
}
