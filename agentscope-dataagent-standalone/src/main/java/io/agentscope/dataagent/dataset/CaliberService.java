package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Caliber lists of a knowledge base for the modeling page (spec149 P1): which tables a business
 * document declares, whether their list is missing or older than the documents, and each row
 * with its provenance, conflicts and a check that its values exist. Requires MANAGE.
 */
@Service
public class CaliberService {
    static final String VALUES_FILE = ".platform/caliber-values.json";
    static final int VALUE_LIMIT = 500;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper EDIT_YAML =
            new ObjectMapper(
                    new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()
                            .disable(
                                    com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature
                                            .WRITE_DOC_START_MARKER)
                            .enable(
                                    com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature
                                            .MINIMIZE_QUOTES));
    private static final int MAX_EDIT_TEXT = 500;
    private static final int MAX_EDIT_VALUES = 50;

    private final DatasetGroupService groups;
    private final MdlWorkspaceService workspace;
    private final DatasetRepository datasets;
    private final TableProvisioner provisioner;
    private final MdlPublishService publisher;
    // spec149 P7-4: reads the full distinct values of an associated (external) table.
    private final DatasetService datasetService;

    public CaliberService(
            DatasetGroupService groups,
            MdlWorkspaceService workspace,
            DatasetRepository datasets,
            TableProvisioner provisioner,
            MdlPublishService publisher) {
        this(groups, workspace, datasets, provisioner, publisher, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CaliberService(
            DatasetGroupService groups,
            MdlWorkspaceService workspace,
            DatasetRepository datasets,
            TableProvisioner provisioner,
            MdlPublishService publisher,
            DatasetService datasetService) {
        this.groups = groups;
        this.workspace = workspace;
        this.datasets = datasets;
        this.provisioner = provisioner;
        this.publisher = publisher;
        this.datasetService = datasetService;
    }

    /** {@code kind}: missing (uploaded table has no such value), sample (not seen in a sample). */
    public record ValueIssue(String column, String value, String kind) {}

    public record Row(
            String name,
            String group,
            String condition,
            Map<String, List<String>> where,
            List<String> synonyms,
            String note,
            String pending,
            String combine,
            CaliberSources.RowSource source,
            List<ValueIssue> valueIssues,
            // spec149 P2: REVIEW_PENDING, VERIFY_PENDING, PROBLEM or PASSED (CaliberReviews).
            String review,
            String reviewedBy,
            String verifiedBy,
            // spec149 P7-4: a caliber's own unit; shown and edited on the review page.
            String unit) {}

    /**
     * {@code status}: DRAFTABLE (documents, no list), OUTDATED (a document changed or was added
     * since the list was drafted), CURRENT, MANUAL (a list no document declares), or NO_DOCUMENT
     * (a caliber knowledge base's table with neither a list nor a declaring document, spec153).
     */
    public record Table(
            String model,
            String listPath,
            String subject,
            String status,
            List<String> documents,
            List<String> changedDocuments,
            String viewName,
            String cubeName,
            List<String> errors,
            List<Row> rows,
            List<CaliberSources.Conflict> conflicts,
            boolean scenarioReviewed,
            // Whether the draft list ships on the next publish (CaliberReviews#publishable).
            boolean publishable,
            // NONE: never published, query uses the base model; CURRENT: the published list equals
            // the draft; OLDER: query still uses an earlier published version of the list.
            String published,
            String viewYaml,
            String cubeYaml,
            List<String> ruleWarnings,
            // spec149 P5: business, audience, measures and scope, reviewed with the scenario.
            CaliberList.Scenario scenario,
            List<ValueIssue> scopeIssues,
            // spec153 P1.5: the columns the list uses, for the row edit form.
            List<Column> columns) {}

    /**
     * A column a list uses: its display name from the model and the values the platform counted
     * ({@code null} when not counted, e.g. too many values or only a sample is known).
     */
    public record Column(String name, String label, List<String> values) {}

    /**
     * spec153 P1.5: a person's edit of one caliber from the review page. The name cannot change;
     * blank texts are removed from the list.
     */
    public record RowEdit(
            String group,
            List<String> synonyms,
            Map<String, List<String>> where,
            String unit,
            String note,
            String pending,
            // Rows of several values add up (combine: sum); only a person confirms it (P7-1).
            boolean combine) {}

    public List<Table> overview(String userId, String groupId) {
        boolean caliberKb = groups.getGroup(userId, groupId).isCaliberMode();
        Snapshot snapshot =
                workspace.withWorkspaceLock(groupId, () -> snapshot(groupId, caliberKb));
        // Value lookups may query the dataset database; never hold the workspace lock for them.
        Map<String, Map<String, List<String>>> values = values(groupId, snapshot);
        List<Table> out = new ArrayList<>();
        for (String model : snapshot.models()) out.add(table(snapshot, model, values.get(model)));
        return out;
    }

    // ------------------------------------------------------------------ snapshot

    private record Snapshot(
            Path root,
            List<String> models,
            Map<String, String> listPathByModel,
            Map<String, CaliberCompiler.Compiled> compiled,
            Map<String, Map<String, String>> documentsByModel,
            CaliberSources.State sources,
            Map<String, String> datasetByModel,
            Map<String, CaliberValues> cachedValues,
            Map<String, CaliberReviews.ListReview> reviews,
            Map<String, List<String>> ruleWarnings,
            Map<String, String> draftLists,
            Map<String, String> publishedLists) {}

    private Snapshot snapshot(String groupId, boolean allTables) {
        Path root = workspace.workspaceRoot(groupId);
        Map<String, String> listPathByModel = new LinkedHashMap<>();
        Map<String, CaliberCompiler.Compiled> compiled = new LinkedHashMap<>();
        Set<String> models = new LinkedHashSet<>();
        for (Map.Entry<String, String> list : CaliberCompiler.lists(root).entrySet()) {
            CaliberCompiler.Compiled c =
                    CaliberCompiler.compile(root, list.getKey(), list.getValue());
            String model =
                    c.list() != null ? c.list().model() : modelOf(list.getKey(), list.getValue());
            if (model == null) model = list.getKey();
            listPathByModel.put(model.toLowerCase(Locale.ROOT), list.getKey());
            compiled.put(list.getKey(), c);
            models.add(model);
        }
        Map<String, Map<String, String>> documentsByModel =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String model : modelNames(root)) {
            Map<String, String> docs = BusinessDocumentService.documentsFor(root, model);
            if (docs.isEmpty()) {
                // spec153: in a caliber knowledge base every table needs a list before release
                // (ADR0156), so a table without a document is listed as waiting for one instead
                // of being hidden. ref_sql models are not tables.
                boolean listed = models.stream().anyMatch(m -> m.equalsIgnoreCase(model));
                if (allTables
                        && !listed
                        && !Files.exists(
                                root.resolve("models").resolve(model).resolve("ref_sql.sql")))
                    models.add(model);
                continue;
            }
            documentsByModel.put(model, docs);
            models.add(model);
        }
        return new Snapshot(
                root,
                List.copyOf(models),
                listPathByModel,
                compiled,
                documentsByModel,
                CaliberSources.read(root),
                datasetMapping(root),
                readValues(root),
                CaliberReviews.read(root),
                ruleWarnings(root),
                CaliberCompiler.lists(root),
                CaliberCompiler.lists(root.getParent().resolve("published")));
    }

    private static Map<String, List<String>> ruleWarnings(Path root) {
        Map<String, List<String>> out = new TreeMap<>();
        for (MdlTypes.MdlIssue issue : CaliberCompiler.ruleWarnings(root)) {
            int at = issue.message().lastIndexOf("口径清单 ");
            String list = at < 0 ? "" : issue.message().substring(at + "口径清单 ".length()).strip();
            out.computeIfAbsent(list, k -> new ArrayList<>()).add(issue.message());
        }
        return out;
    }

    private static String modelOf(String path, String yaml) {
        CaliberList.Parsed parsed = CaliberList.parse(path, yaml);
        return parsed.list() == null ? null : parsed.list().model();
    }

    private static List<String> modelNames(Path root) {
        Path dir = root.resolve("models");
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> dirs = Files.list(dir)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("metadata.yml")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DatasetException("读取模型列表失败：" + e.getMessage(), e);
        }
    }

