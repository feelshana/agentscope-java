package io.agentscope.dataagent.web.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.ConversationScopeRegistry;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Coordinates native session cleanup with a persisted retry plan scoped to one dashboard. */
@Service
public class DashboardDeletionService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DashboardService dashboards;
    private final DataAgentBootstrap bootstrap;
    private final AgentCatalogService catalog;
    private final SessionHistoryService history;
    private final SessionDeletionService deletion;
    private final ConversationScopeRegistry scopes;

    public record Cleanup(SessionEntry entry, List<String> turnIds) {}

    public DashboardDeletionService(
            DashboardService dashboards,
            DataAgentBootstrap bootstrap,
            AgentCatalogService catalog,
            SessionHistoryService history,
            SessionDeletionService deletion,
            ConversationScopeRegistry scopes) {
        this.dashboards = dashboards;
        this.bootstrap = bootstrap;
        this.catalog = catalog;
        this.history = history;
        this.deletion = deletion;
        this.scopes = scopes;
    }

    public void delete(String user, String id) {
        dashboards.delete(user, id, () -> requireIdle(id), directory -> cleanup(id, directory));
    }

    private String conversation(SessionEntry entry) {
        String id = SessionHistoryService.segment(entry.gateKey(), "|g:");
        return id != null ? id : SessionHistoryService.segment(entry.gateKey(), "|t:");
    }

    boolean matches(SessionEntry entry, String id) {
        return entry.kind() == SessionKind.MAIN
                && id.equals(conversation(entry))
                && Objects.equals(
                        SessionHistoryService.segment(entry.gateKey(), "|x:agentId="),
                        catalog.peekGatewayAgentId(entry.userId(), DashboardService.AGENT_ID));
    }

    private List<SessionEntry> sessions(String id) {
        return bootstrap.gateway().sessionAgentManager().allSessions().stream()
                .filter(e -> matches(e, id))
                .toList();
    }

    public void requireIdle(String id) {
        if (sessions(id).stream()
                .anyMatch(e -> bootstrap.gateway().isSessionActive(e.sessionKey())))
            throw new DatasetException("看板仍在生成，请等待本轮结束后再操作", 409);
    }

    private void cleanup(String id, Path directory) {
        requireIdle(id);
        Path journal = directory.resolve("deletion-plan.json");
        try {
            List<Cleanup> plan =
                    Files.exists(journal)
                            ? new ArrayList<>(
                                    List.of(
                                            JSON.readValue(
                                                    Files.readString(journal), Cleanup[].class)))
                            : new ArrayList<>();
            for (SessionEntry entry : sessions(id)) {
                if (plan.stream()
                        .anyMatch(item -> item.entry().sessionKey().equals(entry.sessionKey())))
                    continue;
                history.refresh(entry.sessionKey());
                plan.add(
                        new Cleanup(
                                entry,
                                history.all(entry.sessionKey()).stream()
                                        .filter(t -> "USER".equalsIgnoreCase(t.role()))
                                        .map(t -> t.id())
                                        .filter(Objects::nonNull)
                                        .toList()));
            }
            Path temp = directory.resolve("deletion-plan.tmp");
            Files.writeString(temp, JSON.writeValueAsString(plan));
            Files.move(
                    temp,
                    journal,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            for (Cleanup item : plan) {
                if (!matches(item.entry(), id)) throw new DatasetException("删除清单范围无效", 409);
                deletion.delete(
                        item.entry(),
                        () -> {
                            boolean sharedConversation =
                                    bootstrap.gateway().sessionAgentManager().allSessions().stream()
                                            .anyMatch(
                                                    other ->
                                                            Objects.equals(
                                                                            other.userId(),
                                                                            item.entry().userId())
                                                                    && id.equals(
                                                                            conversation(other))
                                                                    && !matches(other, id));
                            scopes.remove(
                                    item.entry().userId(), id, item.turnIds(), !sharedConversation);
                        });
            }
        } catch (SessionDeletionService.SessionBusyException e) {
            throw new DatasetException("看板仍在生成，请等待本轮结束后重试删除", 409);
        } catch (IOException e) {
            throw new DatasetException("相关文件清理未完成，请重试删除", e);
        }
    }
}
