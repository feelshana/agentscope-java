package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent read-side regression tests with real files and no CLI, seeder or publisher. */
class MdlReadServiceTest {
    @TempDir Path home;
    private final DatasetGroupRepository groups = mock(DatasetGroupRepository.class);
    private final MdlWorkspaceService workspace = mock(MdlWorkspaceService.class);
    private final DatasetGroupEntity group = new DatasetGroupEntity("g", "alice", "知识库", "");
    private Path root;
    private Path draft;
    private MdlReadService reads;

    @BeforeEach
    void setUp() {
        var props = new WrenProperties("not-used", home.toString(), "test", "mysql", 10);
        root = props.groupRoot("g");
        draft = root.resolve("workspace");
        when(groups.findById("g")).thenReturn(Optional.of(group));
        when(workspace.workspaceRoot("g")).thenReturn(draft);
        var reader = new MdlWorkspaceReader(props, workspace, mock(DatasetRepository.class));
        reads = new MdlReadService(groups, props, workspace, reader);
    }

    @Test
    void absentWorkspaceIsNotCreatedByPreviewOrView() {
        var preview = reads.preview("g");
        var view = reads.view("g");
        assertThat(preview.files()).isEmpty();
        assertThat(preview.changed()).isFalse();
        assertThat(view.models()).isEmpty();
        assertThat(view.changed()).isFalse();
        assertThat(root).doesNotExist();
        verify(groups, never()).save(any());
        verify(workspace, never()).ensureWorkspace(any());
    }

    @Test
    void previewFiltersRuntimeArtifactsAndReadingDoesNotChangeFilesOrVersion() throws Exception {
        put(draft.resolve("wren_project.yml"), "name: test\n");
        put(root.resolve("published/wren_project.yml"), "name: test\n");
        put(root.resolve("published/target/mdl.json"), "{}\n");
        put(root.resolve("published/wren-source.properties"), "profile=test\n");
        put(root.resolve("published/" + PublishedAssetWindows.FILE), "{}\n");
        var before = contents();
        int version = group.getMdlVersion();
        var preview = reads.preview("g");
        assertThat(preview.changed()).isFalse();
        assertThat(preview.publishedFiles())
                .extracting(MdlTypes.MdlFile::path)
                .containsExactly("wren_project.yml");
        reads.view("g");
        reads.compiled("g");
        assertThat(contents()).isEqualTo(before);
        assertThat(group.getMdlVersion()).isEqualTo(version);
        assertThat(root.resolve("staging")).doesNotExist();
        verify(groups, never()).save(any());
        put(draft.resolve("wren_project.yml"), "name: changed\n");
        assertThat(reads.preview("g").changed()).isTrue();
    }

    @Test
    void absentCompiledFilesRemainNullWithoutCreatingStorageOrReadingGroupMetadata() {
        var result = reads.compiled("g");
        assertThat(result).isEqualTo(new MdlTypes.CompiledMdl(null, null, null, null));
        assertThat(root).doesNotExist();
        org.mockito.Mockito.verifyNoInteractions(groups);
    }

    @Test
    void compiledReturnsTheExactTwoVersionsAndTheirBuildTimes() throws Exception {
        Path published = root.resolve("published/target/mdl.json");
        Path current = draft.resolve("target/mdl.json");
        put(published, "{\"models\":[\"published\"]}\n");
        put(current, "{\"models\":[\"draft\"]}\n");
        Files.setLastModifiedTime(published, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(current, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));
        var result = reads.compiled("g");
        assertThat(result.published()).isEqualTo(Files.readString(published));
        assertThat(result.draft()).isEqualTo(Files.readString(current));
        assertThat(result.publishedBuiltAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(result.draftBuiltAt()).isEqualTo("2026-01-02T00:00:00Z");
    }

    @Test
    void compiledOversizeStillReturnsTheExisting413Error() throws IOException {
        Path file = root.resolve("published/target/mdl.json");
        put(file, "x".repeat(4 * 1024 * 1024 + 1));
        assertThatThrownBy(() -> reads.compiled("g"))
                .isInstanceOfSatisfying(
                        DatasetException.class, e -> assertThat(e.status()).isEqualTo(413))
                .hasMessage("编译后的 MDL 过大，无法在页面显示");
    }

    @Test
    void previewAndViewKeepTheMissingGroup404() {
        when(groups.findById("g")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reads.preview("g"))
                .isInstanceOfSatisfying(
                        DatasetException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> reads.view("g"))
                .isInstanceOfSatisfying(
                        DatasetException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThat(root).doesNotExist();
    }

    private static void put(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private Map<String, String> contents() throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (var p : paths.filter(Files::isRegularFile).toList())
                files.put(root.relativize(p).toString(), Files.readString(p));
        }
        return files;
    }
}
