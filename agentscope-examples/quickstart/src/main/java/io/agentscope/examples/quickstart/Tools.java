package io.agentscope.examples.quickstart;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

public class Tools {
    @Tool(description = "获取指定城市的天气")
    public String query_weather(
            @ToolParam(name = "city", description = "城市名称") String city,
            @ToolParam(name = "date", description = "日期") String date) {
        return city + " 的天气：晴天，25°C，日期：" + date;
    }

    @Tool(description = "获取当前日期")
    public String get_current_date() {
        return LocalDate.now().toString();
    }

    public static void main(String[] args) {
        Mono<String> result =
                Mono.defer(
                        () -> {
                            try {
                                Thread.sleep(4000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            return Mono.just("Hello, World!");
                        });
        result.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(System.out::println);
        System.out.println("我在前面执行哦，一点都没阻塞");
    }
}
