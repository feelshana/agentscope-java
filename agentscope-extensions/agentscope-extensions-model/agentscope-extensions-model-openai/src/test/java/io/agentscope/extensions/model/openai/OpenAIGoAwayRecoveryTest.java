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
package io.agentscope.extensions.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OpenAIGoAwayRecoveryTest {
    private static final String CHUNK =
            "{\"choices\":[{\"delta\":{\"content\":\"hello\"},\"index\":0}]}";
    private static final Duration DEADLINE = Duration.ofSeconds(3);

    @Test
    void retriesOneFreshRequestBeforeAnyEventAndKeepsBudgetPerSubscriber() {
        AtomicInteger calls = new AtomicInteger();
        var stream =
                client(
                        calls,
                        attempt ->
                                attempt % 2 == 1
                                        ? Flux.error(goAway())
                                        : Flux.just(CHUNK, "[DONE]"))
                        .stream(
                                "test",
                                "http://localhost",
                                request(),
                                GenerateOptions.builder().build());
        StepVerifier.create(stream).expectNextCount(1).verifyComplete();
        StepVerifier.create(stream).expectNextCount(1).verifyComplete();
        assertEquals(4, calls.get());
    }

    @Test
    void retriesClosedAndResetBeforeFirstEventWithoutRetryingHttpErrors() {
        for (String message : new String[] {"closed", "Connection reset"}) {
            AtomicInteger calls = new AtomicInteger();
            StepVerifier.create(
                            client(
                                    calls,
                                    attempt ->
                                            attempt == 1
                                                    ? Flux.error(
                                                            new HttpTransportException(
                                                                    "stream failed",
                                                                    new java.io.IOException(
                                                                            message)))
                                                    : Flux.just(CHUNK, "[DONE]"))
                                    .stream(
                                            "test",
                                            "http://localhost",
                                            request(),
                                            GenerateOptions.builder().build()))
                    .expectNextCount(1)
                    .verifyComplete();
            assertEquals(2, calls.get());
        }
        AtomicInteger calls = new AtomicInteger();
        StepVerifier.create(
                        client(
                                calls,
                                attempt ->
                                        Flux.error(
                                                new HttpTransportException(
                                                        "HTTP error",
                                                        401,
                                                        "unauthorized",
                                                        new java.io.IOException("closed"))))
                                .stream(
                                        "test",
                                        "http://localhost",
                                        request(),
                                        GenerateOptions.builder().build()))
                .expectError()
                .verify(DEADLINE);
        assertEquals(1, calls.get());
    }

    @Test
    void stopsAfterOneRecoveryAttempt() {
        AtomicInteger calls = new AtomicInteger();
        StepVerifier.create(
                        client(calls, attempt -> Flux.error(goAway())).stream(
                                "test",
                                "http://localhost",
                                request(),
                                GenerateOptions.builder().build()))
                .expectError()
                .verify(DEADLINE);
        assertEquals(2, calls.get());
    }

    @Test
    void neverReplaysAfterContentOrToolEvents() {
        for (String event :
                new String[] {
                    CHUNK,
                    "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"query\",\"arguments\":\"{}\"}}]},\"index\":0}]}"
                }) {
            AtomicInteger calls = new AtomicInteger();
            StepVerifier.create(
                            client(
                                    calls,
                                    attempt -> Flux.just(event).concatWith(Flux.error(goAway())))
                                    .stream(
                                            "test",
                                            "http://localhost",
                                            request(),
                                            GenerateOptions.builder().build()))
                    .expectNextCount(1)
                    .expectError()
                    .verify(DEADLINE);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void honorsExplicitDisabledRetriesAndDoesNotRetryOtherTransportErrors() {
        for (boolean disabled : new boolean[] {true, false}) {
            AtomicInteger calls = new AtomicInteger();
            var options =
                    disabled
                            ? GenerateOptions.builder()
                                    .executionConfig(
                                            ExecutionConfig.builder().maxAttempts(1).build())
                                    .build()
                            : GenerateOptions.builder().build();
            StepVerifier.create(
                            client(
                                    calls,
                                    attempt ->
                                            Flux.error(
                                                    disabled
                                                            ? goAway()
                                                            : new HttpTransportException(
                                                                    "Connection reset")))
                                    .stream("test", "http://localhost", request(), options))
                    .expectError()
                    .verify(DEADLINE);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void cancellingBackoffDoesNotStartAnotherRequest() {
        AtomicInteger calls = new AtomicInteger();
        StepVerifier.withVirtualTime(
                        () ->
                                client(calls, attempt -> Flux.error(goAway())).stream(
                                        "test",
                                        "http://localhost",
                                        request(),
                                        GenerateOptions.builder().build()))
                .thenAwait(Duration.ofMillis(100))
                .thenCancel()
                .verify(DEADLINE);
        assertEquals(1, calls.get());
    }

    private static OpenAIRequest request() {
        return OpenAIRequest.builder().model("test").build();
    }

    private static HttpTransportException goAway() {
        return new HttpTransportException(
                "SSE stream failed", new java.io.IOException("GOAWAY received"));
    }

    private static OpenAIClient client(AtomicInteger calls, IntFunction<Flux<String>> action) {
        return new OpenAIClient(
                new HttpTransport() {
                    public HttpResponse execute(HttpRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    public Flux<String> stream(HttpRequest request) {
                        return action.apply(calls.incrementAndGet());
                    }

                    public void close() {}
                });
    }
}
