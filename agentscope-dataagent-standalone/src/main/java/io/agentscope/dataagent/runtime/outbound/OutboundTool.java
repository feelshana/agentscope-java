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
package io.agentscope.dataagent.runtime.outbound;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.gateway.ChannelManager;
import java.util.Objects;

/**
 * Agent-facing tool for proactive outbound delivery into any registered channel (DingTalk, WeCom,
 * ...). Registered onto the main agent's toolkit by {@link
 * io.agentscope.dataagent.runtime.DataAgentBootstrap}.
 *
 * <p>The agent supplies the target ({@code channel_id} + {@code peer_kind} + {@code peer_id}) and
 * either {@code text} or {@code markdown}. The tool returns a short status string.
 */
public final class OutboundTool {

    private final OutboundService service;

    public OutboundTool(ChannelManager channelManager) {
        Objects.requireNonNull(channelManager, "channelManager");
        this.service = new OutboundService(channelManager);
    }

    @Tool(
            name = "outbound_send",
            description =
                    """
                    主动向已注册的 IM 频道发送消息（如钉钉、企业微信）。\
                    用于在没有用户 incoming 消息时推送通知、状态更新或跟进消息。\
                    指定 channel_id（已注册的频道）、peer_kind（DIRECT | CHANNEL | GROUP | THREAD）\
                    和 peer_id（提供商特定的用户或群组 ID）。text 和 markdown 二选一。\
                    成功返回 "ok"，失败返回简短错误描述。\
                    """)
    public String send(
            @ToolParam(name = "channel_id", description = "已注册的频道 ID（如 'wecom-prod'）")
                    String channelId,
            @ToolParam(
                            name = "peer_kind",
                            description = "DIRECT | CHANNEL | GROUP | THREAD；省略时默认 DIRECT")
                    String peerKind,
            @ToolParam(name = "peer_id", description = "目标用户/群组/频道 ID") String peerId,
            @ToolParam(name = "text", description = "纯文本消息内容；与 markdown 互斥", required = false)
                    String text,
            @ToolParam(
                            name = "markdown",
                            description = "Markdown 格式消息内容；与 text 互斥",
                            required = false)
                    String markdown,
            @ToolParam(
                            name = "account_id",
                            description = "可选的多账户维度标识（企业/应用实例），" + "当频道托管多个账户时使用",
                            required = false)
                    String accountId,
            @ToolParam(name = "thread_id", description = "可选的话题锚点，用于话题回复", required = false)
                    String threadId,
            @ToolParam(
                            name = "agent_id",
                            description =
                                    "可选的调用方代理 ID。设置后，服务会验证该频道对此 peer 的"
                                            + "路由是否指向同一代理，否则拒绝投递——防止一个代理"
                                            + "向绑定到其他代理的频道/peer 发送消息。省略则不进行调用方检查。",
                            required = false)
                    String agentId) {
        try {
            service.send(
                    new OutboundRequest(
                            channelId, peerKind, peerId, accountId, threadId, text, markdown,
                            agentId));
            return "ok";
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        }
    }
}
