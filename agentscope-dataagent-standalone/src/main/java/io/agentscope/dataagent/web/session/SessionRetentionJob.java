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
package io.agentscope.dataagent.web.session;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.persistence.jpa.ArtifactRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryEntity;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Configurable, bounded cleanup of inactive complete conversations. */
@Component
@ConditionalOnProperty(name = "dataagent.history-retention.enabled", havingValue = "true")
public class SessionRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(SessionRetentionJob.class);

    private final DataAgentBootstrap bootstrap;
    private final SessionRegistryRepository registry;
    private final ArtifactRepository artifacts;
    private final SessionDeletionService deletion;
    private final int retentionDays;
    private final int batchSize;
    private final int maxPerRun;
    private final boolean dryRun;

    public SessionRetentionJob(
            DataAgentBootstrap bootstrap,
            SessionRegistryRepository registry,
            ArtifactRepository artifacts,
            SessionDeletionService deletion,
            @Value("${dataagent.history-retention.retention-days:90}") int retentionDays,
            @Value("${dataagent.history-retention.batch-size:50}") int batchSize,
            @Value("${dataagent.history-retention.max-per-run:500}") int maxPerRun,
            @Value("${dataagent.history-retention.dry-run:true}") boolean dryRun,
            @Value("${dataagent.history-retention.mode:full-session}") String mode) {
        if (retentionDays < 1
                || batchSize < 1
                || batchSize > 500
                || maxPerRun < 1
                || maxPerRun > 10_000) {
            throw new IllegalArgumentException("Invalid history retention limits");
        }
        if (!"full-session".equals(mode)) {
            throw new IllegalArgumentException(
                    "Only history retention mode full-session is supported");
        }
        this.bootstrap = bootstrap;
        this.registry = registry;
        this.artifacts = artifacts;
        this.deletion = deletion;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.maxPerRun = maxPerRun;
        this.dryRun = dryRun;
    }

    @Scheduled(
            cron = "${dataagent.history-retention.cron:0 0 3 * * *}",
            zone = "${dataagent.history-retention.zone:Asia/Shanghai}")
    public void run() {
        long cutoff = System.currentTimeMillis() - Duration.ofDays(retentionDays).toMillis();
        var candidates =
                registry.findRetentionCandidates(
                        cutoff, PageRequest.of(0, Math.min(maxPerRun, 10_000)));
        if (dryRun) {
            reportDryRun(cutoff, candidates);
            return;
        }
        int deleted = 0;
        int skipped = 0;
        int failed = 0;
        long bytes = 0;
        for (int offset = 0; offset < candidates.size(); offset += batchSize) {
            var batch = candidates.subList(offset, Math.min(offset + batchSize, candidates.size()));
            for (SessionRegistryEntity candidate : batch) {
                var entry =
                        bootstrap
                                .gateway()
                                .sessionAgentManager()
                                .getSession(candidate.getSessionKey());
                if (entry.isEmpty()
                        || bootstrap.gateway().isSessionActive(candidate.getSessionKey())) {
                    skipped++;
                    continue;
                }
                try {
                    long sessionBytes = usageBytes(candidate);
                    deletion.delete(entry.get());
                    bytes += sessionBytes;
                    deleted++;
                } catch (SessionDeletionService.SessionBusyException e) {
                    skipped++;
                } catch (Exception e) {
                    failed++;
                    log.warn(
                            "[history-retention] failed sessionKey={}; retry or orphan compensation"
                                    + " may be required",
                            candidate.getSessionKey(),
                            e);
                }
            }
            if (Thread.currentThread().isInterrupted()) break;
        }
        log.info(
                "[history-retention] complete cutoffMs={} candidates={} deleted={} skipped={}"
                        + " failed={} artifactBytesReleased={}",
                cutoff,
                candidates.size(),
                deleted,
                skipped,
                failed,
                bytes);
    }

    private void reportDryRun(long cutoff, java.util.List<SessionRegistryEntity> candidates) {
        long bytes = candidates.stream().mapToLong(this::usageBytes).sum();
        log.info(
                "[history-retention] dry-run cutoffMs={} candidates={} estimatedArtifactBytes={}",
                cutoff,
                candidates.size(),
                bytes);
    }

    private long usageBytes(SessionRegistryEntity candidate) {
        Object[] usage = artifacts.sessionUsage(candidate.getUserId(), candidate.getSessionId());
        return usage.length > 1 && usage[1] instanceof Number n ? n.longValue() : 0L;
    }
}
