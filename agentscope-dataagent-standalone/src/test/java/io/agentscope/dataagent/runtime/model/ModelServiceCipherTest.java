package io.agentscope.dataagent.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelServiceCipherTest {
    @TempDir Path home;

    @Test
    void fileKeyIsDurableAuthenticatedAndNotRecreatedOnLoss() throws Exception {
        var cipher = new ModelServiceCipher("", home.toString());
        String first = cipher.encrypt("synthetic-private-value");
        assertThat(first)
                .doesNotContain("synthetic-private-value")
                .isNotEqualTo(cipher.encrypt("synthetic-private-value"));
        assertThat(new ModelServiceCipher("", home.toString()).decrypt(first))
                .isEqualTo("synthetic-private-value");
        assertThatThrownBy(() -> cipher.decrypt(first.substring(0, first.length() - 3) + "AAA"))
                .isInstanceOf(IllegalStateException.class);
        Path file = home.resolve(".agentscope-dataagent/model-services.key");
        Files.delete(file);
        assertThatThrownBy(() -> cipher.decrypt(first)).isInstanceOf(IllegalStateException.class);
        assertThat(file).doesNotExist();
    }

    @Test
    void suppliedKeyRequires32BytesAndWrongKeyCannotDecrypt() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var cipher = new ModelServiceCipher(key, home.toString());
        assertThat(cipher.decrypt(cipher.encrypt("test"))).isEqualTo("test");
        assertThat(home.resolve(".agentscope-dataagent")).doesNotExist();
        assertThatThrownBy(() -> new ModelServiceCipher("invalid", home.toString()).encrypt("test"))
                .isInstanceOf(IllegalStateException.class);
    }
}
