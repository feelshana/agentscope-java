/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.web.audit.ActivityEvent;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.dataagent.web.identity.IdentityLinkStore;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import io.agentscope.dataagent.web.toolbus.ToolEventBus;
import io.agentscope.dataagent.web.usage.UsageStore;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Chat endpoints, scoped to a specific agent.
 *
 * <ul>
 *   <li>{@code POST /api/agents/{agentId}/chat/stream} — SSE stream of {@code token | tool_call |
 *       tool_result | done | error} events.
 *   <li>{@code POST /api/agents/{agentId}/chat/send} — synchronous reply (no streaming).
 * </ul>
 *
 * <p>Each user gets an isolated session per {@code (userId, agentId)} pair (the agent id is part of
 * the {@link MsgContext#canonicalKey()}). Slash commands {@code /new}, {@code /reset},
 * {@code /identity}, and {@code /dock_<channel> <id>} are intercepted before the agent is invoked.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String NO_MODEL_MESSAGE =
            "未配置模型 API key：请设置环境变量 DASHSCOPE_API_KEY（或 DATAAGENT_OPENAI_API_KEY /"
                    + " dataagent.openai.api-key）后重启应用，再发起问数";

    private final ChatUiChannel chatUiChannel;
    private final SessionAgentManager sessionAgentManager;
    private final AgentCatalogService catalogService;
    private final IdentityLinkStore identityLinks;
    private final UsageStore usageStore;
    private final ToolEventBus toolEventBus;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;
    private final ObjectProvider<Model> modelProvider;
    private final ConversationScopeRegistry conversationScopes;

    /**
     * AgentStateStore keys for which we have already recorded a RUN_SESSION event. Each (userId, agentId)
     * pair gets one entry per process lifetime so the activity log shows one row per session, not
     * one per turn.
     */
    private final Set<String> startedSessions = ConcurrentHashMap.newKeySet();

    public ChatController(
            ChatUiChannel chatUiChannel,
            DataAgentBootstrap builderBootstrap,
            AgentCatalogService catalogService,
            IdentityLinkStore identityLinks,
            UsageStore usageStore,
            ToolEventBus toolEventBus,
            AgentAccessGuard guard,
            AgentActivityStore activity,
            ObjectProvider<Model> modelProvider,
            ConversationScopeRegistry conversationScopes) {
        this.chatUiChannel = chatUiChannel;
        this.sessionAgentManager = builderBootstrap.gateway().sessionAgentManager();
        this.catalogService = catalogService;
        this.identityLinks = identityLinks;
        this.usageStore = usageStore;
        this.toolEventBus = toolEventBus;
        this.guard = guard;
        this.activity = activity;
        this.modelProvider = modelProvider;
        this.conversationScopes = conversationScopes;
    }

    /**
     * Request body for both endpoints.
     *
     * <p>{@code sessionKey} is the caller-supplied conversation id used to address one of multiple
     * ChatGPT-style sessions for the same (userId, agentId) pair. {@code null}/blank requests collapse
     * to the legacy single-session behaviour by deferring to the gateway's deterministic key.
     */
    public record ChatRequest(String message, String sessionKey, java.util.List<String> groupIds) {}

    /** Response for the synchronous endpoint. */
    public record ChatResponse(String reply, String sessionKey) {}

    /**
     * Response for {@link #currentSession}. {@code exists} is {@code true} when a session entry has
     * already been created (i.e. the user has sent at least one message); the frontend uses this to
     * decide whether to fetch turns on mount.
     */
    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    /**
     * SSE streaming endpoint. Emits, in order:
     *
     * <ul>
     *   <li>{@code tool_call} — for each tool the agent invokes (real time, via {@link
     *       ToolEventBus})
     *   <li>{@code tool_result} — when the tool finishes executing
     *   <li>{@code token} — text deltas streamed incrementally from the model (one chunk per
     *       {@link TextBlockDeltaEvent})
     *   <li>{@code done} — end of run, carrying the resolved {@code sessionKey}
     *   <li>{@code error} — terminates the run on failure
     * </ul>
     *
     * <p>The frontend consumes these via {@code chat.ts}, parsing the {@code data:} payload as JSON
     * with shape {@code { type, data?, toolName?, toolInput?, toolResult?, error?, sessionKey? }}.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        if (modelProvider.getIfAvailable() == null) {
            return Flux.just(sse("error", Map.of("type", "error", "error", NO_MODEL_MESSAGE)));
        }
        // If the caller did not pin a conversation, mint one server-side so the gateKey stays
        // stable across turns and the FE can persist the URL session.
        String conversationId = normalizedConversationId(req.sessionKey());
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        final String resolvedConversationId = conversationId;
        recordRunSession(def, agentId, userId, resolvedConversationId);

        // Slash commands short-circuit the agent and produce a synthetic single-token reply.
        CommandResult cmd =
                handleSlashCommand(userId, agentId, req.message(), resolvedConversationId);
        if (cmd != null) {
            Map<String, Object> doneFrame = new LinkedHashMap<>();
            doneFrame.put("type", "done");
            // /new mints a new conversation; everything else keeps the caller's current one.
            doneFrame.put(
                    "sessionKey",
                    cmd.newSessionKey != null ? cmd.newSessionKey : resolvedConversationId);
            return Flux.just(
                    sse("token", Map.of("type", "token", "data", cmd.message)),
                    sse("done", doneFrame));
        }

        // Subscribe to tool events. The middleware publishes with the *runtime* session id,
        // which only exists once the gateway has dispatched this turn — so match lazily per
        // event against the conversation's gate key. Subscribing once up-front (by the
        // pre-resolved session key) would silently drop every event on the first turn, and
        // the runtime id differs from the storage session key anyway.
        String gateKey = resolveGateKey(userId, agentId, resolvedConversationId);
        Sinks.One<Boolean> done = Sinks.one();
        Flux<ServerSentEvent<String>> toolEvents =
                toolEventBus
                        .events()
                        .filter(e -> isEventForConversation(userId, gateKey, e))
                        .takeUntilOther(done.asMono().timeout(Duration.ofMinutes(10)))
                        .map(this::toToolFrame)
                        .onErrorResume(ex -> Flux.empty());

        Map<String, Object> doneFrame = new LinkedHashMap<>();
        doneFrame.put("type", "done");
        // Always echo the conversationId, NOT the storage key — otherwise the FE would persist
        // the storage key and use it as the next turn's conversationId, splintering the session.
        doneFrame.put("sessionKey", resolvedConversationId);

        Flux<ServerSentEvent<String>> agentEvents =
                executeChatStream(
                                userId,
                                agentId,
                                req.message(),
                                resolvedConversationId,
                                req.groupIds())
                        .filter(event -> event instanceof TextBlockDeltaEvent)
                        .map(
                                event ->
                                        sse(
                                                "token",
                                                Map.of(
                                                        "type",
                                                        "token",
                                                        "data",
                                                        ((TextBlockDeltaEvent) event).getDelta())))
                        .concatWith(Flux.just(sse("done", doneFrame)))
                        .doOnComplete(() -> done.tryEmitValue(true))
                        .onErrorResume(
                                ex -> {
                                    log.warn(
                                            "Chat stream error: userId={}, agentId={}, error={}",
                                            userId,
                                            agentId,
                                            ex.getMessage());
                                    done.tryEmitValue(false);
                                    return Flux.just(
                                            sse(
                                                    "error",
                                                    Map.of(
                                                            "type",
                                                            "error",
                                                            "error",
                                                            ex.getMessage())));
                                });

        return Flux.merge(toolEvents, agentEvents);
    }

    /**
     * Reports whether a session is already registered for the (userId, agentId, conversationId)
     * tuple. The returned {@code sessionKey} field is the caller's own {@code conversationId} (or
     * {@code null} when none was supplied) — never the internal storage key. {@code exists} is
     * {@code true} when the harness has registered a session for this tuple; the FE uses this to
     * decide whether to fetch turns on mount.
     */
    @GetMapping("/session")
    public Mono<CurrentSessionResponse> currentSession(
            @PathVariable String agentId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    String sessionKey,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        String conversationId = normalizedConversationId(sessionKey);
        return Mono.fromCallable(
                () -> {
                    if (conversationId == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    String gateKey = resolveGateKey(userId, agentId, conversationId);
                    boolean exists =
                            gateKey != null && findSessionKeyByGate(userId, gateKey) != null;
                    return new CurrentSessionResponse(conversationId, exists);
                });
    }

    /** Synchronous (non-streaming) chat. Blocks until the agent produces a reply. */
    @PostMapping("/send")
    public Mono<ChatResponse> send(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        if (modelProvider.getIfAvailable() == null) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NO_MODEL_MESSAGE));
        }
        String conversationId = normalizedConversationId(req.sessionKey());
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }
        final String resolvedConversationId = conversationId;
        recordRunSession(def, agentId, userId, resolvedConversationId);
        CommandResult cmd =
                handleSlashCommand(userId, agentId, req.message(), resolvedConversationId);
        if (cmd != null) {
            return Mono.just(
                    new ChatResponse(
                            cmd.message,
                            cmd.newSessionKey != null
                                    ? cmd.newSessionKey
                                    : resolvedConversationId));
        }
        return executeChat(userId, agentId, req.message(), resolvedConversationId, req.groupIds())
                .map(
                        reply -> {
                            String text =
                                    reply.getTextContent() != null ? reply.getTextContent() : "";
                            // Echo the conversationId, not the storage key — see stream().
                            return new ChatResponse(text, resolvedConversationId);
                        });
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private ServerSentEvent<String> toToolFrame(ToolEventBus.ToolEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean isResult = "TOOL_RESULT".equalsIgnoreCase(e.eventType());
        data.put("type", isResult ? "tool_result" : "tool_call");
        data.put("toolName", e.toolName());
        if (e.data() != null) {
            if (isResult && isPlainResult(e.data())) {
                // Unwrap the single "result" key so toolResult carries plain text — the same
                // shape SessionTurnParser restores from session history, so the frontend needs
                // no special-casing for live vs. restored tool blocks.
                data.put("toolResult", e.data().get("result"));
            } else {
                String payload;
                try {
                    payload = MAPPER.writeValueAsString(e.data());
                } catch (JsonProcessingException ex) {
                    payload = String.valueOf(e.data());
                }
                data.put(isResult ? "toolResult" : "toolInput", payload);
            }
        }
        return sse(isResult ? "tool_result" : "tool_call", data);
    }

    private static boolean isPlainResult(Map<String, Object> data) {
        return data.size() == 1 && data.get("result") instanceof String;
    }

    /**
     * Emits a single {@code RUN_SESSION} event the first time a (userId, agentId, conversationId)
     * tuple starts a chat in this process. Subsequent turns within the same session are silent.
     * Resetting the session via {@code /reset} clears the cached marker so a fresh session is
     * logged again.
     */
    private void recordRunSession(
            AgentDefinition def, String agentId, String userId, String conversationId) {
        if (def == null || def.ownerId() == null) {
            // Globals have no per-agent activity log.
            return;
        }
        // Use the gateKey here as a per-(user, agent, conversation) dedupe id — the real
        // sessionKey may not exist yet on the very first turn.
        String dedupeKey = resolveGateKey(userId, agentId, conversationId);
        if (dedupeKey == null) return;
        if (!startedSessions.add(dedupeKey)) return;
        activity.record(
                def.ownerId(),
                agentId,
                activity.actor(userId),
                ActivityEvent.Action.RUN_SESSION,
                dedupeKey,
                null);
    }

    /** Normalizes a request-supplied {@code sessionKey} into a conversationId, or {@code null}. */
    private static String normalizedConversationId(String key) {
        return (key != null && !key.isBlank()) ? key.trim() : null;
    }

    /**
     * Computes the gateway routing key for a given (userId, agentId, conversationId) tuple. This
     * is the {@link MsgContext#canonicalKey()} the gateway uses to look up (or create) the
     * underlying session — it is <em>not</em> the {@code SessionEntry.sessionKey()} the storage
     * layer uses. Use {@link #findSessionKeyByGate} to translate to the real sessionKey.
     *
     * <p>Uses {@link ChatUiChannel#previewRoute} so the key matches exactly what
     * {@link #executeChat} will produce when it dispatches through the same channel.
     * {@code conversationId} (when non-null) flows through to {@code MsgContext.threadId}, so each
     * ChatGPT-style session yields a distinct gateKey and therefore a distinct underlying session.
     */
    private String resolveGateKey(String userId, String agentId, String conversationId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
            InboundMessage probe =
                    InboundMessage.builder(ChatUiChannel.CHANNEL_ID, Peer.direct(userId), List.of())
                            .preferredAgentId(gatewayAgentId)
                            .accountId(conversationId)
                            .build();
            return chatUiChannel.previewRoute(probe).context().canonicalKey();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Translates a gateway routing key into the real {@code SessionEntry.sessionKey()}, by
     * scanning registered MAIN sessions for the matching {@code gateKey}. Returns {@code null}
     * when no session has been registered yet (e.g. before the first turn).
     */
    private String findSessionKeyByGate(String userId, String gateKey) {
        if (gateKey == null) return null;
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (e.kind() != SessionKind.MAIN) continue;
            if (!Objects.equals(gateKey, e.gateKey())) continue;
            if (userId != null && !Objects.equals(userId, e.userId())) continue;
            return e.sessionKey();
        }
        return null;
    }

    /**
     * Lazily checks whether a tool event belongs to the conversation this stream serves.
     *
     * <p>{@link ToolNotificationMiddleware} publishes with {@code RuntimeContext#getSessionId()}
     * — the short {@code main-…} runtime id — while the storage layer keys sessions by the full
     * {@code agent:…:main:…} key, so the event key is matched against either form of the session
     * resolved through the conversation's gate key. Matching per event (rather than once
     * up-front) also covers the first turn, where the gateway creates the session mid-flight.
     */
    private boolean isEventForConversation(
            String userId, String gateKey, ToolEventBus.ToolEvent e) {
        if (e.sessionKey() == null) {
            return false;
        }
        for (SessionEntry s : sessionAgentManager.allSessions()) {
            if (s.kind() != SessionKind.MAIN) continue;
            if (userId != null && !Objects.equals(userId, s.userId())) continue;
            if (e.sessionKey().equals(s.sessionKey()) || e.sessionKey().equals(s.sessionId())) {
                if (gateKey == null || Objects.equals(gateKey, s.gateKey())) {
                    return true;
                }
                // Fallback: on the first turn the previewRoute gateKey may differ from the
                // session actually created mid-flight. Accept by userId + sessionId match alone
                // when the gateKey was resolved before the session existed.
                return true;
            }
        }
        return false;
    }

    /**
     * If {@code message} is a recognised slash command, applies the side-effect (e.g. resets the
     * session for this user+agent+conversation tuple) and returns a synthetic confirmation reply.
     * Returns {@code null} for ordinary messages.
     *
     * <p>{@code /new} mints a fresh {@code sessionKey} (returned via {@link CommandResult#newSessionKey})
     * so the frontend can navigate to a new ChatGPT-style session; the previous session remains
     * intact. {@code /reset} clears the conversation history of the <em>current</em> session in
     * place.
     */
    private CommandResult handleSlashCommand(
            String userId, String agentId, String message, String conversationId) {
        if (message == null) return null;
        String m = message.trim();
        if (!m.startsWith("/")) return null;
        String[] parts = m.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/new":
                {
                    // Mint a brand-new session key; the FE picks it up from the SSE `done` event
                    // and navigates to the new conversation.
                    String fresh = UUID.randomUUID().toString();
                    return new CommandResult(
                            "Started a fresh conversation. Your next message opens a new chat.",
                            fresh);
                }
            case "/reset":
                {
                    String gateKey = resolveGateKey(userId, agentId, conversationId);
                    if (gateKey == null) {
                        return new CommandResult("No active session to reset.", null);
                    }
                    String sessionKey = findSessionKeyByGate(userId, gateKey);
                    if (sessionKey == null) {
                        // No session registered yet — there's nothing to clear, which from the
                        // user's perspective is effectively a fresh start. Drop the dedupe
                        // marker so the next real message logs a fresh RUN_SESSION event.
                        startedSessions.remove(gateKey);
                        return new CommandResult(
                                "No active session yet — your next message will start a fresh"
                                        + " conversation.",
                                null);
                    }
                    boolean ok = sessionAgentManager.resetSession(sessionKey);
                    startedSessions.remove(gateKey);
                    return new CommandResult(
                            ok
                                    ? "AgentStateStore reset. Conversation history cleared; the"
                                            + " next message starts a fresh turn."
                                    : "No matching session found for reset.",
                            null);
                }
            case "/identity":
                {
                    Map<String, String> links = identityLinks.linksFor(userId);
                    if (links.isEmpty()) {
                        return new CommandResult(
                                "No identity links yet. Use `/dock_<channel> <externalId>` to add"
                                        + " one — e.g. `/dock_slack U7F9LZK1A`.",
                                null);
                    }
                    StringBuilder sb = new StringBuilder("Your identity links:\n");
                    links.forEach(
                            (ch, id) ->
                                    sb.append("  - ")
                                            .append(ch)
                                            .append(" -> ")
                                            .append(id)
                                            .append('\n'));
                    return new CommandResult(sb.toString(), null);
                }
            default:
                if (cmd.startsWith("/dock_")) {
                    String channel = cmd.substring("/dock_".length());
                    if (channel.isBlank() || arg.isBlank()) {
                        return new CommandResult(
                                "Usage: `/dock_<channel> <externalId>` — e.g."
                                        + " `/dock_slack U7F9LZK1A`.",
                                null);
                    }
                    identityLinks.link(userId, channel, arg);
                    return new CommandResult(
                            "Linked your identity on `" + channel + "` to `" + arg + "`.", null);
                }
                return null;
        }
    }

    /**
     * Internal carrier for slash-command results. {@code newSessionKey} is non-null when the
     * command mints a fresh session (e.g. {@code /new}); the stream/sync endpoints surface it back
     * to the frontend so the URL can be updated.
     */
    private record CommandResult(String message, String newSessionKey) {}

    /**
     * Builds the inbound message list for a chat turn. Deliberately a plain {@link MsgRole#USER}
     * wrapper: the framework's {@code PreCallEvent} validation rejects SYSTEM messages injected
     * into {@code inputMessages}, so per-turn instruction injection is not allowed here. Language
     * and other behavioural policies live in the agent's own prompt material (AGENTS.md, skills,
     * sub-agent definitions) instead of being smuggled through the chat turn.
     */
    static List<Msg> shapeInboundMessages(String message) {
        return List.of(Msg.builder().role(MsgRole.USER).textContent(message).build());
    }

    /**
     * Builds the {@link InboundMessage} shared by both the synchronous and streaming dispatch
     * paths. Always routes through {@link ChatUiChannel} so that the {@link
     * io.agentscope.harness.agent.gateway.channel.ChannelRouter} runs uniformly — including for
     * the path-mapped Web UI calls. The URL-supplied {@code agentId} is passed as
     * {@link InboundMessage#preferredAgentId()} so it short-circuits the binding-tier evaluation
     * (the user explicitly picked this agent) while still letting the router derive sessionScope
     * and outbound addressing from any matching binding.
     *
     * <p>When {@code agentId} is blank (defensive — controller always supplies one), falls back to
     * pure binding-driven routing: the chatui channel's default agent or matching binding wins.
     */
    private InboundMessage buildInbound(
            String userId,
            String agentId,
            String message,
            String conversationId,
            java.util.List<String> groupIds) {
        List<Msg> msgs = shapeInboundMessages(message);
        conversationScopes.put(conversationId, groupIds);
        // Tools only see the runtime session id (main-…); it maps back to this gate key, so the
        // gate-key entry is what the tool-side scope fallback can resolve mid-run.
        String gateKey = resolveGateKey(userId, agentId, conversationId);
        if (gateKey != null && !gateKey.isBlank()) {
            conversationScopes.put(gateKey, groupIds);
        }
        RuntimeContext runtimeContext =
                RuntimeContext.builder()
                        .userId(userId)
                        .put(DatasetScope.class, new DatasetScope(userId, groupIds))
                        .build();
        if (agentId == null || agentId.isBlank()) {
            return InboundMessage.dm(ChatUiChannel.CHANNEL_ID, userId, List.copyOf(msgs))
                    .withRuntimeContext(runtimeContext);
        }
        String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
        return InboundMessage.builder(
                        ChatUiChannel.CHANNEL_ID, Peer.direct(userId), List.copyOf(msgs))
                .preferredAgentId(gatewayAgentId)
                .accountId(conversationId)
                .runtimeContext(runtimeContext)
                .build();
    }

    /**
     * Synchronous dispatch — returns the final reply as a single {@link Msg}. Used by the
     * {@link #send} endpoint which does not need incremental text streaming.
     */
    private Mono<Msg> executeChat(
            String userId,
            String agentId,
            String message,
            String conversationId,
            java.util.List<String> groupIds) {
        long startMs = System.currentTimeMillis();
        InboundMessage inbound = buildInbound(userId, agentId, message, conversationId, groupIds);
        final String recordedAgentId = agentId != null ? agentId : "(default)";
        return chatUiChannel
                .dispatch(inbound)
                .doOnSuccess(
                        reply ->
                                usageStore.record(
                                        userId,
                                        recordedAgentId,
                                        System.currentTimeMillis() - startMs));
    }

    /**
     * Streaming dispatch — returns fine-grained {@link AgentEvent}s (including
     * {@link TextBlockDeltaEvent} for incremental text) from
     * {@link ChatUiChannel#dispatchStream}. Used by the {@link #stream} SSE endpoint so the
     * frontend can render tokens as they arrive from the model.
     */
    private Flux<AgentEvent> executeChatStream(
            String userId,
            String agentId,
            String message,
            String conversationId,
            java.util.List<String> groupIds) {
        long startMs = System.currentTimeMillis();
        InboundMessage inbound = buildInbound(userId, agentId, message, conversationId, groupIds);
        final String recordedAgentId = agentId != null ? agentId : "(default)";
        return chatUiChannel
                .dispatchStream(inbound)
                .doOnComplete(
                        () ->
                                usageStore.record(
                                        userId,
                                        recordedAgentId,
                                        System.currentTimeMillis() - startMs));
    }

    private ServerSentEvent<String> sse(String eventType, Object data) {
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            json = "{\"type\":\"" + eventType + "\"}";
        }
        return ServerSentEvent.<String>builder().event(eventType).data(json).build();
    }
}
