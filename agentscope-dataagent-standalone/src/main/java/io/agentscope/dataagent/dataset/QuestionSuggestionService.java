package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Drafts up to five business questions for the post-modeling question dialog (spec091). Only
 * question text is produced, never SQL; failures return an empty list so the dialog falls back to
 * manual input.
 */
@Service
public class QuestionSuggestionService {
    public static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_DOCUMENT_CHARS = 4000;
    private static final int MAX_COLUMNS_PER_MODEL = 40;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROMPT =
            """
            你是数据分析需求顾问。根据输入中的语义模型目录、业务文档摘要和事实表时间限制，\
            提出最多 3 个业务人员最常问、能用这些模型直接回答的分析问题。
            要求：
            1. 只使用目录中存在的模型和字段所表达的业务含义，问题用业务语言，不出现 SQL、表名或字段英文名。
            2. 涉及 time_limits 中事实表的问题必须写明具体日期或日期范围，范围不超过该表 max_window_days。\
            日期优先落在 sampled_time_ranges 给出的采样观察时间范围内（那是数据实际所在的时间段）；\
            该表没有采样范围时才参考 now 选择近期日期。其他问题可以不写日期。
            3. 问题要简单：一句话、不超过 25 个字，只问一个指标，最多按一个维度分组或取 Top N；\
            不做多指标对比、占比、同比环比、多条件组合或跨多表推理。三个问题分别是一个汇总数、一个排名、一个分组，\
            避免重复或只换说法。问题要有业务意义：优先围绕事实表的度量（如访问次数、金额），\
            不要只对维度表自身计数（如"各项目的项目数量"）。
            4. 不要与 existing_questions 重复。
            只输出 JSON：{"questions":["问题1","问题2"]}
            """;

    private final ObjectProvider<Model> models;
    private final MdlWorkspaceReader reader;
    private final MdlWorkspaceService workspace;
    private final FactQueryWindowPolicy windows;
    private final DatasetRepository datasets;
    private final Clock clock;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public QuestionSuggestionService(
            ObjectProvider<Model> models,
            MdlWorkspaceReader reader,
            MdlWorkspaceService workspace,
            FactQueryWindowPolicy windows,
            DatasetRepository datasets) {
        this(
                models,
                reader,
                workspace,
                windows,
                datasets,
                Clock.systemDefaultZone(),
                Duration.ofSeconds(30));
    }

    QuestionSuggestionService(
            ObjectProvider<Model> models,
            MdlWorkspaceReader reader,
            MdlWorkspaceService workspace,
            FactQueryWindowPolicy windows,
            DatasetRepository datasets,
            Clock clock,
            Duration timeout) {
        this.models = models;
        this.reader = reader;
        this.workspace = workspace;
        this.windows = windows;
        this.datasets = datasets;
        this.clock = clock;
        this.timeout = timeout;
    }

    /** Callers have already checked that the user may manage {@code groupId}. */
    public List<String> suggest(String groupId, List<String> existing) {
        try {
            Model model = models.getIfAvailable();
            if (model == null) return List.of();
            MdlWorkspaceReader.Snapshot snapshot = reader.read(groupId);
            if (snapshot.models().isEmpty()) return List.of();
            String raw =
                    model.stream(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.SYSTEM)
                                                    .textContent(PROMPT)
                                                    .build(),
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent(input(groupId, snapshot, existing))
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
            return parse(raw, existing);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(QuestionSuggestionService.class)
                    .warn("Question suggestion failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private String input(
            String groupId, MdlWorkspaceReader.Snapshot snapshot, List<String> existing)
            throws java.io.IOException {
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceModel m : snapshot.models()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("model", m.name());
            entry.put("description", m.description());
            entry.put(
                    "columns",
                    m.columns().stream()
                            .filter(c -> c.relationship() == null || c.relationship().isBlank())
                            .limit(MAX_COLUMNS_PER_MODEL)
                            .map(
                                    c ->
                                            c.name()
                                                    + (c.description() == null
                                                            ? ""
                                                            : "：" + c.description()))
                            .toList());
            catalog.add(entry);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("now", ZonedDateTime.now(clock).toLocalDate().toString());
        input.put("models", catalog);
        List<SqlWindowGate.Rule> limits = windows == null ? List.of() : windows.rules(groupId);
        input.put("time_limits", limits);
        input.put("sampled_time_ranges", sampledRanges(groupId, limits));
        input.put("business_document", businessDocument(groupId));
        input.put("existing_questions", existing);
        return JSON.writeValueAsString(input);
    }

    /**
     * Earliest and latest sampled value of each limited table's time columns, so external tables
     * (which may not be probed) still get question dates where data actually exists.
     */
    Map<String, Map<String, List<String>>> sampledRanges(
            String groupId, List<SqlWindowGate.Rule> limits) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        if (datasets == null || limits.isEmpty()) return out;
        for (var dataset : datasets.findByGroupId(groupId)) {
            for (SqlWindowGate.Rule rule : limits) {
                if (!rule.table().equals(dataset.getName())) continue;
                var summary = DatasetSampling.decode(dataset.getSampleSummaryJson());
                if (summary == null || summary.columns() == null) continue;
                for (DatasetSampling.ColumnSample column : summary.columns()) {
                    if (!rule.timeColumns().contains(column.name()) || column.values() == null)
                        continue;
                    List<String> values =
                            column.values().stream()
                                    .filter(v -> v != null && !v.isBlank())
                                    .sorted()
                                    .toList();
                    if (values.isEmpty()) continue;
                    out.computeIfAbsent(rule.table(), k -> new LinkedHashMap<>())
                            .put(
                                    column.name(),
                                    List.of(values.get(0), values.get(values.size() - 1)));
                }
            }
        }
        return out;
    }

    /** All draft business documents (spec092), concatenated up to the prompt budget. */
    private String businessDocument(String groupId) throws java.io.IOException {
        Path directory =
                workspace.workspaceRoot(groupId).resolve(BusinessDocumentService.DIRECTORY);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return "";
        StringBuilder text = new StringBuilder();
        try (var files = Files.list(directory)) {
            for (Path file :
                    files.filter(f -> f.getFileName().toString().endsWith(".md"))
                            .sorted()
                            .toList()) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                text.append("## ")
                        .append(file.getFileName())
                        .append('\n')
                        .append(Files.readString(file, StandardCharsets.UTF_8).trim())
                        .append("\n\n");
                if (text.length() >= MAX_DOCUMENT_CHARS) break;
            }
        }
        String all = text.toString().trim();
        return all.length() > MAX_DOCUMENT_CHARS ? all.substring(0, MAX_DOCUMENT_CHARS) : all;
    }

    static List<String> parse(String raw, List<String> existing) throws java.io.IOException {
        if (raw == null) return List.of();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return List.of();
        JsonNode questions = JSON.readTree(raw.substring(start, end + 1)).path("questions");
        Set<String> taken = new LinkedHashSet<>();
        existing.forEach(q -> taken.add(q.trim()));
        List<String> out = new ArrayList<>();
        for (JsonNode node : questions) {
            String q = node.asText("").trim();
            if (q.isEmpty() || q.length() > 1000 || !taken.add(q)) continue;
            out.add(q);
            if (out.size() == MAX_SUGGESTIONS) break;
        }
        return out;
    }
}
