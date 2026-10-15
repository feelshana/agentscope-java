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
package io.agentscope.dataagent.web.middleware;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Measures page reads separately from browser connection queueing. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PageReadTimingFilter implements WebFilter {
    private static final Logger log = LoggerFactory.getLogger(PageReadTimingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.equals("/api/dataset-groups")
                && !(path.startsWith("/api/agents/") && path.endsWith("/workspace/files"))) {
            return chain.filter(exchange);
        }
        return Mono.defer(
                () -> {
                    long start = System.nanoTime();
                    String requestId = exchange.getRequest().getId();
                    log.debug("[page-read] started requestId={} path={}", requestId, path);
                    return chain.filter(exchange)
                            .doFinally(
                                    signal -> {
                                        long elapsedMs =
                                                TimeUnit.NANOSECONDS.toMillis(
                                                        System.nanoTime() - start);
                                        if (elapsedMs >= 1000) {
                                            log.warn(
                                                    "[page-read] requestId={} path={} elapsedMs={}"
                                                            + " status={} signal={}",
                                                    requestId,
                                                    path,
                                                    elapsedMs,
                                                    exchange.getResponse().getStatusCode(),
                                                    signal);
                                        } else {
                                            log.debug(
                                                    "[page-read] requestId={} path={} elapsedMs={}"
                                                            + " signal={}",
                                                    requestId,
                                                    path,
                                                    elapsedMs,
                                                    signal);
                                        }
                                    });
                });
    }
}
