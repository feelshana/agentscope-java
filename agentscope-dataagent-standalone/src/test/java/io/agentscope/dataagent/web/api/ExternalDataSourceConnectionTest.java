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
    private final ExternalDataSourceController controller =
            new ExternalDataSourceController(
                    sources,
                    mock(DatasetRepository.class),
                    introspector,
                    new ExternalDataSourcePolicy(""));
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
