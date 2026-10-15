/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserControlFilesystemTest {
    @TempDir Path root;

    @Test
    void absoluteRouteSuffixAndRootListingStayInsideUserNamespace() {
        var fs = new UserControlFilesystem(root);
        var alice = RuntimeContext.builder().userId("alice").build();
        var bob = RuntimeContext.builder().userId("bob").build();
        assertThat(fs.write(alice, "/agent/sessions/a.jsonl", "alice only").isSuccess()).isTrue();
        assertThat(fs.read(bob, "/agent/sessions/a.jsonl", 0, 0).isSuccess()).isFalse();
        assertThat(fs.ls(bob, "/").entries()).isNullOrEmpty();
        assertThat(fs.read(alice, "/agent/sessions/a.jsonl", 0, 0).fileData().content())
                .isEqualTo("alice only");
        assertThatThrownBy(() -> fs.resolvePath(RuntimeContext.empty(), "/"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> fs.resolvePath(alice, "C:/agent/sessions/a.jsonl"))
                .isInstanceOf(SecurityException.class);
    }
}
