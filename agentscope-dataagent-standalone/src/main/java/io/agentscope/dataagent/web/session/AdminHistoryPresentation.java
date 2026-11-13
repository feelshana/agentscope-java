package io.agentscope.dataagent.web.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.AnswerQueryMemory;
import io.agentscope.dataagent.web.persistence.jpa.ChartOptionRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionMessageRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository;
import io.agentscope.dataagent.web.session.SessionTurnParser.TurnEntry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

/** Projects saved visual references; never forwards raw tool arguments, SQL or workspace files. */
@Service
public class AdminHistoryPresentation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ARTIFACT =
            Pattern.compile("/api/artifacts/([0-9a-f-]{36})/content");
    private static final Pattern PYTHON_IMAGE =
            Pattern.compile(
                    "(?m)^### artifact:([^\\r"
                            + "\\n"
                            + "]+)\\r"
                            + "?\\n"
                            + "- type: (?:image|svg)\\r"
                            + "?\\n"
                            + "- size: \\d+\\r"
                            + "?\\n"
                            + "- path: (/api/artifacts/[0-9a-f-]{36}/content)");
    private final SessionRegistryRepository sessions;
    private final SessionMessageRepository messages;
    private final ChartOptionRepository charts;
    private final ObjectProvider<AnswerQueryMemory> feedback;

    public AdminHistoryPresentation(
            SessionRegistryRepository sessions,
            SessionMessageRepository messages,
            ChartOptionRepository charts,
            ObjectProvider<AnswerQueryMemory> feedback) {
        this.sessions = sessions;
        this.messages = messages;
        this.charts = charts;
        this.feedback = feedback;
    }

    public record Chart(String id, String title, String url) {}

    public record Image(String name, String reference, String url) {}

    public record Visuals(List<Chart> charts, List<Image> images) {}

    public record Votes(Map<String, String> values, boolean available) {}

    public Votes votes(String owner, String key, List<String> ids) {
        var store = feedback.getIfAvailable();
        if (store == null) return new Votes(Map.of(), true);
        try {
            return new Votes(store.votes(owner, key, ids), true);
        } catch (RuntimeException ignored) {
            return new Votes(Map.of(), false);
        }
    }

    public io.agentscope.dataagent.web.persistence.jpa.SessionRegistryEntity session(
            String owner, String key) {
        return sessions.findById(key)
                .filter(s -> owner.equals(s.getUserId()) && "main".equals(s.getKind()))
                .orElseThrow(AdminHistoryPresentation::missing);
    }

    public Visuals visuals(String owner, String key, List<PositionedTool> tools, String answer) {
        List<Chart> chartList = new ArrayList<>();
        List<Image> images = new ArrayList<>();
        String base = "/api/admin/conversation-history/" + encode(owner);
        String query = "?sessionKey=" + encode(key);
        for (var tool : tools) {
            var entry = tool.entry();
            if ("render_chart".equals(entry.toolName())) {
                JsonNode payload = chart(entry);
                if (payload != null)
                    chartList.add(
                            new Chart(
                                    payload.path("chartId").asText("inline-" + tool.position()),
                                    payload.path("title").asText("历史图表"),
                                    base + "/charts/" + tool.position() + query));
            }
            if ("run_python".equals(entry.toolName()) && entry.toolResult() != null) {
                var match = PYTHON_IMAGE.matcher(entry.toolResult().replace("\\n", "\n"));
                while (match.find())
                    addImage(images, match.group(1).strip(), match.group(2), base, query);
            }
        }
        if (answer != null) {
            var match =
                    Pattern.compile("!\\[[^\\]]*\\]\\((/api/artifacts/[0-9a-f-]{36}/content)\\)")
                            .matcher(answer);
            while (match.find()) addImage(images, "历史图片", match.group(1), base, query);
        }
        return new Visuals(List.copyOf(chartList), List.copyOf(images));
    }

    private static void addImage(
            List<Image> images, String name, String ref, String base, String query) {
        var match = ARTIFACT.matcher(ref);
        if (match.matches() && images.stream().noneMatch(i -> i.reference().equals(ref)))
            images.add(new Image(name, ref, base + "/artifacts/" + match.group(1) + query));
    }

    public record PositionedTool(int position, TurnEntry entry) {}

    public Map<String, Object> chartOption(String owner, String key, int position)
            throws Exception {
        session(owner, key);
        var row =
                messages.findBySessionKeyAndPosition(key, position)
                        .orElseThrow(AdminHistoryPresentation::missing);
        var entry = JSON.readValue(row.payload, TurnEntry.class);
        var payload = chart(entry);
        if (payload == null) throw missing();
        JsonNode option = payload.path("option");
        if (payload.hasNonNull("chartId")) {
            String saved =
                    charts.findByChartIdAndOwnerId(payload.path("chartId").asText(), owner)
                            .filter(c -> owner.equals(c.getOwnerId()))
                            .orElseThrow(AdminHistoryPresentation::missing)
                            .getOptionJson();
            option = JSON.readTree(saved);
        }
        if (!option.isObject() || option.isEmpty()) throw missing();
        return Map.of("option", JSON.convertValue(option, Map.class));
    }

    private static JsonNode chart(TurnEntry entry) {
        if (!"TOOL".equalsIgnoreCase(entry.role())
                || !"render_chart".equals(entry.toolName())
                || entry.toolResult() == null) return null;
        try {
            JsonNode node = JSON.readTree(entry.toolResult());
            for (int i = 0; i < 2 && node.isTextual(); i++) node = JSON.readTree(node.asText());
            return "echarts".equals(node.path("chart").asText())
                            && (node.hasNonNull("chartId") || node.path("option").isObject())
                    ? node
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String displayText(String text) {
        if (text == null) return null;
        // Preserve immutable artifact and chart references for the read-only renderer only.
        var references =
                Pattern.compile(
                                "(?:chart://[A-Za-z0-9_-]+|!\\[[^\\]]*\\]\\(/api/artifacts/[0-9a-f-]{36}/content\\))")
                        .matcher(text);
        List<String> values = new ArrayList<>();
        String protectedText =
                references.replaceAll(
                        m -> {
                            values.add(m.group());
                            return "HISTORYRESOURCE" + (values.size() - 1) + "END";
                        });
        String clean =
                io.agentscope.dataagent.web.api.AdminConversationController.publicText(
                        protectedText);
        for (int i = 0; i < values.size(); i++)
            clean = clean.replace("HISTORYRESOURCE" + i + "END", values.get(i));
        return clean;
    }

    private static String encode(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "该会话的图表或附件不存在或已不可用");
    }
}
