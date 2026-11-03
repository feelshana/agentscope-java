package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Example requests on the semantic asset page, written from the knowledge base's own draft model
 * so a developer sees what to ask for in terms of their tables (spec111). The model drafts them;
 * the platform keeps only examples that name real models, follow a declared relationship, or read
 * the knowledge base's own physical tables. Results are cached until the draft model changes.
 */
@Service
public class AssetExampleService {
    private static final Logger log = LoggerFactory.getLogger(AssetExampleService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    static final int MAX_TEXT_CHARS = 400;
    private static final int MAX_MODELS = 15;
    private static final int MAX_COLUMNS = 40;
    private static final int MAX_DESCRIPTION_CHARS = 160;
    static final String SQL_LEAD = "下面是我们常用的统计 SQL，请整理成以后可以直接查询的结果：\n";
    private static final Pattern TABLE_REF =
            Pattern.compile("(?i)\\b(?:from|join)\\s+([`\\w.\\p{L}]+)");
    private static final Pattern CTE_NAME =
            Pattern.compile("(?i)(?:\\bwith|,)\\s*`?([\\w\\p{L}]+)`?\\s+as\\s*\\(");

    private static final String PROMPT =
            """
            你为数据开发写"语义资产"的需求示例，帮助他理解可以向助手提什么需求。输入是当前知识库的模型（名称、物理表、描述、字段及描述）和模型之间已有的关系。
            只能使用输入里出现的模型、字段含义、取值和关系，不编造表、字段、取值或业务口径；用业务语言写，模型名用「」括起来，不出现 View、ref_sql、视图等技术名词。
            只输出 JSON：{"join":"…","sql":"…"}
            - join：一句话。把一条已有关系两端的两个模型固定拼在一起，说明以后按哪些属性分析时直接用。没有关系时输出空字符串。
            - sql：一条只读 SELECT，只用输入里的物理表名（库名.表名），可以关联、分组、用窗口函数，体现一个有业务意义的统计；不写日期函数，不写说明文字。没有合适内容时输出空字符串。
            join 不超过 80 字。默认排除或只保留部分记录的需求写成业务规则即可，不作为示例（ADR0131）。
            existing_assets 是已经建好的加工表和查询模板：示例要提出它们没有覆盖的新需求，不要重复它们已有的拼接。
            """;

    /** One example request; {@code kind} is the business name shown on its button. */
    public record Example(String kind, String text) {}

    private record Cached(String fingerprint, List<Example> examples) {}

    private final DatasetGroupService groups;
    private final MdlWorkspaceReader reader;
    private final ObjectProvider<Model> models;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AssetExampleService(
            DatasetGroupService groups, MdlWorkspaceReader reader, ObjectProvider<Model> models) {
        this.groups = groups;
        this.reader = reader;
        this.models = models;
    }

    /** Examples for the draft model; requires MANAGE. Empty when there is nothing to build on. */
    public List<Example> examples(String actor, String groupId) {
        groups.getGroup(actor, groupId);
        MdlWorkspaceReader.Snapshot snapshot = reader.read(groupId);
        List<MdlWorkspaceReader.WorkspaceModel> physical =
                snapshot.models().stream()
                        .filter(m -> m.tableName() != null && !m.tableName().isBlank())
                        .limit(MAX_MODELS)
                        .toList();
        if (physical.isEmpty()) return List.of();
        String input;
        try {
            input = JSON.writeValueAsString(input(physical, snapshot));
        } catch (Exception e) {
            return List.of();
        }
        Cached cached = cache.get(groupId);
        if (cached != null && cached.fingerprint().equals(input)) return cached.examples();
        Model model =
                io.agentscope.dataagent.runtime.model.SwitchingModel.forCall(
                        models.getIfAvailable(), RuntimeContext.builder().userId(actor).build());
        String raw = null;
        if (model != null) {
            try {
                raw = call(model, input);
            } catch (Exception e) {
                log.warn("Asset examples model call failed: {}", e.getClass().getSimpleName());
            }
        }
        List<Example> examples = accept(raw, physical, snapshot.relations());
        // Only model-written results are cached; a failed call is retried on the next visit.
        if (raw != null) cache.put(groupId, new Cached(input, examples));
        return examples;
    }

    private static Map<String, Object> input(
            List<MdlWorkspaceReader.WorkspaceModel> physical,
            MdlWorkspaceReader.Snapshot snapshot) {
        List<MdlWorkspaceReader.WorkspaceRelation> relations = snapshot.relations();
        List<Map<String, Object>> modelInput = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceModel m : physical) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", m.name());
            item.put("physical_table", physicalName(m));
            item.put("description", clip(m.description()));
            item.put(
                    "columns",
                    m.columns().stream()
                            .filter(c -> !c.calculated() && c.relationship() == null)
                            .limit(MAX_COLUMNS)
                            .map(c -> c.name() + "：" + clip(c.description()))
                            .toList());
            modelInput.add(item);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("models", modelInput);
        input.put(
                "relationships",
                relations.stream()
                        .map(r -> r.leftModel() + " - " + r.rightModel() + "：" + r.condition())
                        .toList());
        // Built assets, so the examples suggest something the knowledge base does not have yet.
        List<String> existing = new ArrayList<>();
        snapshot.models().stream()
                .filter(m -> m.tableName() == null || m.tableName().isBlank())
                .forEach(m -> existing.add("加工表「" + m.name() + "」：" + clip(m.description())));
        snapshot.views()
                .forEach(v -> existing.add("查询模板「" + v.name() + "」：" + clip(v.description())));
        input.put("existing_assets", existing);
        return input;
    }

