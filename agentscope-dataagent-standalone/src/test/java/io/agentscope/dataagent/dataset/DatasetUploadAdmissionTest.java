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
package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatasetUploadAdmissionTest {
    @Test
    void globalCapacityAndSameNameAreSharedAcrossUsers() {
        var p = new DatasetUploadProperties();
        p.setConcurrentFiles(2);
        var gate = new DatasetUploadAdmission(p);
        var first = gate.acquire("alice", "g", "Sales", "one");
        assertThatThrownBy(() -> gate.acquire("bob", "g", "sales", "two"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("重复");
        var second = gate.acquire("bob", "other", "sales", "two");
        assertThatThrownBy(() -> gate.acquire("c", "c", "c", "three"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("名额");
        first.close();
        first.close();
        second.close();
        assertThat(gate.activeCount()).isZero();
    }

    @Test
    void cancellationDoesNotReleaseRunningWorker() {
        var gate = new DatasetUploadAdmission(new DatasetUploadProperties());
        var lease = gate.acquire("a", "g", "n", "id");
        assertThat(lease.start()).isTrue();
        lease.cancelIntake();
        assertThat(gate.activeCount()).isEqualTo(1);
        lease.saved("dataset");
        lease.close();
        assertThat(gate.activeCount()).isZero();
        assertThat(lease.snapshot().phase()).isEqualTo("IMPORTED_MODEL_PENDING");
        gate.published("a", "g", List.of("dataset"), null);
        assertThat(lease.snapshot().phase()).isEqualTo("COMPLETED");
    }

    @Test
    void cancellationBeforeWorkerStartsCannotBeResumed() {
        var gate = new DatasetUploadAdmission(new DatasetUploadProperties());
        var lease = gate.acquire("a", "g", "n", "id");
        lease.cancelIntake();
        assertThat(lease.start()).isFalse();
        assertThat(gate.activeCount()).isZero();
    }

    @Test
    void statusIsScopedAndIdentifiersCannotBeReused() {
        var gate = new DatasetUploadAdmission(new DatasetUploadProperties());
        var lease = gate.acquire("a", "g", "n", "id");
        lease.close();
        assertThat(gate.statuses("b", "g")).isEmpty();
        assertThat(gate.statuses("a", "h")).isEmpty();
        assertThat(gate.statuses("a", "g")).hasSize(1);
        assertThatThrownBy(() -> gate.acquire("a", "g", "n", "id"))
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void invalidConfigurationIsRejected() {
        var p = new DatasetUploadProperties();
        assertThatThrownBy(() -> p.setConcurrentFiles(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.setMaxRows(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
