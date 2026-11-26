package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.MdlTypes.MdlIssue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * People's conclusions on caliber lists (spec149 P2): a list's scenario and each caliber row are
 * reviewed, then each row is verified with a generated question; every conclusion binds to the
 * content hash, so editing a row voids only that row. Kept under {@code .platform/}, never
 * published; callers hold the workspace lock.
 */
public final class CaliberReviews {
    static final String FILE = ".platform/caliber-reviews.json";
    public static final String REVIEW_PENDING = "REVIEW_PENDING";
    public static final String VERIFY_PENDING = "VERIFY_PENDING";
    public static final String PROBLEM = "PROBLEM";
    public static final String PASSED = "PASSED";
    private static final ObjectMapper JSON = new ObjectMapper();

    private CaliberReviews() {}

    public record RowReview(
            String reviewedHash,
            String reviewedBy,
            String reviewedAt,
            String verifiedHash,
            String verifiedBy,
            String verifiedAt,
            // Row hash a person judged "problem" in verification; it stays failed until edited.
            String problemHash) {
        static RowReview empty() {
            return new RowReview(null, null, null, null, null, null, null);
        }
    }

    /** {@code lastRun}: caliber -> row hash of the latest verification run that expanded. */
    public record ListReview(
            String scenarioHash,
            String scenarioReviewedBy,
            String scenarioReviewedAt,
            Map<String, RowReview> rows,
            Map<String, String> lastRun) {
        static ListReview empty() {
            return new ListReview(null, null, null, new TreeMap<>(), new TreeMap<>());
        }
    }

    public static Map<String, ListReview> read(Path root) {
        Path file = root.resolve(FILE);
        if (!Files.isRegularFile(file)) return new TreeMap<>();
        try {
            Map<String, ListReview> raw =
                    JSON.readValue(
                            file.toFile(),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    Map<String, ListReview>>() {});
            Map<String, ListReview> out = new TreeMap<>();
            raw.forEach(
                    (k, v) ->
                            out.put(
                                    k,
                                    new ListReview(
                                            v.scenarioHash(),
                                            v.scenarioReviewedBy(),
                                            v.scenarioReviewedAt(),
                                            v.rows() == null
                                                    ? new TreeMap<>()
                                                    : new TreeMap<>(v.rows()),
                                            v.lastRun() == null
                                                    ? new TreeMap<>()
                                                    : new TreeMap<>(v.lastRun()))));
            return out;
        } catch (IOException e) {
            throw new DatasetException("读取口径核查记录失败：" + e.getMessage(), e);
        }
    }

