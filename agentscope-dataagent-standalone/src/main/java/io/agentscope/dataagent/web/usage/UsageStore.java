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
package io.agentscope.dataagent.web.usage;

import io.agentscope.dataagent.web.persistence.jpa.UsageEventEntity;
import io.agentscope.dataagent.web.persistence.jpa.UsageEventRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usage event store backed by the {@code usage_event} MySQL table. Records individual turn events
 * (one per user message) and provides hourly/daily aggregations for trend charts.
 *
 * <p>Recent events (up to {@value #MAX_CACHE}) are loaded into an in-memory cache on startup so
 * that aggregation queries remain fast. New events are appended to both the database and the cache.
 */
@Component
public class UsageStore {

    private static final Logger log = LoggerFactory.getLogger(UsageStore.class);

    /** Maximum number of recent events to keep in memory for fast aggregation. */
    private static final int MAX_CACHE = 50_000;

    private final UsageEventRepository repository;
    private final List<UsageEvent> cache = new ArrayList<>();

    public UsageStore(UsageEventRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void loadCache() {
        List<UsageEventEntity> recent =
                repository.findAllByOrderByTimestampMsDesc(PageRequest.of(0, MAX_CACHE));
        for (UsageEventEntity e : recent) {
            cache.add(
                    new UsageEvent(
                            e.getTimestampMs(), e.getUserId(), e.getAgentId(), e.getDurationMs()));
        }
        log.info("Loaded {} usage events into memory cache", cache.size());
    }

    /** Records a single turn completion. */
    @Transactional
    public void record(String userId, String agentId, long durationMs) {
        long now = System.currentTimeMillis();
        repository.save(new UsageEventEntity(now, userId, agentId, durationMs));
        synchronized (cache) {
            cache.add(new UsageEvent(now, userId, agentId, durationMs));
            while (cache.size() > MAX_CACHE) {
                cache.remove(0);
            }
        }
    }

    /** Returns all raw events (newest-first) up to {@code limit}. */
    public List<UsageEvent> recentEvents(int limit) {
        List<UsageEvent> copy;
        synchronized (cache) {
            copy = new ArrayList<>(cache);
        }
        Collections.reverse(copy);
        return copy.stream().limit(limit).toList();
    }

    /**
     * Returns hourly turn counts for the past {@code hours} hours.
     *
     * @param hours number of hours of history to include (max 168 = 7 days)
     */
    public List<BucketCount> hourlyTurns(int hours) {
        int h = Math.max(1, Math.min(hours, 168));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) h * 3_600_000L;

        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < h; i++) {
            long bucketMs = startMs + (long) i * 3_600_000L;
            buckets.put(truncateHour(bucketMs), 0);
        }
        for (UsageEvent e : snapshot()) {
            if (e.timestampMs() < startMs) continue;
            long bucket = truncateHour(e.timestampMs());
            buckets.merge(bucket, 1, Integer::sum);
        }

        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelHour(en.getKey()), en.getValue()))
                .toList();
    }

    /**
     * Returns daily turn counts for the past {@code days} days.
     *
     * @param days number of days of history to include (max 90)
     */
    public List<BucketCount> dailyTurns(int days) {
        int d = Math.max(1, Math.min(days, 90));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) d * 86_400_000L;

        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < d; i++) {
            long bucketMs = startMs + (long) i * 86_400_000L;
            buckets.put(truncateDay(bucketMs), 0);
        }
        for (UsageEvent e : snapshot()) {
            if (e.timestampMs() < startMs) continue;
            long bucket = truncateDay(e.timestampMs());
            buckets.merge(bucket, 1, Integer::sum);
        }

        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelDay(en.getKey()), en.getValue()))
                .toList();
    }

    /** Returns aggregate totals for a specific user only. */
    public UsageSummary summaryForUser(String userId) {
        long totalTurns = repository.countByUserSince(userId, 0L);
        long today = truncateDay(System.currentTimeMillis());
        long todayTurns = repository.countByUserSince(userId, today);
        Long avg = repository.avgDurationByUser(userId);
        long avgDurationMs = avg != null ? avg : 0L;
        return new UsageSummary(totalTurns, todayTurns, avgDurationMs, 1L);
    }

    /** Returns hourly turn counts for the past {@code hours} hours, scoped to one user. */
    public List<BucketCount> hourlyTurnsForUser(String userId, int hours) {
        int h = Math.max(1, Math.min(hours, 168));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) h * 3_600_000L;
        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < h; i++) {
            buckets.put(truncateHour(startMs + (long) i * 3_600_000L), 0);
        }
        for (UsageEvent e : snapshot()) {
            if (!userId.equals(e.userId()) || e.timestampMs() < startMs) continue;
            buckets.merge(truncateHour(e.timestampMs()), 1, Integer::sum);
        }
        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelHour(en.getKey()), en.getValue()))
                .toList();
    }

    /** Returns daily turn counts for the past {@code days} days, scoped to one user. */
    public List<BucketCount> dailyTurnsForUser(String userId, int days) {
        int d = Math.max(1, Math.min(days, 90));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) d * 86_400_000L;
        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < d; i++) {
            buckets.put(truncateDay(startMs + (long) i * 86_400_000L), 0);
        }
        for (UsageEvent e : snapshot()) {
            if (!userId.equals(e.userId()) || e.timestampMs() < startMs) continue;
            buckets.merge(truncateDay(e.timestampMs()), 1, Integer::sum);
        }
        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelDay(en.getKey()), en.getValue()))
                .toList();
    }

    /**
     * Returns top-N users by turn count over the past {@code days} days.
     * Used by admin usage dashboard.
     */
    public List<GroupCount> topUsersByTurns(int days, int topN) {
        long startMs = System.currentTimeMillis() - (long) days * 86_400_000L;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (UsageEvent e : snapshot()) {
            if (e.timestampMs() < startMs || e.userId() == null) continue;
            counts.merge(e.userId(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(en -> new GroupCount(en.getKey(), en.getValue()))
                .toList();
    }

    /**
     * Returns top-N agents by turn count over the past {@code days} days.
     * Used by admin usage dashboard.
     */
    public List<GroupCount> topAgentsByTurns(int days, int topN) {
        long startMs = System.currentTimeMillis() - (long) days * 86_400_000L;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (UsageEvent e : snapshot()) {
            if (e.timestampMs() < startMs || e.agentId() == null) continue;
            counts.merge(e.agentId(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(en -> new GroupCount(en.getKey(), en.getValue()))
                .toList();
    }

    /** Returns aggregate totals. */
    public UsageSummary summary() {
        long totalTurns = repository.count();
        long today = truncateDay(System.currentTimeMillis());
        long todayTurns = repository.countSince(today);
        Long avg = repository.avgDuration();
        long avgDurationMs = avg != null ? avg : 0L;
        long uniqueUsers = repository.countDistinctUsers();
        return new UsageSummary(totalTurns, todayTurns, avgDurationMs, uniqueUsers);
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private List<UsageEvent> snapshot() {
        synchronized (cache) {
            return List.copyOf(cache);
        }
    }

    private static long truncateHour(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.HOURS)
                .toInstant()
                .toEpochMilli();
    }

    private static long truncateDay(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant()
                .toEpochMilli();
    }

    private static String labelHour(long epochMs) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
        return String.format("%02d:%02d", zdt.getHour(), 0);
    }

    private static String labelDay(long epochMs) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
        return String.format("%02d-%02d", zdt.getMonthValue(), zdt.getDayOfMonth());
    }

    // -----------------------------------------------------------------
    //  DTO types
    // -----------------------------------------------------------------

    public record UsageEvent(long timestampMs, String userId, String agentId, long durationMs) {}

    public record BucketCount(long epochMs, String label, int count) {}

    public record GroupCount(String key, int count) {}

    public record UsageSummary(
            long totalTurns, long todayTurns, long avgDurationMs, long uniqueUsers) {}
}