    private static Map<String, String> datasetMapping(Path root) {
        Path file = root.resolve(".platform/datasets.json");
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (!Files.isRegularFile(file)) return out;
        try {
            Map<String, String> byDataset =
                    JSON.readValue(file.toFile(), new TypeReference<Map<String, String>>() {});
            byDataset.forEach((dataset, model) -> out.put(model, dataset));
        } catch (IOException e) {
            // A missing mapping only disables value checks.
        }
        return out;
    }

    // ------------------------------------------------------------------ table

    private Table table(Snapshot s, String model, Map<String, List<String>> values) {
        String listPath = s.listPathByModel().get(model.toLowerCase(Locale.ROOT));
        Map<String, String> docs = s.documentsByModel().getOrDefault(model, Map.of());
        if (listPath == null)
            return new Table(
                    model,
                    null,
                    null,
                    docs.isEmpty() ? "NO_DOCUMENT" : "DRAFTABLE",
                    List.copyOf(docs.keySet()),
                    List.of(),
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    false,
                    "NONE",
                    null,
                    null,
                    List.of(),
                    null,
                    List.of(),
                    List.of());
        Map<String, String> drafted = s.sources().documents().getOrDefault(listPath, Map.of());
        List<String> changed = new ArrayList<>();
        docs.forEach(
                (name, hash) -> {
                    if (!hash.equals(drafted.get(name))) changed.add(name);
                });
        String status = docs.isEmpty() ? "MANUAL" : changed.isEmpty() ? "CURRENT" : "OUTDATED";
        CaliberCompiler.Compiled compiled = s.compiled().get(listPath);
        CaliberList list = compiled.list();
        Map<String, CaliberSources.RowSource> sources =
                s.sources().rows().getOrDefault(listPath, Map.of());
        CaliberReviews.ListReview review = s.reviews().get(listPath);
        List<Row> rows = new ArrayList<>();
        if (list != null)
            for (CaliberList.Caliber c : list.calibers()) {
                CaliberReviews.RowReview r = review == null ? null : review.rows().get(c.name());
                rows.add(
                        new Row(
                                c.name(),
                                c.group(),
                                CaliberCompiler.condition(list, c),
                                c.where(),
                                c.synonyms(),
                                c.note(),
                                c.pending(),
                                c.combine(),
                                sources.get(c.name()),
                                valueIssues(list, c, values),
                                CaliberReviews.rowStatus(review, c),
                                r == null ? null : r.reviewedBy(),
                                r == null ? null : r.verifiedBy(),
                                c.unit()));
            }
        List<CaliberSources.Conflict> conflicts =
                s.sources().conflicts().stream().filter(c -> c.list().equals(listPath)).toList();
        return new Table(
                model,
                listPath,
                list == null ? null : list.scenario().subject(),
                status,
                List.copyOf(docs.keySet()),
                changed,
                list == null ? null : list.viewName(),
                list == null ? null : list.cubeName(),
                compiled.errors(),
                rows,
                conflicts,
                list != null && CaliberReviews.scenarioReviewed(review, list),
                list != null
                        && compiled.ok()
                        && CaliberReviews.publishable(review, list, conflicts),
                publishedState(s, listPath),
                compiled.view(),
                compiled.cube(),
                s.ruleWarnings().getOrDefault(listPath, List.of()),
                list == null ? null : list.scenario(),
                list == null ? List.of() : scopeIssues(list, values),
                list == null ? List.of() : columns(s.root(), list, values));
    }

