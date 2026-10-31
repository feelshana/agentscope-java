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
import io.agentscope.dataagent.dataset.KnowledgeBaseAccessService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.UserEntity;
import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * spec104: the new-user checklist. Data and publish steps are derived from the knowledge bases the
 * user may query (own or shared); ask and analyze are milestones recorded by the chat endpoint.
 * The checklist is full on its first day, collapsed from the next calendar day (Asia/Shanghai) and
 * hidden after {@link #DAYS} days or when dismissed. Once every step is done it stays full, as a
 * completion card, until the end of that day and is hidden from the next.
 */
@Service
public class OnboardingService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int DAYS = 7;
    static final int MAX_STARTERS = 3;

    public static final String DATA = "data";
    public static final String PUBLISH = "publish";
    public static final String ASK = "ask";
    public static final String ANALYZE = "analyze";
    private static final Pattern TOUR_STEP = Pattern.compile("[a-z_]{1,32}");
    private static final Pattern TOUR_GROUP = Pattern.compile("[A-Za-z0-9-]{0,64}");
    private static final Set<String> TOUR_ROUTES = Set.of("", "upload", "datasource");
    static final Set<String> TIPS = Set.of("follow_ups", "analysis_params");

    public enum Stage {
        full,
        collapsed,
        hidden
    }

    public record Task(String id, boolean done) {}

    /** spec105: where a running guided tour is; the route is chosen at the "data source" step. */
    public record Tour(String step, String groupId, String route, boolean paused) {}

    /** A published knowledge base's recommended question for the query home page. */
    public record Starter(String groupId, String groupName, String question, boolean shared) {}

    /**
     * @param daysLeft days until the checklist hides by itself; 0 once hidden
     * @param queryable whether the user can query at least one published knowledge base
     * @param unpublished names of the user's own knowledge bases that are not published yet
     * @param tour the running guided tour, null when there is none
     * @param samples demo files available for one-click upload
     * @param demoSource whether the built-in read-only data source can be chosen
     */
    public record Status(
            Stage stage,
            List<Task> tasks,
            int completed,
            int daysLeft,
            boolean dismissed,
            List<String> tipsSeen,
            List<Starter> starters,
            boolean queryable,
            List<String> unpublished,
            Tour tour,
            List<String> samples,
            boolean demoSource) {}

    private final UserEntityRepository users;
    private final KnowledgeBaseAccessService access;
    private final DatasetRepository datasets;
    private final DemoDataService demo;
    private final Clock clock;

    @Autowired
    public OnboardingService(
            UserEntityRepository users,
            KnowledgeBaseAccessService access,
            DatasetRepository datasets,
            DemoDataService demo) {
        this(users, access, datasets, demo, Clock.systemUTC());
    }

    OnboardingService(
            UserEntityRepository users,
            KnowledgeBaseAccessService access,
            DatasetRepository datasets,
            DemoDataService demo,
            Clock clock) {
        this.users = users;
        this.access = access;
        this.datasets = datasets;
        this.demo = demo;
        this.clock = clock;
    }

    /** Starts counting days on the first call, so the first day is the day it is first seen. */
    @Transactional
    public Status status(String userId) {
        UserEntity user = lock(userId);
        if (user.getOnboardingStartedAt() == null) {
            user.setOnboardingStartedAt(clock.instant());
            users.save(user);
        }
        return view(user);
    }

    @Transactional
    public Status dismiss(String userId) {
        UserEntity user = lock(userId);
        user.setOnboardingDismissedAt(clock.instant());
        if (user.getOnboardingStartedAt() == null) user.setOnboardingStartedAt(clock.instant());
        users.save(user);
        return view(user);
    }

    /** Reopening from help shows the full checklist again and restarts the {@link #DAYS} days. */
    @Transactional
    public Status reopen(String userId) {
        UserEntity user = lock(userId);
        user.setOnboardingDismissedAt(null);
        user.setOnboardingStartedAt(clock.instant());
        users.save(user);
        return view(user);
    }

    /**
     * spec105: starts the new-user experience over for the caller. Knowledge bases and data stay;
     * ones that already exist simply stop counting towards the checklist.
     */
    @Transactional
    public Status reset(String userId) {
        UserEntity user = lock(userId);
        user.setOnboardingStartedAt(clock.instant());
        user.setOnboardingResetAt(clock.instant());
        user.setOnboardingDismissedAt(null);
        user.setOnboardingCompletedAt(null);
        user.setOnboardingMilestones(null);
        user.setOnboardingTipsSeen(null);
        user.setOnboardingTour(null);
        users.save(user);
        return view(user);
    }

    @Transactional
    public Status tipSeen(String userId, String tip) {
        if (!TIPS.contains(tip)) throw new DatasetException("未知的提示", 400);
        UserEntity user = lock(userId);
        user.setOnboardingTipsSeen(add(user.getOnboardingTipsSeen(), tip));
        users.save(user);
        return view(user);
    }

    /** Saves the tour position; a null tour ends it. Only ever touches the caller's own row. */
    @Transactional
    public Status saveTour(String userId, Tour tour) {
        UserEntity user = lock(userId);
        user.setOnboardingTour(tour == null ? null : encode(tour));
        users.save(user);
        return view(user);
    }

    private static String encode(Tour tour) {
        String group = tour.groupId() == null ? "" : tour.groupId();
        String route = tour.route() == null ? "" : tour.route();
        if (tour.step() == null
                || !TOUR_STEP.matcher(tour.step()).matches()
                || !TOUR_GROUP.matcher(group).matches()
                || !TOUR_ROUTES.contains(route)) {
            throw new DatasetException("引导进度无效", 400);
        }
        return tour.step() + "|" + group + "|" + route + "|" + tour.paused();
    }

    private static Tour decode(String value) {
        if (value == null) return null;
        String[] parts = value.split("\\|", -1);
        if (parts.length != 4) return null;
        return new Tour(
                parts[0],
                parts[1].isEmpty() ? null : parts[1],
                parts[2],
                Boolean.parseBoolean(parts[3]));
    }

    /** Called by the chat endpoint; unknown users and repeated milestones are ignored. */
    @Transactional
    public void record(String userId, String milestone) {
        if (userId == null || !(ASK.equals(milestone) || ANALYZE.equals(milestone))) return;
        users.findByIdForUpdate(userId)
                .filter(u -> !split(u.getOnboardingMilestones()).contains(milestone))
                .ifPresent(
                        u -> {
                            u.setOnboardingMilestones(add(u.getOnboardingMilestones(), milestone));
                            users.save(u);
                        });
    }

    private UserEntity lock(String userId) {
        return users.findByIdForUpdate(userId == null ? "" : userId)
                .orElseThrow(() -> new DatasetException("用户不存在", 401));
    }

    private Status view(UserEntity user) {
        String userId = user.getUserId();
        List<DatasetGroupEntity> groups = access.visible(userId, null);
        // spec105: preparing data is judged on the user's own knowledge bases only, so enabling a
        // public one never ticks those steps for people who have done nothing.
        List<DatasetGroupEntity> own =
                groups.stream().filter(g -> userId.equals(g.getOwnerId())).toList();
        var resetAt = user.getOnboardingResetAt();
        List<DatasetGroupEntity> counted =
                own.stream()
                        .filter(
                                g ->
                                        resetAt == null
                                                || g.getCreatedAt() == null
                                                || g.getCreatedAt().isAfter(resetAt))
                        .toList();
        List<String> ids = counted.stream().map(DatasetGroupEntity::getId).toList();
        boolean data =
                !ids.isEmpty()
                        && datasets.countForGroups(ids).stream().anyMatch(c -> c.getTotal() > 0);
        List<DatasetGroupEntity> published =
                groups.stream().filter(g -> g.getMdlVersion() > 0).toList();
        Set<String> milestones = split(user.getOnboardingMilestones());
        Task ask = new Task(ASK, milestones.contains(ASK));
        Task analyze = new Task(ANALYZE, milestones.contains(ANALYZE));
        // Someone who can already query but owns nothing only has the asking steps left.
        List<Task> tasks =
                own.isEmpty() && !published.isEmpty()
                        ? List.of(ask, analyze)
                        : List.of(
                                new Task(DATA, data),
                                new Task(
                                        PUBLISH,
                                        counted.stream().anyMatch(g -> g.getMdlVersion() > 0)),
                                ask,
                                analyze);
        int completed = (int) tasks.stream().filter(Task::done).count();

        // The first time every step is seen done is the completion day; the finished checklist
        // stays on the home page for the rest of that day.
        boolean allDone = completed == tasks.size();
        if (allDone && user.getOnboardingCompletedAt() == null) {
            user.setOnboardingCompletedAt(clock.instant());
            users.save(user);
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZONE);
        // Reopening starts a display window without resetting the first completion record.
        var firstCompleted = user.getOnboardingCompletedAt();
        var started = user.getOnboardingStartedAt();
        var visibleSince =
                started != null && firstCompleted != null && started.isAfter(firstCompleted)
                        ? started
                        : firstCompleted;
        boolean finishedEarlier =
                allDone && LocalDate.ofInstant(visibleSince, ZONE).isBefore(today);
        boolean dismissed = user.getOnboardingDismissedAt() != null;
        long day =
                user.getOnboardingStartedAt() == null
                        ? 0
                        : ChronoUnit.DAYS.between(
                                LocalDate.ofInstant(user.getOnboardingStartedAt(), ZONE), today);
        Stage stage;
        if (dismissed || finishedEarlier || day >= DAYS) stage = Stage.hidden;
        else if (allDone || day < 1) stage = Stage.full;
        else stage = Stage.collapsed;
        int daysLeft = stage == Stage.hidden ? 0 : (int) (DAYS - Math.max(day, 0));

        List<Starter> starters =
                published.stream()
                        .filter(
                                g ->
                                        g.getRecommendedQuestion() != null
                                                && !g.getRecommendedQuestion().isBlank())
                        .limit(MAX_STARTERS)
                        .map(
                                g ->
                                        new Starter(
                                                g.getId(),
                                                g.getName(),
                                                g.getRecommendedQuestion(),
                                                !userId.equals(g.getOwnerId())))
                        .toList();
        List<String> unpublished =
                groups.stream()
                        .filter(g -> g.getMdlVersion() <= 0 && userId.equals(g.getOwnerId()))
                        .map(DatasetGroupEntity::getName)
                        .limit(MAX_STARTERS)
                        .toList();
        return new Status(
                stage,
                tasks,
                completed,
                daysLeft,
                dismissed,
                List.copyOf(split(user.getOnboardingTipsSeen())),
                starters,
                !published.isEmpty(),
                unpublished,
                decode(user.getOnboardingTour()),
                demo.samples(),
                demo.sourceAvailable());
    }

    private static Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) return new LinkedHashSet<>();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String add(String csv, String value) {
        Set<String> values = split(csv);
        values.add(value);
        return String.join(",", values);
    }
}
