package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class KnowledgeBaseBrowseTest {
    @TempDir Path root;

    @Test
    void readsPublishedOnlyAndProjectsOutCredentials() throws Exception {
        var access = mock(KnowledgeBaseAccessService.class);
        var datasets = mock(DatasetRepository.class);
        var service = mock(DatasetService.class);
        var properties = mock(WrenProperties.class);
        var group = new DatasetGroupEntity("g", "owner", "库", null);
        group.setMdlVersion(1);
        when(access.requireQuery("reader", "g")).thenReturn(group);
        when(properties.groupRoot("g")).thenReturn(root);
        when(datasets.findByGroupId("g")).thenReturn(List.of());
        Files.createDirectories(root.resolve("published/target"));
        Files.createDirectories(root.resolve("published/knowledge/rules"));
        Files.createDirectories(root.resolve("workspace/knowledge/rules"));
        Files.writeString(
                root.resolve("published/target/mdl.json"),
                "{\"models\":[{\"name\":\"销售\"}],\"relationships\":[],\"password\":\"credential\"}");
        Files.writeString(root.resolve("published/knowledge/rules/confirmed.md"), "净收入扣除退款");
        Files.writeString(root.resolve("workspace/knowledge/rules/draft.md"), "未发布草稿");
        var result =
                new KnowledgeBaseBrowseService(
                                access, datasets, service, properties, new ObjectMapper())
                        .browse("reader", "g");
        String serialized = new ObjectMapper().writeValueAsString(result);
        assertThat(serialized)
                .contains("净收入扣除退款", "销售")
                .doesNotContain("credential", "password", "未发布草稿");
    }

    @Test
    void readablePreviewIsBoundedAndRechecksAccessAfterReading() throws Exception {
        var access = mock(KnowledgeBaseAccessService.class);
        var repository = mock(DatasetRepository.class);
        var service = mock(DatasetService.class, CALLS_REAL_METHODS);
        var props = mock(DatasetStoreProperties.class);
        String url = "jdbc:h2:mem:preview_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        when(props.url()).thenReturn(url);
        when(props.username()).thenReturn("sa");
        when(props.password()).thenReturn("");
        ReflectionTestUtils.setField(service, "access", access);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "props", props);
        var entity = mock(DatasetEntity.class);
        when(entity.getGroupId()).thenReturn("g");
        when(entity.getTableName()).thenReturn("ds_rows");
        when(repository.findById("d")).thenReturn(Optional.of(entity));
        doReturn(List.of(new ColumnSchema("n", "编号", "INTEGER", false, "编号")))
                .when(service)
                .readColumns(entity);
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var st = connection.createStatement()) {
            st.execute("CREATE TABLE ds_rows(n int)");
            for (int i = 0; i < 30; i++) st.execute("INSERT INTO ds_rows VALUES (" + i + ")");
        }
        assertThat(service.previewReadable("reader", "g", "d").rows()).hasSize(20);
        assertThatThrownBy(() -> service.previewReadable("reader", "other", "d"))
                .isInstanceOf(DatasetException.class);
        var group = new DatasetGroupEntity("g", "owner", "库", null);
        when(access.requireQuery("reader", "g"))
                .thenReturn(group)
                .thenThrow(new DatasetException("已撤权", 404));
        assertThatThrownBy(() -> service.previewReadable("reader", "g", "d"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("撤权");
    }
}