    /** Columns the list uses, in list order, with display names and counted values. */
    private static List<Column> columns(
            Path root, CaliberList list, Map<String, List<String>> values) {
        Map<String, String> labels = displayNames(root, list.model());
        boolean sampled = values != null && values.containsKey(CaliberValues.SAMPLE_MARKER);
        List<Column> out = new ArrayList<>();
        for (String column : referencedColumns(list)) {
            List<String> known =
                    values == null || sampled ? null : values.get(column.toLowerCase(Locale.ROOT));
            out.add(
                    new Column(
                            column,
                            labels.get(column.toLowerCase(Locale.ROOT)),
                            known == null ? null : List.copyOf(known)));
        }
        return out;
    }

    /** Lower-cased column name to the model's display name; empty when unreadable. */
    static Map<String, String> displayNames(Path root, String model) {
        Map<String, String> out = new TreeMap<>();
        if (model == null) return out;
        Path file = root.resolve("models").resolve(model).resolve("metadata.yml");
        if (!Files.isRegularFile(file)) return out;
        try {
            com.fasterxml.jackson.databind.JsonNode doc = EDIT_YAML.readTree(file.toFile());
            for (com.fasterxml.jackson.databind.JsonNode column : doc.path("columns")) {
                String name = column.path("name").asText("");
                String label = column.path("properties").path("displayName").asText("").strip();
                if (!name.isEmpty() && !label.isEmpty())
                    out.put(name.toLowerCase(Locale.ROOT), label);
            }
        } catch (IOException | RuntimeException e) {
            // Labels are a convenience; the form falls back to column names.
        }
        return out;
    }

    private static String publishedState(Snapshot s, String listPath) {
        String published = s.publishedLists().get(listPath);
        if (published == null) return "NONE";
        return published.equals(s.draftLists().get(listPath)) ? "CURRENT" : "OLDER";
    }

    // ------------------------------------------------------------------ review and verification

