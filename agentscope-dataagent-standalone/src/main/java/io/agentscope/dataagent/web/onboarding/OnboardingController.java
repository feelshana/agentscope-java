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
package io.agentscope.dataagent.web.onboarding;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DemoDataService;
import java.util.concurrent.Callable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** spec104: the current user's own onboarding state; there is no way to address another user. */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {
    private final OnboardingService onboarding;
    private final DemoDataService demo;

    public OnboardingController(OnboardingService onboarding, DemoDataService demo) {
        this.onboarding = onboarding;
        this.demo = demo;
    }

    @GetMapping
    public Mono<OnboardingService.Status> get(Authentication auth) {
        String user = (String) auth.getPrincipal();
        return call(() -> onboarding.status(user));
    }

    @PostMapping("/dismiss")
    public Mono<OnboardingService.Status> dismiss(Authentication auth) {
        String user = (String) auth.getPrincipal();
        return call(() -> onboarding.dismiss(user));
    }

    @PostMapping("/reopen")
    public Mono<OnboardingService.Status> reopen(Authentication auth) {
        String user = (String) auth.getPrincipal();
        return call(() -> onboarding.reopen(user));
    }

    @PostMapping("/reset")
    public Mono<OnboardingService.Status> reset(Authentication auth) {
        String user = (String) auth.getPrincipal();
        return call(() -> onboarding.reset(user));
    }

    @PostMapping("/tips/{tip}")
    public Mono<OnboardingService.Status> tip(@PathVariable String tip, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return call(() -> onboarding.tipSeen(user, tip));
    }

    @PutMapping("/tour")
    public Mono<OnboardingService.Status> tour(
            @RequestBody OnboardingService.Tour tour, Authentication auth) {
        String user = (String) auth.getPrincipal();
        return call(() -> onboarding.saveTour(user, tour));
    }

    @DeleteMapping("/tour")
    public Mono<OnboardingService.Status> endTour(Authentication auth) {
        String user = (String) auth.getPrincipal();
        return call(() -> onboarding.saveTour(user, null));
    }

    /** spec105: demo files for one-click upload; they then go through the normal upload. */
    @GetMapping("/samples/{name}")
    public Mono<ResponseEntity<byte[]>> sample(@PathVariable String name) {
        return Mono.fromCallable(() -> demo.sample(name))
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        bytes ->
                                ResponseEntity.ok()
                                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                        .body(bytes))
                .onErrorMap(DatasetException.class, OnboardingController::toStatus);
    }

    private static ResponseStatusException toStatus(DatasetException e) {
        return new ResponseStatusException(HttpStatus.valueOf(e.status()), e.getMessage(), e);
    }

    private static Mono<OnboardingService.Status> call(Callable<OnboardingService.Status> action) {
        return Mono.fromCallable(action)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(DatasetException.class, OnboardingController::toStatus);
    }
}
