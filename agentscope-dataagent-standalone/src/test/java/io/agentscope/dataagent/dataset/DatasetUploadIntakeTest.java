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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;

class DatasetUploadIntakeTest {
    @Test
    void cancelledStagingCannotCreateAnUntrackedDescription() throws Exception {
        var staged = new DatasetUploadIntake.Staged("x.csv");
        var description = staged.createDescription();
        staged.close();
        assertThat(Files.exists(description)).isFalse();
        assertThat(Files.exists(staged.file())).isFalse();
        assertThatThrownBy(staged::createDescription).isInstanceOf(DatasetException.class);
    }

    private FilePart file(String name, String text) {
        var part = mock(FilePart.class);
        when(part.filename()).thenReturn(name);
        when(part.content())
                .thenAnswer(
                        i ->
                                Flux.defer(
                                        () ->
                                                Flux.just(
                                                        DefaultDataBufferFactory.sharedInstance
                                                                .wrap(
                                                                        text.getBytes(
                                                                                StandardCharsets
                                                                                        .UTF_8)))));
        return part;
    }

    @Test
    void actualBytesAreLimitedAndSlotIsReleased() {
        var p = new DatasetUploadProperties();
        p.setMaxFileSize(DataSize.ofBytes(4));
        var gate = new DatasetUploadAdmission(p);
        var intake = new DatasetUploadIntake(p, gate);
        assertThatThrownBy(
                        () ->
                                intake.run(
                                                file("x.csv", "12345"),
                                                null,
                                                "u",
                                                "g",
                                                "x",
                                                null,
                                                input -> {
                                                    throw new AssertionError();
                                                })
                                        .block())
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("大小");
        assertThat(gate.activeCount()).isZero();
        assertThat(gate.statuses("u", "g").get(0).phase()).isEqualTo("FAILED");
    }

    @Test
    void stagingUsesDiskAndDeletesAfterSuccess() {
        var p = new DatasetUploadProperties();
        var gate = new DatasetUploadAdmission(p);
        var intake = new DatasetUploadIntake(p, gate);
        var staged = new AtomicReference<java.nio.file.Path>();
        String value =
                intake.run(
                                file("x.csv", "abc"),
                                null,
                                "u",
                                "g",
                                "x",
                                null,
                                input -> {
                                    staged.set(input.staged().file());
                                    assertThat(Files.exists(staged.get())).isTrue();
                                    input.lease().completed();
                                    return "ok";
                                })
                        .block();
        assertThat(value).isEqualTo("ok");
        assertThat(Files.exists(staged.get())).isFalse();
        assertThat(gate.activeCount()).isZero();
    }

    @Test
    void descriptionAndFileShareRequestBudget() {
        var p = new DatasetUploadProperties();
        p.setMaxSelectionSize(DataSize.ofBytes(6));
        var gate = new DatasetUploadAdmission(p);
        var intake = new DatasetUploadIntake(p, gate);
        assertThatThrownBy(
                        () ->
                                intake.run(
                                                file("x.csv", "1234"),
                                                file("d.docx", "1234"),
                                                "u",
                                                "g",
                                                "x",
                                                null,
                                                input -> "unused")
                                        .block())
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("总大小");
        assertThat(gate.activeCount()).isZero();
    }

    @Test
    void batchTotalFailsBeforeImportAndReleasesReadSlot() {
        var p = new DatasetUploadProperties();
        p.setMaxSelectionSize(DataSize.ofBytes(6));
        var gate = new DatasetUploadAdmission(p);
        var intake = new DatasetUploadIntake(p, gate);
        assertThatThrownBy(
                        () ->
                                intake.stageBatch(
                                                List.of(
                                                        file("a.csv", "1234"),
                                                        file("b.csv", "1234")),
                                                "u")
                                        .block())
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("总大小");
        assertThat(gate.activeCount()).isZero();
    }

    @Test
    void cancelledWorkerKeepsFileAndSlotUntilItActuallyEnds() throws Exception {
        var p = new DatasetUploadProperties();
        var gate = new DatasetUploadAdmission(p);
        var intake = new DatasetUploadIntake(p, gate);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var ended = new CountDownLatch(1);
        var staged = new AtomicReference<java.nio.file.Path>();
        var subscription =
                intake.run(
                                file("x.csv", "123"),
                                null,
                                "u",
                                "g",
                                "x",
                                null,
                                input -> {
                                    staged.set(input.staged().file());
                                    started.countDown();
                                    boolean waiting = true;
                                    while (waiting)
                                        try {
                                            finish.await();
                                            waiting = false;
                                        } catch (InterruptedException ignored) {
                                        }
                                    assertThat(Files.exists(staged.get())).isTrue();
                                    ended.countDown();
                                    return "done";
                                })
                        .subscribe();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();
        assertThat(gate.activeCount()).isEqualTo(1);
        assertThat(Files.exists(staged.get())).isTrue();
        finish.countDown();
        assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 100 && gate.activeCount() != 0; i++) Thread.sleep(10);
        assertThat(gate.activeCount()).isZero();
        assertThat(Files.exists(staged.get())).isFalse();
    }
}
