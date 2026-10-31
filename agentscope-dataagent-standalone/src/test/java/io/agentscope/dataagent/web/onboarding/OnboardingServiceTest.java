package io.agentscope.dataagent.web.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DemoDataService;
import io.agentscope.dataagent.dataset.KnowledgeBaseAccessService;
import io.agentscope.dataagent.web.onboarding.OnboardingService.Stage;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.UserEntity;
import io.agentscope.dataagent.web.persistence.jpa.UserEntityRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** spec104: checklist steps, day-based stages, dismissal and per-user isolation. */
class OnboardingServiceTest {
    /** 2026-10-29 09:00 in Asia/Shanghai. */
    private static final Instant DAY_ONE = Instant.parse("2026-10-29T01:00:00Z");

    private final UserEntityRepository users = mock(UserEntityRepository.class);
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final DatasetRepository datasets = mock(DatasetRepository.class);
    private final DemoDataService demo = mock(DemoDataService.class);
    private final Map<String, UserEntity> rows = new java.util.HashMap<>();

    @BeforeEach
    void users() {
        for (String id : List.of("alice", "bob")) {
            UserEntity user = new UserEntity(id, id, "x", "user");
            rows.put(id, user);
            when(users.findByIdForUpdate(id)).thenReturn(Optional.of(user));
        }
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        when(access.visible(any(), eq(null))).thenReturn(List.of());
        when(datasets.countForGroups(anyList())).thenReturn(List.of());
    }

