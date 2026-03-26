/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.dataanalysis.service;

import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.examples.dataanalysis.dto.PlanResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Service for managing the PlanNotebook and broadcasting plan changes via SSE.
 */
@Service
public class AnalysisPlanService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPlanService.class);

    private final Sinks.Many<PlanResponse> planSink =
            Sinks.many().multicast().onBackpressureBuffer();

    private PlanNotebook planNotebook;

    public void setPlanNotebook(PlanNotebook planNotebook) {
        this.planNotebook = planNotebook;
    }

    public PlanNotebook getPlanNotebook() {
        return planNotebook;
    }

    /**
     * SSE stream – sends the current plan state immediately, then pushes each subsequent update.
     */
    public Flux<PlanResponse> getPlanStream() {
        return Flux.concat(
                Mono.fromCallable(
                        () -> {
                            PlanResponse current = getCurrentPlan();
                            return current != null ? current : new PlanResponse();
                        }),
                planSink.asFlux());
    }

    /**
     * Push the latest plan state to all SSE subscribers.
     */
    public void broadcastPlanChange() {
        PlanResponse response = getCurrentPlan();
        if (response == null) {
            response = new PlanResponse();
        }
        planSink.tryEmitNext(response);
        log.debug(
                "Plan broadcast: {}", response.getName() != null ? response.getName() : "(empty)");
    }

    /**
     * Snapshot of the current plan state.
     */
    public PlanResponse getCurrentPlan() {
        if (planNotebook == null) {
            return null;
        }
        return PlanResponse.fromPlan(planNotebook.getCurrentPlan());
    }
}
