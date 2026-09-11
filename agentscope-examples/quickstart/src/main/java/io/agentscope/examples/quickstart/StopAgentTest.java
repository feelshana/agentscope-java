package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import reactor.core.publisher.Mono;

public class StopAgentTest {
    public static void main(String[] args) {

        // =========================
        // 1. 构建 Toolkit & ToolSchema
        // =========================
        Toolkit toolkit = new Toolkit();

        toolkit.registerTool(new Tools());
        Hook hook =
                new Hook() {

                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostActingEvent postActingEvent) {
                            String tool = postActingEvent.getToolUse().getName();
                            if (tool.equals("query_weather")) {
                                //                                postActingEvent.stopAgent();
                            }
                        }
                        return Mono.just(event);
                    }
                };

        DashScopeChatModel model =
                DashScopeChatModel.builder()
                        .apiKey("sk-8d1642857f804232bf53e4916295fa4a")
                        .modelName("qwen-plus")
                        .stream(false)
                        .build();
        Memory memory = new InMemoryMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("Assistant")
                        .sysPrompt(
                                """
                                你是一个有帮助的 AI 助手。
                                可使用get_current_date工具获取当前日期
                                """)
                        .model(model)
                        .memory(memory)
                        .toolkit(toolkit)
                        .hook(hook)
                        .modelExecutionConfig(ExecutionConfig.builder().maxAttempts(3).build())
                        .build();

        Event event = agent.stream(Msg.builder().textContent("上海明天的天气怎么样？").build()).blockLast();
        Map map = Map.of("key", "value");
        map.forEach((k, v) -> System.out.println(k + " -> " + v));

        System.out.println(event.getMessage().getGenerateReason());
        System.out.println(event.getMessage().getTextContent());
        // 模拟下一轮对话
        event = agent.stream(Msg.builder().textContent("谢谢,再见").build()).blockLast();

        System.out.println(event.getMessage().getTextContent());
    }
}