    static void write(Path root, Map<String, ListReview> reviews) {
        Path file = root.resolve(FILE);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(reviews),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("保存口径核查记录失败：" + e.getMessage(), e);
        }
    }

    /** Hash of everything in a list except its calibers: model, names, scenario, conventions. */
    public static String scenarioHash(CaliberList list) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", list.model());
        m.put("name", list.name());
        m.put("scenario", scenarioShape(list.scenario()));
        m.put("unrestricted", list.unrestricted());
        try {
            return CaliberSources.sha(JSON.writeValueAsString(m));
        } catch (IOException e) {
            throw new DatasetException("计算口径摘要失败", e);
        }
    }

    /**
     * The scenario as hashed. spec149 P5 fields are left out while empty, so lists written before
     * them keep the hash their reviewers signed.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> scenarioShape(CaliberList.Scenario scenario) {
        Map<String, Object> shape = JSON.convertValue(scenario, LinkedHashMap.class);
        if (scenario.business() == null) shape.remove("business");
        if (scenario.audience() == null) shape.remove("audience");
        if (scenario.scope().isEmpty()) shape.remove("scope");
        if (shape.get("measures") instanceof List<?> measures)
            for (Object measure : measures)
                if (measure instanceof Map<?, ?> m) {
                    Map<String, Object> mm = (Map<String, Object>) m;
                    if (mm.get("synonyms") instanceof List<?> l && l.isEmpty())
                        mm.remove("synonyms");
                    if (Boolean.FALSE.equals(mm.get("isDefault"))) mm.remove("isDefault");
                    // spec149 P7-4 fields are left out while empty, keeping signed hashes.
                    if (mm.get("unit") == null) mm.remove("unit");
                    if (mm.get("period") == null) mm.remove("period");
                }
        return shape;
    }

    public static String rowStatus(ListReview review, CaliberList.Caliber row) {
        String hash = CaliberSources.hash(row);
        RowReview r = review == null ? null : review.rows().get(row.name());
        if (r == null || !hash.equals(r.reviewedHash())) return REVIEW_PENDING;
        if (hash.equals(r.verifiedHash())) return PASSED;
        if (hash.equals(r.problemHash())) return PROBLEM;
        return VERIFY_PENDING;
    }

    /**
     * spec149 P7-2 (ADR0156): in a caliber knowledge base every table is read through its list,
     * so a model without a list blocks publishing. ref_sql models are not tables and are skipped.
     */
    public static List<MdlIssue> coverage(Path root) {
        Set<String> covered = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : CaliberCompiler.lists(root).entrySet()) {
            CaliberList.Parsed parsed = CaliberList.parse(e.getKey(), e.getValue());
            if (parsed.ok()) covered.add(parsed.list().model().toLowerCase(java.util.Locale.ROOT));
        }
        List<String> missing = new ArrayList<>();
        List<String> all = new ArrayList<>();
        Path models = root.resolve("models");
        if (Files.isDirectory(models, LinkOption.NOFOLLOW_LINKS))
            try (Stream<Path> dirs = Files.list(models)) {
                dirs.filter(d -> Files.isRegularFile(d.resolve("metadata.yml")))
                        .filter(d -> !Files.exists(d.resolve("ref_sql.sql")))
                        .map(d -> d.getFileName().toString())
                        .peek(all::add)
                        .filter(m -> !covered.contains(m.toLowerCase(java.util.Locale.ROOT)))
                        .sorted()
                        .forEach(missing::add);
            } catch (IOException e) {
                return List.of(
                        new MdlIssue("error", "读取模型目录失败：" + e.getMessage(), "calibers-blocked"));
            }
        if (missing.isEmpty()) return List.of();
        StringBuilder hint = new StringBuilder();
        BusinessDocumentService.unmatchedDeclarations(root, all)
                .forEach(
                        (doc, names) ->
                                hint.append("文档《")
                                        .append(doc)
                                        .append("》写的适用模型 ")
                                        .append(String.join("、", names))
                                        .append(" 不是本知识库的表，请改成上面列出的表名后重新上传。"));
        return List.of(
                new MdlIssue(
                        "error",
                        "口径问数知识库的每张表都要有口径清单，"
                                + String.join("、", missing)
                                + " 还没有。上传开头写明“适用模型：表名”的业务文档后，建模页会自动起草；不需要的表请移出知识库。"
                                + hint,
                        "calibers-blocked"));
    }

    public static boolean scenarioReviewed(ListReview review, CaliberList list) {
        return review != null && scenarioHash(list).equals(review.scenarioHash());
    }

    /** A list ships when its scenario is reviewed, every row passed and nothing is in conflict. */
    public static boolean publishable(
            ListReview review, CaliberList list, List<CaliberSources.Conflict> conflicts) {
        if (!scenarioReviewed(review, list)) return false;
        if (!conflicts.isEmpty()) return false;
        for (CaliberList.Caliber c : list.calibers())
            if (!PASSED.equals(rowStatus(review, c))) return false;
        return true;
    }

    // ------------------------------------------------------------------ publication

    /**
     * spec149: tables publish independently. In the staging copy, a caliber list whose draft is
     * not fully reviewed and verified is replaced by its published version (or removed with its
     * generated files when it was never published), and a Cube no list generated is left out:
     * only Cubes a person reviewed reach query. Returns warnings naming what was held back.
     */
    public static List<MdlIssue> stage(Path draft, Path staging, Path published) {
        return stage(draft, staging, published, false);
    }

    /**
     * Like {@link #stage(Path, Path, Path)}; with {@code blockIncomplete} an unfinished list is an
     * error instead of being held back: a table with a caliber list is queried only through its
     * calibers, so the release waits until a person has finished the list (or deleted it).
     */
    public static List<MdlIssue> stage(
            Path draft, Path staging, Path published, boolean blockIncomplete) {
        List<MdlIssue> issues = new ArrayList<>();
        Map<String, ListReview> reviews = read(draft);
        CaliberSources.State sources = CaliberSources.read(draft);
        Set<String> paths = new LinkedHashSet<>(CaliberCompiler.lists(staging).keySet());
        paths.addAll(CaliberCompiler.lists(published).keySet());
        try {
            for (String path : paths) {
                Path draftFile = staging.resolve(path);
                Path publishedFile = published.resolve(path);
                String now =
                        Files.isRegularFile(draftFile)
                                ? Files.readString(draftFile, StandardCharsets.UTF_8)
                                : null;
                String before =
                        Files.isRegularFile(publishedFile)
                                ? Files.readString(publishedFile, StandardCharsets.UTF_8)
                                : null;
                if (now != null && now.equals(before)) continue;
                if (now == null) {
                    // Deleting a list removes calibers from query; that needs no row review.
                    issues.add(
                            new MdlIssue(
                                    "warning", "口径清单 " + path + " 已删除，本次发布后不再提供其口径", "calibers"));
                    continue;
                }
                CaliberList.Parsed parsed = CaliberList.parse(path, now);
                List<CaliberSources.Conflict> conflicts =
                        sources.conflicts().stream().filter(c -> c.list().equals(path)).toList();
                if (parsed.ok() && publishable(reviews.get(path), parsed.list(), conflicts))
                    continue;
                holdBack(staging, published, path, now, before);
                if (blockIncomplete) {
                    issues.add(
                            new MdlIssue(
                                    "error",
                                    "口径清单 "
                                            + path
                                            + " 还未全部核查并验证通过："
                                            + String.join(
                                                    "、",
                                                    blockers(reviews.get(path), parsed, conflicts))
                                            + "。有口径清单的表只按口径问数，请到“口径”页补完后再发布；不需要这份清单时删除它再发布",
                                    "calibers-blocked"));
                    continue;
                }
                issues.add(
                        new MdlIssue(
                                "warning",
                                "口径清单 "
                                        + path
                                        + " 有未核查或未通过验证的改动，本次未发布这些改动"
                                        + (before == null ? "，该表暂按基础语义模型问数" : "，问数继续使用已发布的口径"),
                                "calibers-held"));
            }
            for (String cube : unreviewedCubes(staging)) {
                deleteDir(staging.resolve(cube));
                issues.add(
                        new MdlIssue(
                                "warning", cube + " 不是由口径清单生成的指标，未经核查，本次不发布", "calibers-held"));
            }
        } catch (IOException e) {
            throw new DatasetException("准备口径发布内容失败：" + e.getMessage(), e);
        }
        // The selection rules must describe the lists that actually ship.
        CaliberCompiler.writeRules(staging);
        return issues;
    }

    /** What keeps a list from shipping, in the reviewer's words. */
    static List<String> blockers(
            ListReview review, CaliberList.Parsed parsed, List<CaliberSources.Conflict> conflicts) {
        if (!parsed.ok()) return List.of("清单有错误");
        CaliberList list = parsed.list();
        List<String> out = new ArrayList<>();
        if (!scenarioReviewed(review, list)) out.add("表说明待核查");
        Set<String> conflicted = new LinkedHashSet<>();
        for (CaliberSources.Conflict c : conflicts) conflicted.add(c.caliber());
        for (CaliberList.Caliber c : list.calibers()) {
            if (conflicted.contains(c.name())) {
                out.add(c.name() + "（冲突）");
                continue;
            }
            String status = rowStatus(review, c);
            if (REVIEW_PENDING.equals(status)) out.add(c.name() + "（待核查）");
            else if (VERIFY_PENDING.equals(status)) out.add(c.name() + "（待验证）");
            else if (PROBLEM.equals(status)) out.add(c.name() + "（有问题）");
        }
        return out;
    }

    private static void holdBack(
            Path staging, Path published, String path, String now, String before)
            throws IOException {
        for (String generated : generatedPaths(staging, path, now))
            deleteDir(staging.resolve(generated).getParent());
        Path stagedList = staging.resolve(path);
        if (before == null) {
            Files.deleteIfExists(stagedList);
            return;
        }
        Files.writeString(stagedList, before, StandardCharsets.UTF_8);
        for (String generated : generatedPaths(published, path, before)) {
            Path from = published.resolve(generated).getParent();
            Path to = staging.resolve(generated).getParent();
            deleteDir(to);
            copyDir(from, to);
        }
    }

    /** The generated files a list owns: by its compiled names and by their generated_by marker. */
    private static List<String> generatedPaths(Path root, String path, String yaml) {
        Set<String> out = new LinkedHashSet<>();
        CaliberList.Parsed parsed = CaliberList.parse(path, yaml);
        if (parsed.list() != null) {
            out.add("views/" + parsed.list().viewName() + "/metadata.yml");
            out.add("cubes/" + parsed.list().cubeName() + "/metadata.yml");
        }
        CaliberCompiler.generated(root)
                .forEach(
                        (p, content) -> {
                            if (content.contains(CaliberCompiler.GENERATED_BY + ": " + path))
                                out.add(p);
                        });
        List<String> existing = new ArrayList<>();
        for (String p : out) if (Files.isRegularFile(root.resolve(p))) existing.add(p);
        return existing;
    }

    /** Cube directories whose metadata no caliber list generated. */
    static List<String> unreviewedCubes(Path root) {
        Path dir = root.resolve("cubes");
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return out;
        Map<String, String> generated = CaliberCompiler.generated(root);
        try (Stream<Path> dirs = Files.list(dir)) {
            for (Path d : dirs.filter(Files::isDirectory).sorted().toList()) {
                String meta = "cubes/" + d.getFileName() + "/metadata.yml";
                if (Files.isRegularFile(root.resolve(meta)) && !generated.containsKey(meta))
                    out.add("cubes/" + d.getFileName());
            }
        } catch (IOException e) {
            throw new DatasetException("读取指标目录失败：" + e.getMessage(), e);
        }
        return out;
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.deleteIfExists(p);
        }
    }

    private static void copyDir(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