    private OnboardingService at(Instant now) {
        return new OnboardingService(
                users, access, datasets, demo, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static DatasetGroupEntity group(String id, String owner, int version, String question) {
        DatasetGroupEntity g = mock(DatasetGroupEntity.class);
        when(g.getId()).thenReturn(id);
        when(g.getName()).thenReturn("库" + id);
        when(g.getOwnerId()).thenReturn(owner);
        when(g.getMdlVersion()).thenReturn(version);
        when(g.getRecommendedQuestion()).thenReturn(question);
        return g;
    }

    private static DatasetRepository.GroupCount count(String groupId, long total) {
        DatasetRepository.GroupCount c = mock(DatasetRepository.GroupCount.class);
        when(c.getGroupId()).thenReturn(groupId);
        when(c.getTotal()).thenReturn(total);
        return c;
    }

    private static List<Boolean> done(OnboardingService.Status s) {
        return s.tasks().stream().map(OnboardingService.Task::done).toList();
    }

    @Test
    void aNewUserStartsWithTheFullChecklist() {
        var status = at(DAY_ONE).status("alice");
        assertThat(status.stage()).isEqualTo(Stage.full);
        assertThat(done(status)).containsExactly(false, false, false, false);
        assertThat(status.daysLeft()).isEqualTo(7);
        assertThat(status.queryable()).isFalse();
        assertThat(rows.get("alice").getOnboardingStartedAt()).isEqualTo(DAY_ONE);
    }

    @Test
    void uploadedOrExternalDataCompletesTheFirstStep() {
        var own = group("g1", "alice", 0, null);
        when(access.visible("alice", null)).thenReturn(List.of(own));
        var counted = List.of(count("g1", 1));
        when(datasets.countForGroups(List.of("g1"))).thenReturn(counted);
        var status = at(DAY_ONE).status("alice");
        assertThat(done(status)).containsExactly(true, false, false, false);
        assertThat(status.unpublished()).containsExactly("库g1");
    }

    /** spec105: a public or shared knowledge base offers its question but ticks nothing. */
    @Test
    void someoneElsesPublishedKnowledgeBaseLeavesOnlyTheAskingSteps() {
        var shared = group("g2", "carol", 3, "最近一个月每天的访问次数");
        when(access.visible("bob", null)).thenReturn(List.of(shared));
        var counted = List.of(count("g2", 2));
        when(datasets.countForGroups(List.of("g2"))).thenReturn(counted);
        var status = at(DAY_ONE).status("bob");
        assertThat(status.tasks())
                .extracting(OnboardingService.Task::id)
                .containsExactly(OnboardingService.ASK, OnboardingService.ANALYZE);
        assertThat(status.completed()).isZero();
        assertThat(status.queryable()).isTrue();
        assertThat(status.starters())
                .containsExactly(new OnboardingService.Starter("g2", "库g2", "最近一个月每天的访问次数", true));
    }

    /** Owning a knowledge base keeps all four steps, judged on the user's own ones. */
    @Test
    void ownKnowledgeBaseKeepsFourStepsEvenWhenAPublicOneIsPublished() {
        var mine = group("g1", "bob", 0, null);
        var open = group("g2", "carol", 3, "最近一个月每天的访问次数");
        when(access.visible("bob", null)).thenReturn(List.of(mine, open));
        var counted = List.of(count("g1", 1));
        when(datasets.countForGroups(List.of("g1"))).thenReturn(counted);
        assertThat(done(at(DAY_ONE).status("bob"))).containsExactly(true, false, false, false);
    }

    /** spec105: resetting starts over without touching knowledge bases or anyone else. */
    @Test
    void resetStartsOverAndOnlyNewKnowledgeBasesCount() {
        var old = group("g1", "bob", 2, null);
        when(old.getCreatedAt()).thenReturn(DAY_ONE.minusSeconds(3600));
        when(access.visible("bob", null)).thenReturn(List.of(old));
        var counted = List.of(count("g1", 1));
        when(datasets.countForGroups(List.of("g1"))).thenReturn(counted);
        var service = at(DAY_ONE);
        service.record("bob", OnboardingService.ASK);
        service.record("alice", OnboardingService.ASK);
        service.saveTour("bob", new OnboardingService.Tour("publish", "g1", "upload", false));
        service.dismiss("bob");
        assertThat(done(service.status("bob"))).containsExactly(true, true, true, false);

        var status = service.reset("bob");
        assertThat(status.tasks()).hasSize(4);
        assertThat(status.completed()).isZero();
        assertThat(status.stage()).isEqualTo(Stage.full);
        assertThat(status.tour()).isNull();
        assertThat(status.dismissed()).isFalse();
        assertThat(rows.get("alice").getOnboardingMilestones()).contains(OnboardingService.ASK);

        var fresh = group("g2", "bob", 1, null);
        when(fresh.getCreatedAt()).thenReturn(DAY_ONE.plusSeconds(60));
        when(access.visible("bob", null)).thenReturn(List.of(old, fresh));
        var both = List.of(count("g2", 1));
        when(datasets.countForGroups(List.of("g2"))).thenReturn(both);
        assertThat(done(at(DAY_ONE.plusSeconds(120)).status("bob")))
                .containsExactly(true, true, false, false);
    }

    @Test
    void tourPositionIsSavedForTheCallerAndClearedWhenItEnds() {
        var service = at(DAY_ONE);
        var tour = new OnboardingService.Tour("sample", "g-1", "upload", true);
        assertThat(service.saveTour("bob", tour).tour()).isEqualTo(tour);
        assertThat(rows.get("alice").getOnboardingTour()).isNull();
        assertThat(service.status("bob").tour()).isEqualTo(tour);
        assertThat(service.saveTour("bob", null).tour()).isNull();
    }

    @Test
    void malformedTourPositionIsRejected() {
        var service = at(DAY_ONE);
        assertThatThrownBy(
                        () ->
                                service.saveTour(
                                        "bob", new OnboardingService.Tour("a|b", null, "", false)))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(
                        () ->
                                service.saveTour(
                                        "bob",
                                        new OnboardingService.Tour("home", null, "other", false)))
                .isInstanceOf(DatasetException.class);
    }

    /** Only knowledge bases the user may query count; another user's private one does not. */
    @Test
    void anotherUsersKnowledgeBaseDoesNotCount() {
        var alices = group("g1", "alice", 2, "9 月每天的订单金额");
        when(access.visible("alice", null)).thenReturn(List.of(alices));
        var counted = List.of(count("g1", 1));
        when(datasets.countForGroups(List.of("g1"))).thenReturn(counted);
        var bob = at(DAY_ONE).status("bob");
        assertThat(done(bob)).containsExactly(false, false, false, false);
        assertThat(bob.starters()).isEmpty();
    }

    @Test
    void milestonesAreRecordedOnceAndUnknownOnesIgnored() {
        var service = at(DAY_ONE);
        service.record("alice", OnboardingService.ASK);
        service.record("alice", OnboardingService.ASK);
        service.record("alice", "publish");
        service.record("nobody", OnboardingService.ASK);
        assertThat(rows.get("alice").getOnboardingMilestones()).isEqualTo("ask");
        assertThat(done(service.status("alice"))).containsExactly(false, false, true, false);
        assertThat(rows.get("bob").getOnboardingMilestones()).isNull();
    }

    /** Days follow Asia/Shanghai: 23:59 on day one is still full, the next morning collapsed. */
    @Test
    void stagesFollowCalendarDaysInShanghai() {
        at(DAY_ONE).status("alice");
        assertThat(at(Instant.parse("2026-10-29T15:59:00Z")).status("alice").stage())
                .isEqualTo(Stage.full);
        var dayTwo = at(Instant.parse("2026-10-29T16:01:00Z")).status("alice");
        assertThat(dayTwo.stage()).isEqualTo(Stage.collapsed);
        assertThat(dayTwo.daysLeft()).isEqualTo(6);
        assertThat(at(Instant.parse("2026-11-04T15:00:00Z")).status("alice").stage())
                .isEqualTo(Stage.collapsed);
        var daySeven = at(Instant.parse("2026-11-04T16:01:00Z")).status("alice");
        assertThat(daySeven.stage()).isEqualTo(Stage.hidden);
        assertThat(daySeven.daysLeft()).isZero();
    }

    /** The finished checklist stays for the rest of the completion day, then hides. */
    @Test
    void finishingEveryStepKeepsTheChecklistUntilTheDayEnds() {
        var own = group("g2", "alice", 1, null);
        when(access.visible("alice", null)).thenReturn(List.of(own));
        var counted = List.of(count("g2", 1));
        when(datasets.countForGroups(List.of("g2"))).thenReturn(counted);
        at(DAY_ONE).status("alice");
        // Finished on day three (2026-10-31 in Shanghai), while the checklist was collapsed.
        Instant dayThree = Instant.parse("2026-10-31T02:00:00Z");
        var service = at(dayThree);
        service.record("alice", OnboardingService.ASK);
        service.record("alice", OnboardingService.ANALYZE);
        var status = service.status("alice");
        assertThat(status.completed()).isEqualTo(4);
        assertThat(status.stage()).isEqualTo(Stage.full);
        assertThat(rows.get("alice").getOnboardingCompletedAt()).isEqualTo(dayThree);
        assertThat(at(Instant.parse("2026-10-31T15:59:00Z")).status("alice").stage())
                .isEqualTo(Stage.full);
        assertThat(at(Instant.parse("2026-10-31T16:01:00Z")).status("alice").stage())
                .isEqualTo(Stage.hidden);
        assertThat(rows.get("alice").getOnboardingCompletedAt()).isEqualTo(dayThree);
    }

    @Test
    void dismissHidesAndReopenRestartsTheSevenDays() {
        at(DAY_ONE).status("alice");
        Instant later = Instant.parse("2026-11-10T02:00:00Z");
        assertThat(at(DAY_ONE).dismiss("alice").stage()).isEqualTo(Stage.hidden);
        var reopened = at(later).reopen("alice");
        assertThat(reopened.stage()).isEqualTo(Stage.full);
        assertThat(reopened.dismissed()).isFalse();
        assertThat(reopened.daysLeft()).isEqualTo(7);
        assertThat(rows.get("alice").getOnboardingStartedAt()).isEqualTo(later);
    }

    @Test
    void tipsAreRememberedAndUnknownTipsRejected() {
        var service = at(DAY_ONE);
        service.tipSeen("alice", "follow_ups");
        assertThat(service.tipSeen("alice", "follow_ups").tipsSeen()).containsExactly("follow_ups");
        assertThatThrownBy(() -> service.tipSeen("alice", "../x"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("未知的提示");
        assertThat(rows.get("bob").getOnboardingTipsSeen()).isNull();
    }

    @Test
    void unknownUsersAreRejected() {
        when(users.findByIdForUpdate("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> at(DAY_ONE).status("ghost")).isInstanceOf(DatasetException.class);
    }

    @Test
    void completedUserCanReopenOnFollowingDayWithoutLosingProgress() {
        var own = group("g1", "alice", 1, "销售额");
        when(access.visible("alice", null)).thenReturn(List.of(own));
        var counted = List.of(count("g1", 1));
        when(datasets.countForGroups(List.of("g1"))).thenReturn(counted);
        var first = at(DAY_ONE);
        first.record("alice", OnboardingService.ASK);
        first.record("alice", OnboardingService.ANALYZE);
        assertThat(first.status("alice").completed()).isEqualTo(4);
        Instant completedAt = rows.get("alice").getOnboardingCompletedAt();
        Instant tomorrow = Instant.parse("2026-10-30T01:00:00Z");
        assertThat(at(tomorrow).status("alice").stage()).isEqualTo(Stage.hidden);
        var reopened = at(tomorrow).reopen("alice");
        assertThat(reopened.stage()).isEqualTo(Stage.full);
        assertThat(done(reopened)).containsExactly(true, true, true, true);
        assertThat(rows.get("alice").getOnboardingCompletedAt()).isEqualTo(completedAt);
        assertThat(rows.get("alice").getOnboardingMilestones()).isEqualTo("ask,analyze");
        assertThat(at(Instant.parse("2026-10-31T01:00:00Z")).status("alice").stage())
                .isEqualTo(Stage.hidden);
    }

    @Test
    void recordingATipBeforeFirstStatusDoesNotRequireAStartTimestamp() {
        var own = group("g1", "alice", 1, null);
        when(access.visible("alice", null)).thenReturn(List.of(own));
        var counted = List.of(count("g1", 1));
        when(datasets.countForGroups(List.of("g1"))).thenReturn(counted);
        var service = at(DAY_ONE);
        service.record("alice", OnboardingService.ASK);
        service.record("alice", OnboardingService.ANALYZE);
        assertThat(service.tipSeen("alice", "analysis_params").completed()).isEqualTo(4);
        assertThat(rows.get("alice").getOnboardingStartedAt()).isNull();
    }
}
