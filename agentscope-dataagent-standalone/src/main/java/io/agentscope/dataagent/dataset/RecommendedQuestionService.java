package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import reactor.core.scheduler.Schedulers;

/**
 * spec103: one recommended question per knowledge base, prefilled on the query page to lead into
 * analysis. The platform fixes the dates — the real range of an uploaded fact table, the latest
 * month (or the shorter query window) of an external one — and asks "每天的<指标>" so the answer is
 * a daily series that offers the follow-up analyses. The model only picks the table and names the
 * metric, which may carry no other dimension. Without a dated fact table the original suggestion
 * generator is used. The stored question comes from the published model; the modeling trial shows
 * the same rule applied to the draft.
 */
@Service
public class RecommendedQuestionService {
    private static final Logger log = LoggerFactory.getLogger(RecommendedQuestionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML =
            new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
    static final int MONTH_DAYS = 30;
    private static final int MAX_METRIC_CHARS = 20;

    /** A grouping, ranking or comparison word: the question must be by day only. */
    static final Pattern OTHER_DIMENSION =
            Pattern.compile(
                    "各|按|每个|分组|分布|占比|排名|排行|对比|top|前\\s*\\d|最多|最少|最高|最低", Pattern.CASE_INSENSITIVE);

    private static final String PROMPT =
            """
            你是数据分析需求顾问。输入是一个知识库中带日期的事实表候选（table、描述、字段、date_phrase）。\
            选出业务人员最常关心的一张表，以及这张表上一个最有业务意义、可以按天统计的总量指标。
            要求：指标用业务语言命名，不超过 12 个字，不出现 SQL、表名或字段英文名，如“报表访问次数”“访问用户数”“订单金额”；\
            优先选事实表的核心度量（记录数、金额、去重人数），不要选维度属性。指标只是一个总量，不能带任何分组或筛选维度\
            （不要写“各部门”“按项目”“Top10”“某类”等）。只输出 JSON：{"table":"表名","metric":"指标名"}
            """;

    /** A dated fact table and the fixed date phrase its question must use. */
    record Candidate(String table, String role, String datePhrase, Map<String, Object> catalog) {}

    /** A table of either the published or the draft model, with its registered time columns. */
    record Table(
            String name,
            String datasetId,
            String description,
            List<String[]> columns,
            String role,
            JsonNode temporal) {}

    private final MdlCatalog catalog;
    private final DatasetRepository datasets;
    private final DatasetGroupRepository groups;
    private final FactQueryWindowPolicy windows;
    private final ObjectProvider<TableProvisioner> tables;
    private final ObjectProvider<Model> models;
    private final QuestionSuggestionService suggestions;
    private final MdlWorkspaceReader reader;
    private final MdlWorkspaceService workspace;
    private final Duration timeout;
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RecommendedQuestionService(
            MdlCatalog catalog,
            DatasetRepository datasets,
            DatasetGroupRepository groups,
            FactQueryWindowPolicy windows,
            ObjectProvider<TableProvisioner> tables,
            ObjectProvider<Model> models,
            QuestionSuggestionService suggestions,
            MdlWorkspaceReader reader,
            MdlWorkspaceService workspace) {
        this(
                catalog,
                datasets,
                groups,
                windows,
                tables,
                models,
                suggestions,
                reader,
                workspace,
                Duration.ofSeconds(30));
    }

    RecommendedQuestionService(
            MdlCatalog catalog,
            DatasetRepository datasets,
            DatasetGroupRepository groups,
            FactQueryWindowPolicy windows,
            ObjectProvider<TableProvisioner> tables,
            ObjectProvider<Model> models,
            QuestionSuggestionService suggestions,
            MdlWorkspaceReader reader,
            MdlWorkspaceService workspace,
            Duration timeout) {
        this.catalog = catalog;
        this.datasets = datasets;
        this.groups = groups;
        this.windows = windows;
        this.tables = tables;
        this.models = models;
        this.suggestions = suggestions;
        this.reader = reader;
        this.workspace = workspace;
        this.timeout = timeout;
    }

    /** Regenerates after every publish; never delays or fails the publish itself. */
    @TransactionalEventListener(fallbackExecution = true)
    public void published(PublishedQueryProjectChanged event) {
        var group = groups.findById(event.groupId()).orElse(null);
        // The modeler's own question stays; generated ones follow the newly published data.
        if (group != null && group.isRecommendedQuestionManual()) return;
        schedule(event.groupId());
    }

    /**
     * The stored question; callers have checked QUERY. A published knowledge base without one
     * (published before spec103) gets it generated in the background for the next visit.
     */
    public Optional<String> get(String groupId) {
        var group = groups.findById(groupId).orElse(null);
        if (group == null) return Optional.empty();
        String question = group.getRecommendedQuestion();
        if ((question == null || question.isBlank()) && group.getMdlVersion() > 0)
            schedule(groupId);
        return Optional.ofNullable(question).filter(q -> !q.isBlank());
    }

    /**
     * The same rule on the draft model, for the modeling trial (not stored); callers have checked
     * MANAGE. Before the first publish this is the only recommended question there is.
     */
    public Optional<String> draft(String groupId) {
        return generate(groupId, draftTables(groupId));
    }

    /** The knowledge base's question and whether the modeler wrote it. */
    public record Recommendation(String question, boolean manual) {}

    static final int MAX_QUESTION_CHARS = 200;

    /**
     * The modeling trial's view (callers have checked MANAGE): the stored question, or — before
     * there is one — a question generated from the draft and stored, so the trial and the query
     * page show the same text.
     */
    public Recommendation trial(String groupId) {
        var group = groups.findById(groupId).orElse(null);
        if (group == null) return new Recommendation(null, false);
        String stored = group.getRecommendedQuestion();
        if (stored != null && !stored.isBlank())
            return new Recommendation(stored, group.isRecommendedQuestionManual());
        Optional<String> generated = draft(groupId);
        generated.ifPresent(q -> groups.updateRecommendedQuestion(groupId, q));
        return new Recommendation(generated.orElse(null), false);
    }

    /** The modeler's own question; blank text hands the question back to generation. */
    public Recommendation edit(String groupId, String question) {
        String text = question == null ? "" : question.strip();
        if (text.isEmpty()) return regenerate(groupId);
        if (text.length() > MAX_QUESTION_CHARS || text.contains("\n"))
            throw new DatasetException("推荐问题需为一行，不超过 " + MAX_QUESTION_CHARS + " 字", 400);
        groups.saveRecommendedQuestion(groupId, text, true);
        return new Recommendation(text, true);
    }

    /** A new generated question from the draft; the modeler's edit is dropped. */
    public Recommendation regenerate(String groupId) {
        String generated = draft(groupId).orElse(null);
        groups.saveRecommendedQuestion(groupId, generated, false);
        return new Recommendation(generated, false);
    }

    void schedule(String groupId) {
        // A publish while generating asks for one more pass with the newer model.
        if (running.putIfAbsent(groupId, Boolean.FALSE) != null) {
            running.put(groupId, Boolean.TRUE);
            return;
        }
        Schedulers.boundedElastic()
                .schedule(
                        () -> {
                            try {
                                boolean again;
                                do {
                                    running.put(groupId, Boolean.FALSE);
                                    generate(groupId)
                                            .ifPresent(
                                                    q ->
                                                            groups.updateRecommendedQuestion(
                                                                    groupId, q));
                                    again = Boolean.TRUE.equals(running.get(groupId));
                                } while (again);
                            } catch (RuntimeException e) {
                                log.warn(
                                        "Recommended question for {} not generated: {}",
                                        groupId,
                                        e.getMessage());
                            } finally {
                                running.remove(groupId);
                            }
                        });
    }

    Optional<String> generate(String groupId) {
        return generate(groupId, publishedTables(groupId));
    }

    private Optional<String> generate(String groupId, List<Table> source) {
        List<Candidate> candidates = candidates(groupId, source);
        if (candidates.isEmpty()) {
            List<String> fallback = suggestions.suggest(groupId, List.of());
            return fallback.isEmpty() ? Optional.empty() : Optional.of(fallback.get(0));
        }
        Model model = models.getIfAvailable();
        if (model == null) return Optional.empty();
        try {
            String raw =
                    model.stream(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.SYSTEM)
                                                    .textContent(PROMPT)
                                                    .build(),
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent(
                                                            JSON.writeValueAsString(
                                                                    candidates.stream()
                                                                            .map(Candidate::catalog)
                                                                            .toList()))
                                                    .build()),
                                    List.of(),
                                    null)
                            .flatMapIterable(
                                    r -> r.getContent() == null ? List.of() : r.getContent())
                            .filter(TextBlock.class::isInstance)
                            .cast(TextBlock.class)
                            .map(TextBlock::getText)
                            .reduce("", String::concat)
                            .timeout(timeout)
                            .block();
            return compose(raw, candidates);
        } catch (Exception e) {
            log.warn("Recommended question model call failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * The question from the model's pick: the platform's date phrase plus "每天的<指标>". An
     * unknown table, or a metric that carries its own dates or another dimension, yields nothing.
     */
    static Optional<String> compose(String raw, List<Candidate> candidates) {
        if (raw == null) return Optional.empty();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();
        JsonNode pick;
        try {
            pick = JSON.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            return Optional.empty();
        }
        String table = pick.path("table").asText("").strip();
        String metric = pick.path("metric").asText("").strip();
        if (metric.isEmpty()
                || metric.length() > MAX_METRIC_CHARS
                || metric.matches(".*[\\n`;]+.*")
                || metric.matches(".*\\d{4}-\\d{2}.*")
                || OTHER_DIMENSION.matcher(metric).find()) return Optional.empty();
        return candidates.stream()
                .filter(c -> c.table().equals(table))
                .findFirst()
                .map(c -> question(c.datePhrase(), metric));
    }

    static String question(String datePhrase, String metric) {
        // "2026-09-20 至 2026-10-07 每天的…" / "最近一个月每天的…"
        return datePhrase
                + (Character.isDigit(datePhrase.charAt(datePhrase.length() - 1)) ? " " : "")
                + "每天的"
                + metric;
    }

    /** Dated fact tables of the published model, registered fact tables first. */
    List<Candidate> candidates(String groupId) {
        return candidates(groupId, publishedTables(groupId));
    }

    List<Candidate> candidates(String groupId, List<Table> source) {
        List<Candidate> facts = new ArrayList<>();
        List<Candidate> others = new ArrayList<>();
        for (Table t : source) {
            if (t.temporal() == null || !t.temporal().isArray() || t.temporal().isEmpty()) continue;
            String column = t.temporal().get(0).path("column").asText("");
            String format = t.temporal().get(0).path("format").asText("");
            String phrase = datePhrase(groupId, t, column, format);
            if (phrase == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", t.name());
            item.put("description", t.description());
            item.put("date_phrase", phrase);
            item.put(
                    "columns",
                    t.columns().stream()
                            .limit(40)
                            .map(c -> c[0] + (c[2] == null ? "" : "：" + c[2]))
                            .toList());
            ("FACT".equals(t.role()) ? facts : others)
                    .add(new Candidate(t.name(), t.role(), phrase, item));
        }
        facts.addAll(others);
        return facts;
    }

    private List<Table> publishedTables(String groupId) {
        var mdl = catalog.load(groupId).orElse(null);
        if (mdl == null) return List.of();
        Map<String, Object> context;
        try {
            context = catalog.dateGateContext(groupId);
        } catch (DatasetException e) {
            return List.of();
        }
        Map<String, MdlCatalog.Model> byName = new LinkedHashMap<>();
        mdl.models().forEach(m -> byName.put(m.name(), m));
        List<Table> out = new ArrayList<>();
        for (Object entry : (List<?>) context.get("models")) {
            if (!(entry instanceof Map<?, ?> info)) continue;
            MdlCatalog.Model m = byName.get(String.valueOf(info.get("name")));
            if (m == null) continue;
            out.add(
                    new Table(
                            m.name(),
                            m.datasetId(),
                            m.description(),
                            m.columns().stream()
                                    .map(c -> new String[] {c.name(), c.type(), c.description()})
                                    .toList(),
                            String.valueOf(info.get("role")),
                            info.get("temporalColumns") instanceof JsonNode n ? n : null));
        }
        return out;
    }

    /** Draft tables with the temporal columns and confirmed role from their metadata.yml. */
    private List<Table> draftTables(String groupId) {
        if (reader == null || workspace == null) return List.of();
        Path root = workspace.workspaceRoot(groupId).toAbsolutePath().normalize();
        List<Table> out = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceModel m : reader.read(groupId).models()) {
            if (m.refSql() != null) continue;
            JsonNode properties = null;
            if (m.path() != null) {
                Path file = root.resolve(m.path()).normalize();
                if (file.startsWith(root) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        properties = YAML.readTree(file.toFile()).path("properties");
                    } catch (Exception e) {
                        properties = null;
                    }
                }
            }
            String role =
                    properties != null && properties.path("table_role_confirmed").asBoolean(false)
                            ? properties.path("table_role").asText("UNKNOWN")
                            : "UNKNOWN";
            out.add(
                    new Table(
                            m.name(),
                            m.datasetId(),
                            m.description(),
                            m.columns().stream()
                                    .filter(
                                            c ->
                                                    c.relationship() == null
                                                            || c.relationship().isBlank())
                                    .map(c -> new String[] {c.name(), c.type(), c.description()})
                                    .toList(),
                            role,
                            properties == null ? null : properties.path("temporal_columns")));
        }
        return out;
    }

    /** The fixed date phrase of one table, or null when its dates cannot be determined. */
    private String datePhrase(String groupId, Table table, String column, String format) {
        if (column.isBlank() || column.contains("`")) return null;
        var dataset =
                table.datasetId() == null
                        ? null
                        : datasets.findById(table.datasetId()).orElse(null);
        if (dataset == null) return null;
        if ("datasource".equals(dataset.getOrigin())) {
            int days = MONTH_DAYS;
            for (SqlWindowGate.Rule rule :
                    windows == null ? List.<SqlWindowGate.Rule>of() : windows.rules(groupId))
                if (rule.table().equals(table.name()) && rule.maxWindowDays() > 0)
                    days = Math.min(days, rule.maxWindowDays());
            return externalPhrase(days);
        }
        TableProvisioner provisioner = tables.getIfAvailable();
        if (provisioner == null) return null;
        String type =
                table.columns().stream()
                        .filter(c -> c[0].equals(column))
                        .map(c -> c[1])
                        .findFirst()
                        .orElse(null);
        String expression = TemporalCondition.sortable("`" + column + "`", format, type, "mysql");
        String[] range = provisioner.dateRange(dataset.getTableName(), expression);
        if (range == null) return null;
        return range[0].equals(range[1]) ? range[0] : range[0] + " 至 " + range[1];
    }

    static String externalPhrase(int days) {
        return days >= MONTH_DAYS ? "最近一个月" : "最近 " + days + " 天";
    }
}
