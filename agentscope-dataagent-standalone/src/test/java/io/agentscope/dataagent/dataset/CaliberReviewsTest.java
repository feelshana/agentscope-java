package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** spec149 P2: tables publish independently; only reviewed and verified calibers ship. */
class CaliberReviewsTest {
    @TempDir Path home;

    static final String MODEL =
            "name: vip_m\n"
                    + "columns:\n"
                    + "  - {name: cnt_zd, type: BIGINT}\n"
                    + "  - {name: vip_type_6, type: VARCHAR}\n";
    static final String V1 =
            """
            model: vip_m
            name: vip
            scenario:
              subject: 会员规模
              measures:
                - {column: cnt_zd, name: members}
            calibers:
              - {name: 足球通, where: {vip_type_6: 足球通会员}}
            """;
    static final String V2 = V1 + "  - {name: 篮球通, where: {vip_type_6: 篮球通会员}}\n";

    @Test
    void unreviewedChangesFallBackToThePublishedListAndNewListsStayOut() throws Exception {
        Path draft = home.resolve("draft");
        Path staging = home.resolve("staging");
        Path published = home.resolve("published");
        for (Path p : new Path[] {draft, staging, published})
            write(p, "models/vip_m/metadata.yml", MODEL);
        // Published v1; draft (copied to staging) has v2 plus a new list and a hand-written cube.
        write(published, "calibers/vip.yml", V1);
        CaliberCompiler.sync(published);
        for (Path p : new Path[] {draft, staging}) {
            write(p, "calibers/vip.yml", V2);
            write(p, "calibers/fresh.yml", V1.replace("name: vip", "name: fresh"));
            write(p, "cubes/manual/metadata.yml", "name: manual\nbase_object: vip_m\n");
            CaliberCompiler.sync(p);
        }

        var issues = CaliberReviews.stage(draft, staging, published);

        assertThat(issues)
                .extracting(MdlTypes.MdlIssue::message)
                .anySatisfy(m -> assertThat(m).contains("calibers/vip.yml", "问数继续使用已发布的口径"))
                .anySatisfy(m -> assertThat(m).contains("calibers/fresh.yml", "暂按基础语义模型问数"))
                .anySatisfy(m -> assertThat(m).contains("cubes/manual", "不发布"));
        assertThat(Files.readString(staging.resolve("calibers/vip.yml"))).isEqualTo(V1);
        assertThat(Files.readString(staging.resolve("views/vip_caliber/metadata.yml")))
                .isEqualTo(Files.readString(published.resolve("views/vip_caliber/metadata.yml")))
                .doesNotContain("篮球通");
        assertThat(staging.resolve("calibers/fresh.yml")).doesNotExist();
        assertThat(staging.resolve("views/fresh_caliber")).doesNotExist();
        assertThat(staging.resolve("cubes/fresh_metrics")).doesNotExist();
        assertThat(staging.resolve("cubes/manual")).doesNotExist();
        assertThat(CaliberCompiler.check(staging)).isEmpty();
        // The draft itself is never touched.
        assertThat(Files.readString(draft.resolve("calibers/vip.yml"))).isEqualTo(V2);
    }

    @Test
    void publishingRefusesAnUnfinishedListAndNamesWhatIsMissing() throws Exception {
        Path draft = home.resolve("draft");
        Path staging = home.resolve("staging");
        Path published = home.resolve("published");
        for (Path p : new Path[] {draft, staging}) {
            write(p, "models/vip_m/metadata.yml", MODEL);
            write(p, "calibers/vip.yml", V2);
            CaliberCompiler.sync(p);
        }
        Files.createDirectories(published);
        CaliberList list = CaliberList.parse("calibers/vip.yml", V2).list();
        var rows = new TreeMap<String, CaliberReviews.RowReview>();
        CaliberList.Caliber first = list.calibers().get(0);
        String hash = CaliberSources.hash(first);
        // The first caliber was reviewed but never judged; every other row passed.
        rows.put(
                first.name(),
                new CaliberReviews.RowReview(hash, "alice", "t", null, null, null, null));
        for (CaliberList.Caliber c : list.calibers().subList(1, list.calibers().size())) {
            String h = CaliberSources.hash(c);
            rows.put(
                    c.name(), new CaliberReviews.RowReview(h, "alice", "t", h, "alice", "t", null));
        }
        CaliberReviews.write(
                draft,
                new TreeMap<>(
                        java.util.Map.of(
                                "calibers/vip.yml",
                                new CaliberReviews.ListReview(
                                        CaliberReviews.scenarioHash(list),
                                        "alice",
                                        "t",
                                        rows,
                                        new TreeMap<>()))));

        assertThat(CaliberReviews.stage(draft, staging, published, true))
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i.severity()).isEqualTo("error");
                            assertThat(i.source()).isEqualTo("calibers-blocked");
                            assertThat(i.message())
                                    .contains("calibers/vip.yml", first.name() + "（待验证）")
                                    .doesNotContain("表说明待核查");
                        });
        // A draft check keeps the warning, so editing and document enhancement are not blocked.
        Path again = home.resolve("staging2");
        write(again, "models/vip_m/metadata.yml", MODEL);
        write(again, "calibers/vip.yml", V2);
        CaliberCompiler.sync(again);
        assertThat(CaliberReviews.stage(draft, again, published))
                .singleElement()
                .satisfies(i -> assertThat(i.severity()).isEqualTo("warning"));
    }

    @Test
    void aFullyReviewedAndVerifiedListShips() throws Exception {
        Path draft = home.resolve("draft");
        Path staging = home.resolve("staging");
        Path published = home.resolve("published");
        for (Path p : new Path[] {draft, staging}) {
            write(p, "models/vip_m/metadata.yml", MODEL);
            write(p, "calibers/vip.yml", V2);
            CaliberCompiler.sync(p);
        }
        Files.createDirectories(published);
        CaliberList list = CaliberList.parse("calibers/vip.yml", V2).list();
        Map<String, CaliberReviews.RowReview> rows = new TreeMap<>();
        for (CaliberList.Caliber c : list.calibers()) {
            String h = CaliberSources.hash(c);
            rows.put(
                    c.name(), new CaliberReviews.RowReview(h, "alice", "t", h, "alice", "t", null));
        }
        Map<String, CaliberReviews.ListReview> reviews = new TreeMap<>();
        reviews.put(
                "calibers/vip.yml",
                new CaliberReviews.ListReview(
                        CaliberReviews.scenarioHash(list), "alice", "t", rows, new TreeMap<>()));
        CaliberReviews.write(draft, reviews);

        assertThat(CaliberReviews.stage(draft, staging, published)).isEmpty();
        assertThat(Files.readString(staging.resolve("calibers/vip.yml"))).isEqualTo(V2);
        assertThat(Files.readString(staging.resolve("views/vip_caliber/metadata.yml")))
                .contains("篮球通");
    }

    @Test
    void ruleLinesSpellingOutACaliberConditionAreFlagged() throws Exception {
        Path root = home.resolve("draft");
        write(root, "models/vip_m/metadata.yml", MODEL);
        write(root, "calibers/vip.yml", V2);
        write(
                root,
                "knowledge/rules/general.md",
                "# 规则\n- 用户问足球通时取 vip_type_6 = '足球通会员'\n- 不同口径不能相加\n");

        assertThat(CaliberCompiler.ruleWarnings(root))
                .singleElement()
                .satisfies(
                        i ->
                                assertThat(i.message())
                                        .contains("足球通", "疑似口径漏进规则", "calibers/vip.yml"));
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
