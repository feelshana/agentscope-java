package io.agentscope.dataagent.web.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.runtime.session.ConversationScopeRegistry;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Small metadata projection for the existing history index; never reads generated HTML. */
@Service
public class ConversationHistoryTitles {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DatasetGroupRepository groups;
    private final ConversationScopeRegistry scopes;
    private final WrenProperties wren;

    public ConversationHistoryTitles(
            DatasetGroupRepository groups, ConversationScopeRegistry scopes, WrenProperties wren) {
        this.groups = groups;
        this.scopes = scopes;
        this.wren = wren;
    }

    public String title(SessionEntry entry) {
        var type = HistoryAssistant.resolve(entry.gateKey(), entry.agentId());
        String conversation = SessionHistoryService.segment(entry.gateKey(), "|g:");
        if (conversation == null)
            conversation = SessionHistoryService.segment(entry.gateKey(), "|t:");
        if (type == HistoryAssistant.MODELING) {
            var selection =
                    conversation == null ? null : scopes.getSelection(entry.userId(), conversation);
            String group =
                    conversation != null && conversation.startsWith("modeling-")
                            ? conversation.substring("modeling-".length())
                            : null;
            if (group == null && conversation != null) {
                var selected = selection == null ? null : selection.groupIds();
                if (selected != null && selected.size() == 1) group = selected.get(0);
            }
            if (group != null && selection != null) {
                String historicalName = selection.groupNames().get(group);
                if (historicalName != null && !historicalName.isBlank())
                    return clean(historicalName) + " · 建模对话";
            }
            return group == null
                    ? "未关联知识库 · 建模对话"
                    : groups.findById(group)
                            .map(
                                    g ->
                                            entry.userId() != null
                                                            && entry.userId().equals(g.getOwnerId())
                                                    ? clean(g.getName()) + " · 建模对话"
                                                    : "知识库名称未记录 · 建模对话")
                            .orElse("知识库已删除 · 建模对话");
        }
        if (type == HistoryAssistant.DASHBOARD) {
            String name = dashboardName(conversation);
            return name == null ? "看板记录不可用 · 看板对话" : name + " · 看板对话";
        }
        return null;
    }

    private String dashboardName(String id) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) return null;
            Path file = wren.mdlRoot().resolve("dashboards").resolve(id).resolve("asset.json");
            for (Path current = file; current != null; current = current.getParent()) {
                if (Files.isSymbolicLink(current)) return null;
            }
            if (!Files.isRegularFile(file) || Files.size(file) > 16384) return null;
            var asset = JSON.readTree(Files.readString(file));
            if (!id.equals(asset.path("id").asText())) return null;
            String title = asset.path("title").asText();
            return title.isBlank() ? null : clean(title);
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static String clean(String title) {
        String text = title == null ? "未命名" : title.replaceAll("\\s+", " ").strip();
        return text.length() > 180 ? text.substring(0, 180) + "…" : text;
    }
}
