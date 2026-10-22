package io.agentscope.dataagent.dataset;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Modeling turns currently running per knowledge base (single process, like the other capacity
 * budgets). ADR0111: a draft the assistant is still changing cannot be published.
 */
@Component
public class ModelingActivity {
    private final ConcurrentHashMap<String, AtomicInteger> running = new ConcurrentHashMap<>();

    public void begin(String groupId) {
        running.computeIfAbsent(groupId, k -> new AtomicInteger()).incrementAndGet();
    }

    public void end(String groupId) {
        running.computeIfPresent(
                groupId, (k, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    public boolean running(String groupId) {
        AtomicInteger count = running.get(groupId);
        return count != null && count.get() > 0;
    }

    public void requireIdle(String groupId) {
        if (running(groupId)) throw new DatasetException("助手正在修改草稿，请等它完成当前步骤后再发布或撤销", 409);
    }
}
