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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Bounded upload staging on disk, with cleanup tied to the actual worker lifetime. */
@Component
public class DatasetUploadIntake {
    private static final Logger log = LoggerFactory.getLogger(DatasetUploadIntake.class);
    private final DatasetUploadProperties properties;
    private final DatasetUploadAdmission admission;

    public DatasetUploadIntake(
            DatasetUploadProperties properties, DatasetUploadAdmission admission) {
        this.properties = properties;
        this.admission = admission;
    }

    public static final class Staged implements AutoCloseable {
        private final Path file;
        private Path description;
        private final String filename;
        private boolean inUse, closed;

        Staged(String filename) throws IOException {
            this.filename = filename;
            file = Files.createTempFile("dataagent-upload-", ".tmp");
        }

        public Path file() {
            return file;
        }

        public Path description() {
            return description;
        }

        synchronized Path createDescription() throws IOException {
            if (closed) throw new DatasetException("上传已取消", 409);
            description = Files.createTempFile("dataagent-description-", ".tmp");
            return description;
        }

        public String filename() {
            return filename;
        }

        synchronized boolean claim() {
            if (closed) return false;
            inUse = true;
            return true;
        }

        public synchronized void closeIfIdle() {
            if (!inUse) close();
        }

        public synchronized void close() {
            if (closed) return;
            closed = true;
            inUse = false;
            delete(file);
            if (description != null) delete(description);
        }

        private static void delete(Path path) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Upload staging cleanup failed: {}", path, e);
            }
        }
    }

    public record Input(Staged staged, DatasetUploadAdmission.Lease lease) {}

    public <T> Mono<T> run(
            FilePart file,
            FilePart description,
            String user,
            String group,
            String name,
            String id,
            Function<Input, T> worker) {
        return Mono.defer(
                        () -> {
                            var lease = admission.acquire(user, group, name, id);
                            lease.filename(file.filename());
                            var requestBytes = new AtomicLong();
                            return Mono.usingWhen(
                                            newStaged(file.filename()),
                                            staged ->
                                                    write(file, staged.file(), requestBytes)
                                                            .then(
                                                                    description == null
                                                                            ? Mono.empty()
                                                                            : Mono.defer(
                                                                                    () -> {
                                                                                        try {
                                                                                            staged
                                                                                                    .createDescription();
                                                                                        } catch (
                                                                                                IOException
                                                                                                        e) {
                                                                                            return Mono
                                                                                                    .error(
                                                                                                            e);
                                                                                        }
                                                                                        return write(
                                                                                                description,
                                                                                                staged.description,
                                                                                                requestBytes);
                                                                                    }))
                                                            .then(work(staged, lease, worker)),
                                            staged -> close(staged, false),
                                            (staged, error) -> close(staged, false),
                                            staged -> {
                                                lease.cancelIntake();
                                                return close(staged, true);
                                            })
                                    .doOnError(lease::failed)
                                    .doFinally(signal -> lease.cancelIntake());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public <T> Mono<T> runStaged(
            Staged staged, String user, String group, String name, Function<Input, T> worker) {
        return Mono.defer(
                        () -> {
                            var lease = admission.acquire(user, group, name, null);
                            lease.filename(staged.filename());
                            return work(staged, lease, worker)
                                    .doOnError(lease::failed)
                                    .doFinally(signal -> lease.cancelIntake());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private <T> Mono<T> work(
            Staged staged, DatasetUploadAdmission.Lease lease, Function<Input, T> worker) {
        return Mono.fromCallable(
                        () -> {
                            if (!lease.start()) throw new DatasetException("上传已取消", 409);
                            if (!staged.claim()) {
                                lease.close();
                                throw new DatasetException("上传已取消", 409);
                            }
                            try {
                                return worker.apply(new Input(staged, lease));
                            } catch (RuntimeException error) {
                                lease.failed(error);
                                throw error;
                            } finally {
                                staged.close();
                                lease.close();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<Staged>> stageBatch(List<FilePart> files, String user) {
        return Mono.defer(
                        () -> {
                            var permit =
                                    admission.acquire(
                                            user, "@staging", UUID.randomUUID().toString(), null);
                            var staged = new java.util.concurrent.CopyOnWriteArrayList<Staged>();
                            var total = new AtomicLong();
                            return Flux.fromIterable(files)
                                    .concatMap(
                                            file ->
                                                    newStaged(file.filename())
                                                            .flatMap(
                                                                    item -> {
                                                                        staged.add(item);
                                                                        return write(
                                                                                        file,
                                                                                        item.file(),
                                                                                        total)
                                                                                .thenReturn(item);
                                                                    }))
                                    .collectList()
                                    .doOnError(error -> staged.forEach(Staged::closeIfIdle))
                                    .doOnCancel(() -> staged.forEach(Staged::closeIfIdle))
                                    .doFinally(signal -> permit.close());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Staged> newStaged(String name) {
        return Mono.fromCallable(() -> new Staged(name))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnDiscard(Staged.class, Staged::closeIfIdle);
    }

    private Mono<Void> write(FilePart part, Path path, AtomicLong requestBytes) {
        var fileBytes = new AtomicLong();
        Flux<DataBuffer> content =
                part.content()
                        .<DataBuffer>handle(
                                (buffer, sink) -> {
                                    int count = buffer.readableByteCount();
                                    if (fileBytes.addAndGet(count)
                                            > properties.getMaxFileSize().toBytes()) {
                                        DataBufferUtils.release(buffer);
                                        sink.error(
                                                new DatasetException(
                                                        "文件超过大小限制："
                                                                + properties
                                                                        .getMaxFileSize()
                                                                        .toMegabytes()
                                                                + " MB",
                                                        413));
                                    } else if (requestBytes.addAndGet(count)
                                            > properties.getMaxSelectionSize().toBytes()) {
                                        DataBufferUtils.release(buffer);
                                        sink.error(new DatasetException("本次上传总大小超过限制", 413));
                                    } else sink.next(buffer);
                                });
        return DataBufferUtils.write(
                        content.doOnDiscard(DataBuffer.class, DataBufferUtils::release), path)
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
    }

    private Mono<Void> close(Staged file, boolean idleOnly) {
        return Mono.fromRunnable(
                        () -> {
                            if (idleOnly) file.closeIfIdle();
                            else file.close();
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
