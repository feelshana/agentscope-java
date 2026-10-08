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

import io.agentscope.dataagent.web.artifact.ArtifactStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {
    private final ArtifactStore store;

    public ArtifactController(ArtifactStore store) {
        this.store = store;
    }

    @GetMapping("/{id}/content")
    public Mono<ResponseEntity<FileSystemResource>> content(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean download,
            Authentication auth) {
        return Mono.fromCallable(
                        () -> {
                            var a = store.require((String) auth.getPrincipal(), id);
                            var file = store.path(a.id);
                            if (!Files.isRegularFile(file))
                                throw new ResponseStatusException(HttpStatus.GONE, "附件已不可用");
                            String lower = a.filename.toLowerCase(java.util.Locale.ROOT);
                            MediaType type =
                                    lower.endsWith(".png")
                                            ? MediaType.IMAGE_PNG
                                            : (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
                                                    ? MediaType.IMAGE_JPEG
                                                    : lower.endsWith(".svg")
                                                            ? MediaType.valueOf("image/svg+xml")
                                                            : MediaType.APPLICATION_OCTET_STREAM;
                            boolean inline = !download && type.getType().equals("image");
                            return ResponseEntity.ok()
                                    .contentType(type)
                                    .contentLength(a.sizeBytes)
                                    .header(
                                            HttpHeaders.CONTENT_DISPOSITION,
                                            (inline
                                                            ? ContentDisposition.inline()
                                                            : ContentDisposition.attachment())
                                                    .filename(a.filename, StandardCharsets.UTF_8)
                                                    .build()
                                                    .toString())
                                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                                    .header("X-Content-Type-Options", "nosniff")
                                    .header(
                                            "Content-Security-Policy",
                                            "sandbox; default-src 'none'")
                                    .body(new FileSystemResource(file));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
