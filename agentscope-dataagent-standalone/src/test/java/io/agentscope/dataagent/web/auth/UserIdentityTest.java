package io.agentscope.dataagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserIdentityTest {
    @Test
    void identityPortReadsCurrentUserAndAdministratorRoleWithoutCaching() {
        var store = mock(UserStore.class, CALLS_REAL_METHODS);
        when(store.findById("user"))
                .thenReturn(
                        Optional.of(
                                new UserStore.UserRecord(
                                        "user", "user", "unused", List.of("user", "admin"))));
        assertThat(store.exists(null)).isFalse();
        assertThat(store.isAdministrator(null)).isFalse();
        assertThat(store.exists("missing")).isFalse();
        assertThat(store.isAdministrator("missing")).isFalse();
        assertThat(store.exists("user")).isTrue();
        assertThat(store.isAdministrator("user")).isTrue();
        when(store.findById("user"))
                .thenReturn(
                        Optional.of(
                                new UserStore.UserRecord(
                                        "user", "user", "unused", List.of("user"))));
        assertThat(store.isAdministrator("user")).isFalse();
        when(store.findById("user")).thenReturn(Optional.empty());
        assertThat(store.exists("user")).isFalse();
    }
}
