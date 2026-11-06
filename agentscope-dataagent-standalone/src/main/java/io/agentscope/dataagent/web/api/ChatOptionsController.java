package io.agentscope.dataagent.web.api;

import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.KnowledgeBaseAccessService;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.runtime.session.WrenQueryToolRegistrar;
import io.agentscope.dataagent.tools.data.DataToolkitRegistrar;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class ChatOptionsController {
    private final AgentAccessGuard guard;
    private final KnowledgeBaseAccessService access;
    private final DatasetGroupService groups;
    private final MdlCatalog catalog;
    private final DataToolkitRegistrar tools;

    public ChatOptionsController(
            AgentAccessGuard guard,
            KnowledgeBaseAccessService access,
            DatasetGroupService groups,
            MdlCatalog catalog,
            DataToolkitRegistrar tools) {
        this.guard = guard;
        this.access = access;
        this.groups = groups;
        this.catalog = catalog;
        this.tools = tools;
    }

    public record DefaultGroup(String id, String name) {}

    public record Options(boolean webSearchAvailable, DefaultGroup defaultGroup) {}

    @GetMapping("/api/agents/" + WrenQueryToolRegistrar.QUERY_AGENT_ID + "/chat/options")
    public Mono<Options> options(
            Authentication auth, @RequestParam(defaultValue = "false") boolean newConversation) {
        return Mono.fromCallable(
                        () -> {
                            String userId = (String) auth.getPrincipal();
                            guard.require(userId, WrenQueryToolRegistrar.QUERY_AGENT_ID, Tier.RUN);
                            return new Options(
                                    tools.isWebSearchAvailable(),
                                    newConversation ? defaultGroup(userId) : null);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private DefaultGroup defaultGroup(String userId) {
        for (int page = 0; ; page++) {
            var candidates = access.publishedPage(userId, page);
            for (var group : candidates) {
                if (!groups.deletionPending(group.getId())
                        && catalog.load(group.getId()).isPresent()) {
                    return new DefaultGroup(group.getId(), group.getName());
                }
            }
            if (!candidates.hasNext()) return null;
        }
    }
}