    /** One generated verification question for a caliber and how the draft expands it. */
    /**
     * {@code segment} is this caliber's part of the generated View: the SQL over the physical
     * model with every condition, which is what the reviewer must judge; {@code expanded} is the
     * Cube query over the View that question answering will run.
     */
    public record Check(
            String caliber,
            String question,
            String query,
            String segment,
            String expanded,
            String error) {}

    /**
     * A person confirms the scenario and/or rows as shown: each conclusion binds to the current
     * content, so a later edit of a row voids only that row. Conflicting rows cannot be passed.
     */
    public Table review(
            String userId,
            String groupId,
            String listPath,
            boolean scenario,
            List<String> calibers) {
        groups.getGroup(userId, groupId);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    // Re-check after waiting for the lock: access may have been revoked meanwhile.
                    groups.getGroup(userId, groupId);
                    Path root = workspace.workspaceRoot(groupId);
                    CaliberList list = requireList(root, listPath);
                    Set<String> conflicted = conflicted(root, listPath);
                    Map<String, CaliberReviews.ListReview> all = CaliberReviews.read(root);
                    CaliberReviews.ListReview old =
                            all.getOrDefault(listPath, CaliberReviews.ListReview.empty());
                    String at = java.time.Instant.now().toString();
                    Map<String, CaliberReviews.RowReview> rows = new TreeMap<>(old.rows());
                    for (String name : calibers == null ? List.<String>of() : calibers) {
                        CaliberList.Caliber c = requireCaliber(list, name);
                        if (conflicted.contains(name))
                            throw new DatasetException("口径「" + name + "」有待处理的冲突，先选择保留哪个版本再核查", 409);
                        CaliberReviews.RowReview r =
                                rows.getOrDefault(name, CaliberReviews.RowReview.empty());
                        rows.put(
                                name,
                                new CaliberReviews.RowReview(
                                        CaliberSources.hash(c),
                                        userId,
                                        at,
                                        r.verifiedHash(),
                                        r.verifiedBy(),
                                        r.verifiedAt(),
                                        r.problemHash()));
                    }
                    all.put(
                            listPath,
                            new CaliberReviews.ListReview(
                                    scenario
                                            ? CaliberReviews.scenarioHash(list)
                                            : old.scenarioHash(),
                                    scenario ? userId : old.scenarioReviewedBy(),
                                    scenario ? at : old.scenarioReviewedAt(),
                                    rows,
                                    old.lastRun()));
                    CaliberReviews.write(root, all);
                    return null;
                });
        return tableOf(userId, groupId, listPath);
    }

    /**
     * Generates one question per reviewed, not yet verified caliber (or the named ones) and
     * expands it on the draft with {@code cube query --sql-only} plus EXPLAIN; nothing is
     * executed (ADR0132). Only cases that expanded can be judged.
     */
    public List<Check> verify(
            String userId, String groupId, String listPath, List<String> calibers) {
        groups.getGroup(userId, groupId);
        record Case(
                String caliber, String hash, String question, String segment, List<String> args) {}
        List<Case> cases =
                workspace.withWorkspaceLock(
                        groupId,
                        () -> {
                            Path root = workspace.workspaceRoot(groupId);
                            CaliberList list = requireList(root, listPath);
                            CaliberReviews.ListReview review =
                                    CaliberReviews.read(root).get(listPath);
                            List<Case> out = new ArrayList<>();
                            for (CaliberList.Caliber c : list.calibers()) {
                                String status = CaliberReviews.rowStatus(review, c);
                                boolean wanted =
                                        calibers == null || calibers.isEmpty()
                                                ? !CaliberReviews.PASSED.equals(status)
                                                        && !CaliberReviews.REVIEW_PENDING.equals(
                                                                status)
                                                : calibers.contains(c.name());
                                if (!wanted) continue;
                                if (CaliberReviews.REVIEW_PENDING.equals(status))
                                    throw new DatasetException(
                                            "口径「" + c.name() + "」尚未核查，先核查再验证", 409);
                                out.add(
                                        new Case(
                                                c.name(),
                                                CaliberSources.hash(c),
                                                question(list, c),
                                                CaliberCompiler.segment(list, c),
                                                query(list, c)));
                            }
                            return out;
                        });
        if (cases.isEmpty()) return List.of();
        List<MdlPublishService.DraftPlan> plans =
                publisher.planDraftCubeQueries(groupId, cases.stream().map(Case::args).toList());
        List<Check> checks = new ArrayList<>();
        Map<String, String> ran = new TreeMap<>();
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            MdlPublishService.DraftPlan plan = plans.get(i);
            checks.add(
                    new Check(
                            c.caliber(),
                            c.question(),
                            plan.sql(),
                            c.segment(),
                            plan.expanded(),
                            plan.error()));
            if (plan.error() == null) ran.put(c.caliber(), c.hash());
        }
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    groups.getGroup(userId, groupId);
                    Path root = workspace.workspaceRoot(groupId);
                    Map<String, CaliberReviews.ListReview> all = CaliberReviews.read(root);
                    CaliberReviews.ListReview old =
                            all.getOrDefault(listPath, CaliberReviews.ListReview.empty());
                    all.put(
                            listPath,
                            new CaliberReviews.ListReview(
                                    old.scenarioHash(),
                                    old.scenarioReviewedBy(),
                                    old.scenarioReviewedAt(),
                                    old.rows(),
                                    ran));
                    CaliberReviews.write(root, all);
                    return null;
                });
        return checks;
    }

    /**
     * Records a person's verdict ({@code EXPECTED} or {@code PROBLEM}) for cases of the latest
     * run whose caliber has not changed since; anything else is refused.
     */
    public Table verdict(
            String userId, String groupId, String listPath, Map<String, String> verdicts) {
        groups.getGroup(userId, groupId);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    groups.getGroup(userId, groupId);
                    Path root = workspace.workspaceRoot(groupId);
                    CaliberList list = requireList(root, listPath);
                    Map<String, CaliberReviews.ListReview> all = CaliberReviews.read(root);
                    CaliberReviews.ListReview old = all.get(listPath);
                    if (old == null) throw new DatasetException("请先运行问题验证", 409);
                    String at = java.time.Instant.now().toString();
                    Map<String, CaliberReviews.RowReview> rows = new TreeMap<>(old.rows());
                    for (Map.Entry<String, String> v :
                            (verdicts == null ? Map.<String, String>of() : verdicts).entrySet()) {
                        CaliberList.Caliber c = requireCaliber(list, v.getKey());
                        String hash = CaliberSources.hash(c);
                        if (!hash.equals(old.lastRun().get(c.name())))
                            throw new DatasetException(
                                    "口径「" + c.name() + "」没有本次可判定的验证结果，或验证后已被修改，请重新验证", 409);
                        CaliberReviews.RowReview r =
                                rows.getOrDefault(c.name(), CaliberReviews.RowReview.empty());
                        boolean expected = "EXPECTED".equals(v.getValue());
                        if (!expected && !"PROBLEM".equals(v.getValue()))
                            throw new DatasetException("判定只能是 EXPECTED 或 PROBLEM", 400);
                        rows.put(
                                c.name(),
                                new CaliberReviews.RowReview(
                                        r.reviewedHash(),
                                        r.reviewedBy(),
                                        r.reviewedAt(),
                                        expected ? hash : null,
                                        expected ? userId : null,
                                        expected ? at : null,
                                        expected ? null : hash));
                    }
                    all.put(
                            listPath,
                            new CaliberReviews.ListReview(
                                    old.scenarioHash(),
                                    old.scenarioReviewedBy(),
                                    old.scenarioReviewedAt(),
                                    rows,
                                    old.lastRun()));
                    CaliberReviews.write(root, all);
                    return null;
                });
        return tableOf(userId, groupId, listPath);
    }

    /**
     * spec153 P1.5: a person edits one existing caliber from the review page. The whole list is
     * compiled before anything is written; the row is recorded as a manual edit (later document
     * drafts keep it and report a conflict instead), and its review and verification lapse because
     * its content hash changes. Conflicting rows are refused: the person chooses a version first.
     */
    public Table edit(String userId, String groupId, String listPath, String name, RowEdit edit) {
        groups.getGroup(userId, groupId);
        if (edit == null) throw new DatasetException("缺少修改内容", 400);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    // Re-check after waiting for the lock: access may have been revoked meanwhile.
                    groups.getGroup(userId, groupId);
                    Path root = workspace.workspaceRoot(groupId);
                    CaliberList list = requireList(root, listPath);
                    requireCaliber(list, name);
                    if (conflicted(root, listPath).contains(name))
                        throw new DatasetException("口径「" + name + "」有待处理的冲突，先选择保留哪个版本再修改", 409);
                    String before = CaliberCompiler.lists(root).get(listPath);
                    String after = edited(before, name, edit);
                    CaliberCompiler.Compiled compiled =
                            CaliberCompiler.compile(root, listPath, after);
                    if (!compiled.ok())
                        throw new DatasetException(
                                "未保存：" + String.join("；", compiled.errors()), 400);
                    String refused =
                            CaliberSources.apply(
                                    root,
                                    listPath,
                                    before,
                                    after,
                                    CaliberSources.EDIT,
                                    userId,
                                    BusinessDocumentService.documentsFor(root, list.model()));
                    if (refused != null) throw new DatasetException(refused, 409);
                    try {
                        Files.writeString(root.resolve(listPath), after, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new DatasetException("保存口径清单失败：" + e.getMessage(), e);
                    }
                    CaliberCompiler.sync(root);
                    DraftChangeLog.record(root, listPath, ModelingTurnPurpose.EDIT, userId);
                    return null;
                });
        groups.markMdlDirty(userId, groupId);
        return tableOf(userId, groupId, listPath);
    }

    /** The list YAML with one caliber's editable fields replaced; other content is kept. */
    static String edited(String yaml, String name, RowEdit edit) {
        com.fasterxml.jackson.databind.node.ObjectNode root;
        try {
            root = (com.fasterxml.jackson.databind.node.ObjectNode) EDIT_YAML.readTree(yaml);
        } catch (IOException | ClassCastException e) {
            throw new DatasetException("读取口径清单失败：" + e.getMessage(), 409);
        }
        com.fasterxml.jackson.databind.node.ObjectNode row = null;
        for (com.fasterxml.jackson.databind.JsonNode node : root.path("calibers"))
            if (node.isObject() && name.equals(node.path("name").asText(null)))
                row = (com.fasterxml.jackson.databind.node.ObjectNode) node;
        if (row == null) throw new DatasetException("口径不存在：" + name, 404);

        Map<String, List<String>> where = new LinkedHashMap<>();
        if (edit.where() != null)
            for (Map.Entry<String, List<String>> e : edit.where().entrySet()) {
                String column = e.getKey() == null ? "" : e.getKey().strip();
                List<String> values = texts(e.getValue(), "取值");
                if (column.isEmpty() || values.isEmpty())
                    throw new DatasetException("每个条件都要选择列并至少填一个取值", 400);
                if (values.size() > MAX_EDIT_VALUES)
                    throw new DatasetException("每个条件最多 " + MAX_EDIT_VALUES + " 个取值", 400);
                where.put(column, values);
            }
        if (where.isEmpty()) throw new DatasetException("口径至少要有一个取数条件", 400);
        com.fasterxml.jackson.databind.node.ObjectNode conditions = row.objectNode();
        for (Map.Entry<String, List<String>> e : where.entrySet()) {
            if (e.getValue().size() == 1) {
                conditions.put(e.getKey(), e.getValue().get(0));
            } else {
                com.fasterxml.jackson.databind.node.ArrayNode array = conditions.arrayNode();
                e.getValue().forEach(array::add);
                conditions.set(e.getKey(), array);
            }
        }
        row.set("where", conditions);
        List<String> synonyms = texts(edit.synonyms(), "说法");
        if (synonyms.isEmpty()) {
            row.remove("synonyms");
        } else {
            com.fasterxml.jackson.databind.node.ArrayNode array = row.arrayNode();
            synonyms.forEach(array::add);
            row.set("synonyms", array);
        }
        text(row, "group", edit.group());
        text(row, "unit", edit.unit());
        text(row, "note", edit.note());
        text(row, "pending", edit.pending());
        if (edit.combine()) row.put("combine", "sum");
        else row.remove("combine");
        try {
            return EDIT_YAML.writeValueAsString(root);
        } catch (IOException e) {
            throw new DatasetException("生成口径清单失败：" + e.getMessage(), e);
        }
    }

    private static void text(
            com.fasterxml.jackson.databind.node.ObjectNode row, String field, String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() > MAX_EDIT_TEXT)
            throw new DatasetException(field + " 不能超过 " + MAX_EDIT_TEXT + " 字", 400);
        if (v.isEmpty()) row.remove(field);
        else row.put(field, v);
    }

    private static List<String> texts(List<String> values, String what) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String v = value == null ? "" : value.strip();
            if (v.isEmpty()) continue;
            if (v.length() > 200) throw new DatasetException(what + "不能超过 200 字：" + v, 400);
            out.add(v);
        }
        return List.copyOf(out);
    }

    private Table tableOf(String userId, String groupId, String listPath) {
        return overview(userId, groupId).stream()
                .filter(t -> listPath.equals(t.listPath()))
                .findFirst()
                .orElseThrow(() -> new DatasetException("口径清单不存在：" + listPath, 404));
    }

    /** The question shown to the reviewer, in business words. */
    static String question(CaliberList list, CaliberList.Caliber c) {
        CaliberList.Measure m = checkedMeasure(list);
        String label = m.label() == null ? m.column() : m.label();
        String period =
                list.scenario().time() == null
                        ? ""
                        : "month".equals(list.scenario().time().grain()) ? "各月" : "各日";
        String scope =
                list.scenario().scope().stream()
                        .map(d -> CaliberCompiler.label(d) + d.defaultValue())
                        .collect(java.util.stream.Collectors.joining("、"));
        return c.name() + period + "的" + label + (scope.isEmpty() ? "" : "（" + scope + "）");
    }

    /** The default measure, else the first: what a question naming only the audience reads. */
    private static CaliberList.Measure checkedMeasure(CaliberList list) {
        CaliberList.Measure primary = list.scenario().defaultMeasure();
        return primary != null ? primary : list.scenario().measures().get(0);
    }

    /**
     * The Cube query for a caliber question: one caliber, the default measure, by period, with
     * every scope dimension at its default (spec149 P5), as the gate requires of a real query.
     */
    static List<String> query(CaliberList list, CaliberList.Caliber c) {
        List<String> args = new ArrayList<>();
        args.add("--cube");
        args.add(list.cubeName());
        args.add("--measures");
        args.add(checkedMeasure(list).name());
        for (String filter : CaliberCompiler.defaultFilters(list)) {
            args.add("--filter");
            args.add(filter);
        }
        args.add("--dimensions");
        args.add("caliber");
        if (list.scenario().time() != null) {
            args.add("--time-dimension");
            args.add("period:" + list.scenario().time().grain());
        }
        args.add("--filter");
        args.add("caliber:in:" + c.name());
        return args;
    }

    private static CaliberList requireList(Path root, String listPath) {
        if (listPath == null || !listPath.matches("calibers/[a-z0-9_-]{1,64}\\.yml"))
            throw new DatasetException("口径清单路径无效", 400);
        String yaml = CaliberCompiler.lists(root).get(listPath);
        if (yaml == null) throw new DatasetException("口径清单不存在：" + listPath, 404);
        CaliberCompiler.Compiled compiled = CaliberCompiler.compile(root, listPath, yaml);
        if (!compiled.ok())
            throw new DatasetException("口径清单有问题，先修正：" + String.join("；", compiled.errors()), 409);
        return compiled.list();
    }

    private static CaliberList.Caliber requireCaliber(CaliberList list, String name) {
        return list.calibers().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new DatasetException("口径不存在：" + name, 404));
    }

    private static Set<String> conflicted(Path root, String listPath) {
        Set<String> out = new LinkedHashSet<>();
        for (CaliberSources.Conflict c : CaliberSources.read(root).conflicts())
            if (c.list().equals(listPath)) out.add(c.caliber());
        return out;
    }

    /** Values written in a caliber (and the fixed scope) that the table is not known to contain. */
    static List<ValueIssue> valueIssues(
            CaliberList list, CaliberList.Caliber c, Map<String, List<String>> values) {
        if (values == null) return List.of();
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        list.scenario().fixedScope().forEach((k, v) -> conditions.put(k, List.of(v)));
        conditions.putAll(c.where());
        List<ValueIssue> out = new ArrayList<>();
        boolean sampled = values.containsKey(CaliberValues.SAMPLE_MARKER);
        for (Map.Entry<String, List<String>> e : conditions.entrySet()) {
            List<String> known = values.get(e.getKey().toLowerCase(Locale.ROOT));
            if (known == null) continue;
            for (String value : e.getValue()) {
                if (value.equals(list.unrestricted()) || known.contains(value)) continue;
                out.add(new ValueIssue(e.getKey(), value, sampled ? "sample" : "missing"));
            }
        }
        return out;
    }

    /** spec149 P5: scope defaults, totals and listed values the table is not known to contain. */
    static List<ValueIssue> scopeIssues(CaliberList list, Map<String, List<String>> values) {
        if (values == null) return List.of();
        boolean sampled = values.containsKey(CaliberValues.SAMPLE_MARKER);
        List<ValueIssue> out = new ArrayList<>();
        for (CaliberList.Scope d : list.scenario().scope()) {
            List<String> known = values.get(d.column().toLowerCase(Locale.ROOT));
            if (known == null) continue;
            Set<String> written = new LinkedHashSet<>();
            if (d.total() != null) written.add(d.total());
            if (d.defaultValue() != null) written.add(d.defaultValue());
            written.addAll(d.values());
            for (String value : written)
                if (!known.contains(value))
                    out.add(new ValueIssue(d.column(), value, sampled ? "sample" : "missing"));
        }
        return out;
    }

    // ------------------------------------------------------------------ values

    /** Cached column values of one dataset, invalidated when the dataset changes. */
    record CaliberValues(String stamp, Map<String, List<String>> columns) {
        static final String SAMPLE_MARKER = "__sample__";
    }

    private Map<String, Map<String, List<String>>> values(String groupId, Snapshot s) {
        Map<String, Map<String, List<String>>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (datasets == null) return out;
        Map<String, DatasetEntity> byId = new LinkedHashMap<>();
        for (DatasetEntity d : datasets.findByGroupId(groupId)) byId.put(d.getId(), d);
        Map<String, CaliberValues> updated = new LinkedHashMap<>();
        for (CaliberCompiler.Compiled compiled : s.compiled().values()) {
            CaliberList list = compiled.list();
            if (list == null) continue;
            DatasetEntity dataset = byId.get(s.datasetByModel().get(list.model()));
            if (dataset == null) continue;
            Set<String> columns = referencedColumns(list);
            // spec149 P7-4: external tables are read in full by the platform too, so a value check
            // says "not in the table" instead of "not in the sample".
            if (!"datasource".equals(dataset.getOrigin()) || datasetService != null) {
                // An external table changes outside the platform, so its values are re-read daily.
                String stamp =
                        dataset.getRowCount()
                                + "@"
                                + dataset.getUpdatedAt()
                                + ("datasource".equals(dataset.getOrigin())
                                        ? "@" + java.time.LocalDate.now()
                                        : "");
                CaliberValues cached = s.cachedValues().get(dataset.getId());
                Map<String, List<String>> known =
                        cached != null && stamp.equals(cached.stamp())
                                ? new TreeMap<>(cached.columns())
                                : new TreeMap<>();
                for (String column : columns) {
                    String key = column.toLowerCase(Locale.ROOT);
                    if (known.containsKey(key)) continue;
                    List<String> v =
                            "datasource".equals(dataset.getOrigin())
                                    ? datasetService.distinctValues(dataset, column, VALUE_LIMIT)
                                    : provisioner.distinctValues(
                                            dataset.getTableName(), column, VALUE_LIMIT);
                    // High-cardinality or unreadable columns are remembered as "not checked".
                    known.put(key, v);
                }
                updated.put(dataset.getId(), new CaliberValues(stamp, known));
                Map<String, List<String>> usable = new TreeMap<>();
                known.forEach(
                        (k, v) -> {
                            if (v != null) usable.put(k, v);
                        });
                out.put(list.model(), usable);
            } else {
                Map<String, List<String>> sampled = sampleValues(dataset);
                if (!sampled.isEmpty()) {
                    sampled.put(CaliberValues.SAMPLE_MARKER, List.of());
                    out.put(list.model(), sampled);
                }
            }
        }
        if (!updated.isEmpty())
            workspace.withWorkspaceLock(
                    groupId,
                    () -> {
                        Path root = workspace.workspaceRoot(groupId);
                        Map<String, CaliberValues> all = new TreeMap<>(readValues(root));
                        all.putAll(updated);
                        writeValues(root, all);
                        return null;
                    });
        return out;
    }

    /** External tables: only values seen in the platform's sample, never a new query (ADR0135). */
    private static Map<String, List<String>> sampleValues(DatasetEntity dataset) {
        Map<String, List<String>> out = new TreeMap<>();
        if (dataset.getSampleSummaryJson() == null) return out;
        try {
            DatasetSampling.Summary summary =
                    DatasetSampling.decode(dataset.getSampleSummaryJson());
            if (summary == null || summary.columns() == null) return out;
            for (DatasetSampling.ColumnSample c : summary.columns())
                if (!c.skipped() && c.values() != null && !c.valuesTruncated())
                    out.put(c.name().toLowerCase(Locale.ROOT), c.values());
        } catch (RuntimeException e) {
            // An unreadable sample only disables the check.
        }
        return out;
    }

    private static Set<String> referencedColumns(CaliberList list) {
        Set<String> columns = new LinkedHashSet<>(list.scenario().fixedScope().keySet());
        list.scenario().scope().forEach(d -> columns.add(d.column()));
        for (CaliberList.Caliber c : list.calibers()) columns.addAll(c.where().keySet());
        return columns;
    }

    private static Map<String, CaliberValues> readValues(Path root) {
        Path file = root.resolve(VALUES_FILE);
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            return JSON.readValue(
                    file.toFile(), new TypeReference<Map<String, CaliberValues>>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static void writeValues(Path root, Map<String, CaliberValues> values) {
        Path file = root.resolve(VALUES_FILE);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(values), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("保存口径取值缓存失败：" + e.getMessage(), e);
        }
    }
}
