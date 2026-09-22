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
package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ChatController} shapes inbound messages as a plain USER wrapper. The framework
 * rejects SYSTEM messages injected into {@code PreCallEvent.inputMessages}, so this test guards
 * against any regression that would smuggle instructions (e.g. language policy) back into the
 * chat turn — those belong in the agent's own prompt material (AGENTS.md, skills, sub-agents).
 */
class ChatControllerInboundShapeTest {

    @Test
    void wrapsUserMessageOnly() {
        List<Msg> msgs = ChatController.shapeInboundMessages("hello");
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).getRole()).isEqualTo(MsgRole.USER);
        assertThat(msgs.get(0).getTextContent()).isEqualTo("hello");
    }

    @Test
    void neverInjectsSystemMessages() {
        for (Msg m : ChatController.shapeInboundMessages("any message")) {
            assertThat(m.getRole()).isNotEqualTo(MsgRole.SYSTEM);
        }
    }
}
