package io.agentscope.dataagent.web.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.model.ManagedModelFactory;
import io.agentscope.dataagent.runtime.model.ModelServiceCipher;
import io.agentscope.dataagent.runtime.model.ModelServiceRegistry;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@TestPropertySource(properties = {"spring.sql.init.mode=never", "spring.jpa.show-sql=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ModelServicePersistenceTest {
    @Autowired ModelServiceConfigurationRepository repository;
    @TempDir Path home;

    @Test
    void encryptedConfigurationSurvivesRegistryRecreationAndDatabaseRoundTrip() {
        var cipher =
                new ModelServiceCipher(
                        Base64.getEncoder().encodeToString(new byte[32]), home.toString());
        var factory = new ManagedModelFactory();
        var first = new ModelServiceRegistry(repository, cipher, factory, new ObjectMapper());
        var profile =
                first.save(
                                null,
                                new ModelServiceRegistry.Request(
                                        null,
                                        "persistent fixture",
                                        ModelServiceRegistry.Provider.OPENAI,
                                        "http://127.0.0.1:12345",
                                        null,
                                        "fixture",
                                        "synthetic-test-key",
                                        true,
                                        10,
                                        0.0,
                                        false,
                                        false))
                        .services()
                        .get(0);
        var row = repository.findById("global").orElseThrow();
        assertThat(row.getEncryptedPayload())
                .doesNotContain("synthetic-test-key", "persistent fixture");
        var restarted = new ModelServiceRegistry(repository, cipher, factory, new ObjectMapper());
        assertThat(restarted.view().services())
                .singleElement()
                .satisfies(p -> assertThat(p.id()).isEqualTo(profile.id()));
        restarted.delete(profile.id(), 1);
        assertThat(
                        new ModelServiceRegistry(repository, cipher, factory, new ObjectMapper())
                                .view()
                                .services())
                .isEmpty();
    }
}
