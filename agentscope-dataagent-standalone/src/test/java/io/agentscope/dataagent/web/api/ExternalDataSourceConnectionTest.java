package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DataSourceIntrospector;
import io.agentscope.dataagent.dataset.DemoDataService;
import io.agentscope.dataagent.dataset.ExternalDataSourcePolicy;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class ExternalDataSourceConnectionTest {
    private final ExternalDataSourceRepository sources = mock(ExternalDataSourceRepository.class);
    private final DataSourceIntrospector introspector = mock(DataSourceIntrospector.class);
    private final DemoDataService demo = mock(DemoDataService.class);
    private final ExternalDataSourceController controller =
            new ExternalDataSourceController(
                    sources,
                    mock(DatasetRepository.class),
                    introspector,
                    new ExternalDataSourcePolicy(""),
                    demo);
    private final Authentication auth = new UsernamePasswordAuthenticationToken("alice", "unused");

    @Test
    void testsUnsavedParametersWithoutPersistingAndReturnsDatabaseError() {
        when(introspector.connectionError(any())).thenReturn("Host is not allowed to connect");
        var result = controller.testConnection(request("new-password"), null, auth).block();
        assertThat(result.connected()).isFalse();
        assertThat(result.error()).contains("Host is not allowed");
        ArgumentCaptor<ExternalDataSourceEntity> candidate =
                ArgumentCaptor.forClass(ExternalDataSourceEntity.class);
        verify(introspector).connectionError(candidate.capture());
        assertThat(candidate.getValue().getJdbcUrl())
                .contains("db.internal:3306/sales", "sslMode=DISABLED", "connectTimeout=10000");
        assertThat(candidate.getValue().getPassword()).isEqualTo("new-password");
        verifyNoInteractions(sources);
    }

    @Test
    void editedSourceKeepsSavedPasswordWhenBlankAndNeverSavesCandidate() {
        when(sources.findById("source")).thenReturn(Optional.of(saved("alice")));
        var result = controller.testConnection(request(""), "source", auth).block();
        assertThat(result.connected()).isTrue();
        ArgumentCaptor<ExternalDataSourceEntity> candidate =
                ArgumentCaptor.forClass(ExternalDataSourceEntity.class);
        verify(introspector).connectionError(candidate.capture());
        assertThat(candidate.getValue().getPassword()).isEqualTo("saved-password");
        verify(sources, never()).save(any());
    }

    @Test
    void cannotReuseAnotherUsersSavedCredentials() {
        when(sources.findById("source")).thenReturn(Optional.of(saved("bob")));
        assertThatThrownBy(() -> controller.testConnection(request(""), "source", auth).block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(introspector);
    }

    private ExternalDataSourceEntity builtin() {
        when(demo.sourceAvailable()).thenReturn(true);
        var source =
                new ExternalDataSourceEntity(
                        DemoDataService.SOURCE_ID,
                        DemoDataService.OWNER,
                        DemoDataService.SOURCE_NAME,
                        "mysql",
                        "jdbc:mysql://db.internal:3306/demo_data",
                        "demo_reader",
                        "reader-secret",
                        true);
        when(sources.findById(DemoDataService.SOURCE_ID)).thenReturn(Optional.of(source));
        return source;
    }

    /** spec105: everyone sees the demo source, and never its address or account. */
    @Test
    void builtinSourceIsListedForEveryoneWithoutConnectionDetails() {
        builtin();
        var listed = controller.list(auth).block();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).builtin()).isTrue();
        assertThat(listed.get(0).jdbcUrl()).isNull();
        assertThat(listed.get(0).username()).isNull();
    }

    @Test
    void builtinSourceIsHiddenWhenItIsNotAvailable() {
        builtin();
        when(demo.sourceAvailable()).thenReturn(false);
        assertThat(controller.list(auth).block()).isEmpty();
        assertThatThrownBy(() -> controller.schemas(DemoDataService.SOURCE_ID, auth).block())
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void builtinSourceOnlyBrowsesTheDemoSchema() {
        builtin();
        assertThat(controller.schemas(DemoDataService.SOURCE_ID, auth).block())
                .containsExactly(DemoDataService.SCHEMA);
        assertThatThrownBy(
                        () -> controller.tables(DemoDataService.SOURCE_ID, "mysql", auth).block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(introspector);
    }

    @Test
    void builtinSourceCannotBeEditedOrDeleted() {
        builtin();
        assertThatThrownBy(
                        () ->
                                controller
                                        .update(DemoDataService.SOURCE_ID, request("pw"), auth)
                                        .block())
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.delete(DemoDataService.SOURCE_ID, auth).block())
                .isInstanceOf(ResponseStatusException.class);
        verify(sources, never()).save(any());
        verify(sources, never()).deleteById(any());
    }

    /** 2026-10-25: queries use MySQL 8.0 SQL, so an older or unknown engine is never saved. */
    @Test
    void unsupportedSourceIsRefusedBeforeItIsSaved() {
        when(introspector.connectionError(any()))
                .thenReturn("当前 MySQL 版本 5.7.44 低于 8.0，外部数据源仅支持 MySQL 8.0 及以上或 Doris");
        assertThatThrownBy(() -> controller.create(request("pw"), auth).block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                            assertThat(error.getReason()).contains("低于 8.0");
                        });
        when(sources.findById("source")).thenReturn(Optional.of(saved("alice")));
        assertThatThrownBy(() -> controller.update("source", request("pw"), auth).block())
                .isInstanceOf(ResponseStatusException.class);
        verify(sources, never()).save(any());
    }

    private ExternalDataSourceController.DataSourceRequest request(String password) {
        return new ExternalDataSourceController.DataSourceRequest(
                "销售库",
                "mysql",
                null,
                "reader",
                password,
                true,
                "db.internal",
                3306,
                "sales",
                "DISABLED");
    }

    private ExternalDataSourceEntity saved(String owner) {
        return new ExternalDataSourceEntity(
                "source",
                owner,
                "销售库",
                "mysql",
                "jdbc:mysql://old.internal/sales",
                "reader",
                "saved-password",
                true);
    }
}