    /**
     * Keeps the examples that stay inside the knowledge base: a join naming both ends of a
     * declared relationship, and SQL that reads only its physical tables. Without a usable join
     * from the model, the first relationship gives a plain one. Default filters are rules, not
     * assets (ADR0131), so they are never offered as examples.
     */
    static List<Example> accept(
            String raw,
            List<MdlWorkspaceReader.WorkspaceModel> physical,
            List<MdlWorkspaceReader.WorkspaceRelation> relations) {
        JsonNode node = parse(raw);
        List<Example> out = new ArrayList<>();
        String join = text(node, "join");
        if (!join.isEmpty()
                && relations.stream()
                        .anyMatch(
                                r -> join.contains(r.leftModel()) && join.contains(r.rightModel())))
            out.add(new Example("查询模板", join));
        else if (!relations.isEmpty()) {
            var r = relations.get(0);
            out.add(
                    new Example(
                            "查询模板",
                            "把「"
                                    + r.leftModel()
                                    + "」和「"
                                    + r.rightModel()
                                    + "」按已有关系拼在一起，以后按「"
                                    + r.rightModel()
                                    + "」的属性分析「"
                                    + r.leftModel()
                                    + "」时直接用。"));
        }
        String sql = AssetSqlGenerator.clean(text(node, "sql"));
        if (readsOnly(sql, physical)) out.add(new Example("已有 SQL", SQL_LEAD + sql));
        return out;
    }

    /** A SELECT whose every FROM/JOIN target is one of the knowledge base's tables or its CTEs. */
    static boolean readsOnly(String sql, List<MdlWorkspaceReader.WorkspaceModel> physical) {
        if (sql.isEmpty() || !sql.toLowerCase(Locale.ROOT).matches("(?s)^\\s*(select|with)\\b.*"))
            return false;
        Set<String> known = new HashSet<>();
        for (var m : physical) {
            known.add(physicalName(m).toLowerCase(Locale.ROOT));
            known.add(m.tableName().toLowerCase(Locale.ROOT));
        }
        Matcher cte = CTE_NAME.matcher(sql);
        while (cte.find()) known.add(cte.group(1).toLowerCase(Locale.ROOT));
        Matcher table = TABLE_REF.matcher(sql);
        boolean any = false;
        while (table.find()) {
            String name = table.group(1).replace("`", "").toLowerCase(Locale.ROOT);
            if (!known.contains(name)) return false;
            any = true;
        }
        return any;
    }

    private static String physicalName(MdlWorkspaceReader.WorkspaceModel m) {
        return m.schemaName() == null || m.schemaName().isBlank()
                ? m.tableName()
                : m.schemaName() + "." + m.tableName();
    }

    private static JsonNode parse(String raw) {
        if (raw == null) return JSON.createObjectNode();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return JSON.createObjectNode();
        try {
            return JSON.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").strip();
        return value.length() > MAX_TEXT_CHARS ? "" : value;
    }

    private static String clip(String text) {
        if (text == null) return "";
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() > MAX_DESCRIPTION_CHARS
                ? flat.substring(0, MAX_DESCRIPTION_CHARS)
                : flat;
    }

    private static String call(Model model, String input) {
        return model.stream(
                        List.of(
                                Msg.builder().role(MsgRole.SYSTEM).textContent(PROMPT).build(),
                                Msg.builder().role(MsgRole.USER).textContent(input).build()),
                        List.of(),
                        null)
                .flatMapIterable(r -> r.getContent() == null ? List.of() : r.getContent())
                .filter(TextBlock.class::isInstance)
                .cast(TextBlock.class)
                .map(TextBlock::getText)
                .reduce("", String::concat)
                .timeout(TIMEOUT)
                .block();
    }
}
