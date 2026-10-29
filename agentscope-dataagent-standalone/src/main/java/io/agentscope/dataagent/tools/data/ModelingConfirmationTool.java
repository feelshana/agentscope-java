package io.agentscope.dataagent.tools.data;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.dataset.ModelingConfirmationService;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import java.util.List;

/**
 * The modeling assistant's way to ask the user (spec092), like a coding agent asking a question:
 * a few options with the recommended one first; the user picks, writes their own or skips in a
 * page dialog. Asking is rare by design and capped per turn.
 */
public final class ModelingConfirmationTool {
    public static final int MAX_PER_TURN = 3;

    /**
     * Relationship candidates have their own allowance: the 2026-10-22 run spent all three
     * questions on metric definitions and published an external-table project with no
     * relationships at all.
     */
    public static final int MAX_RELATIONSHIPS_PER_TURN = 3;

    private final WrenProjectAccess access;
    private final ModelingConfirmationService confirmations;

    public ModelingConfirmationTool(
            WrenProjectAccess access, ModelingConfirmationService confirmations) {
        this.access = access;
        this.confirmations = confirmations;
    }

    @Tool(
            name = "request_user_confirmation",
            description =
                    """
                    向用户提出一个需要业务决定的口径问题，页面会以卡片让用户选择。只在同时满足以下条件时使用：\
                    口径确实有歧义，并且不同选择会改变统计结果。文档写清楚的、采样或全表验证能确定的、用户在对话中已说明的，\
                    直接写入业务规则或列描述，不要提问。每轮最多 3 个问题，同一件事不要重复问。
                    选项 2–4 个，第一个是你推荐的；推荐必须有文档、数据或用户说明作依据，没有依据时（例如编码值含义只是猜测）第一个选项写"暂不确定，先记为待确认"，猜测的含义放在后面；option_rules 写选择该项后要写入的规则原文（一句话，可直接作为规则），与 option_labels 数量相同、一一对应。"自己写""不采用""稍后"由页面提供，不要作为选项。
                    外部表的候选关系（主键唯一性无法验证）也用本工具提问，topic 填 relationship，选项写明关系的两侧列与 join_type；按字段命名得出的候选（如 operator_id 对应 sys_operator.id）第一个选项就是该关联，不因类型或采样值格式不同而否定；关系问题另有每轮 3 个名额，不占口径问题的名额。
                    提交后不要在对话中等待或重复提问，继续完成其余工作；用户确认后你会收到一条消息，届时再写入规则或列描述。\
                    """)
    public String request(
            RuntimeContext context,
            @ToolParam(name = "question", description = "要用户决定的问题，一句话") String question,
            @ToolParam(name = "basis", description = "为什么需要确认：歧义来源或依据") String basis,
            @ToolParam(name = "option_labels", description = "选项名称，2–4 个，第一个为推荐")
                    List<String> optionLabels,
            @ToolParam(name = "option_rules", description = "与选项一一对应的规则原文")
                    List<String> optionRules,
            @ToolParam(
                            name = "topic",
                            description = "问题类型：relationship 表示表间关系，其余口径问题留空",
                            required = false)
                    String topic) {
        try {
            var project = access.resolve(context);
            List<String> labels = withoutPageOptions(optionLabels, optionRules);
            boolean relationship =
                    "relationship".equalsIgnoreCase(topic == null ? "" : topic.trim());
            String key =
                    (relationship ? "modelingRelationshipConfirmations." : "modelingConfirmations.")
                            + project.groupId();
            requireCapacity(context, key, relationship);
            var created =
                    confirmations.create(project.groupId(), question, basis, labels, optionRules);
            // Only questions that were actually created use up this turn's allowance.
            count(context, key);
            return "已提交给用户确认（" + created.question() + "）。不要等待，继续完成其余工作；用户确认后会发来消息。";
        } catch (RuntimeException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Drops trailing labels without a rule, e.g. "其他/我在对话中说明": the page always offers
     * writing one's own answer, so such an option only breaks the label/rule pairing.
     */
    static List<String> withoutPageOptions(List<String> labels, List<String> rules) {
        if (labels == null || rules == null || labels.size() <= rules.size()) return labels;
        return labels.subList(0, rules.size());
    }

    /** Turn-scoped cap; the RuntimeContext is rebuilt every turn. */
    private static void requireCapacity(RuntimeContext context, String key, boolean relationship) {
        if (context == null) return;
        synchronized (context) {
            int used = context.get(key) instanceof Integer count ? count : 0;
            int limit = relationship ? MAX_RELATIONSHIPS_PER_TURN : MAX_PER_TURN;
            if (used >= limit)
                throw new IllegalStateException(
                        relationship
                                ? "本轮已提出 " + limit + " 个关系问题，不再提问；其余候选关系不写入工程，在本轮回复中列出"
                                : "本轮已提出 " + limit + " 个口径问题，不再提问；其余不确定之处不写入工程，在本轮回复中列出");
        }
    }

    private static void count(RuntimeContext context, String key) {
        if (context == null) return;
        synchronized (context) {
            int used = context.get(key) instanceof Integer count ? count : 0;
            context.put(key, used + 1);
        }
    }
}
