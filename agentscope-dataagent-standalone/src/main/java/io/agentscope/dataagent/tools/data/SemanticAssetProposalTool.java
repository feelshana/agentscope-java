package io.agentscope.dataagent.tools.data;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.dataset.AssetProposalStore;
import io.agentscope.dataagent.dataset.ModelingTurnPurpose;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import java.util.List;

/**
 * spec114 / ADR0132: modeling changes are proposed in the query coach, where the data developer
 * lists frequent questions and approves each proposal before anything is drafted. Outside the
 * coach the only proposal allowed is a query template the user asked for in so many words, which
 * the page then drafts in a declared asset turn.
 */
public final class SemanticAssetProposalTool {
    private final WrenProjectAccess access;
    private final AssetProposalStore proposals;

    public SemanticAssetProposalTool(WrenProjectAccess access, AssetProposalStore proposals) {
        this.access = access;
        this.proposals = proposals;
    }

    @Tool(
            name = "propose_modeling_change",
            description =
                    """
                    向用户提议一项建模改动，页面以方案表或提议卡展示，用户批准后才起草；提交后不要写对应文件。
                    只在"问数优化"轮次中使用：为每个常用问题判断现有模型、关系、规则能否答对，对缺口逐项提议，question_ids 填它服务的问题 ID。
                    kind 按官方去处选择：rule（口径、默认过滤、术语定义，含依赖统计日期的分类如留存、流失）；relationship（表之间缺少或需修正的关联）；calculated_field（反复使用的单行业务分类）；view 只用于用户明确要求的查询模板，问数优化中不提议 view。带出关联表的属性靠关系，不提议计算字段。
                    其他轮次只有用户明确要求建视图或查询模板时可以调用，kind 填 view、explicit 填 true；没有明确要求时不要调用。
                    name 写简短名称（查询模板名称会成为模型名，不能与已有模型重名）；need 用业务语言写要解决的问题；outline 写起草要点（规则原文要点、关联键与基数、表达式、或查询模板的模型、关联键、输出列与需保留的过滤列）；reason 写为什么需要这项改动。\
                    """)
    public String propose(
            RuntimeContext context,
            @ToolParam(name = "kind", description = "rule、relationship、calculated_field 或 view")
                    String kind,
            @ToolParam(name = "name", description = "简短名称；查询模板名称即模型名") String name,
            @ToolParam(name = "need", description = "要解决的问题，业务语言") String need,
            @ToolParam(name = "outline", description = "起草要点") String outline,
            @ToolParam(name = "reason", description = "为什么需要这项改动") String reason,
            @ToolParam(name = "question_ids", description = "服务的常用问题 ID 列表", required = false)
                    List<String> questionIds,
            @ToolParam(
                            name = "explicit",
                            description = "用户在对话中明确要求建视图或查询模板时填 true",
                            required = false)
                    Boolean explicit) {
        try {
            var project = access.resolve(context);
            if (project.assetTurn()) return "error: 本轮已经是建设语义资产轮次，请直接起草，不需要再提议。";
            var purpose = context.get(ModelingTurnPurpose.class);
            boolean coach = purpose != null && purpose.coach();
            boolean asked =
                    Boolean.TRUE.equals(explicit)
                            && "view".equalsIgnoreCase(kind == null ? "" : kind.strip());
            if (!coach && !asked)
                return "error: 只有问数优化中可以提议建模改动；用户明确要求建视图或查询模板时才可在本轮提议（kind=view，explicit=true）。"
                        + "其他口径直接写入规则，或提示用户把相关问题加入问数优化。";
            if (!coach && purpose != null) return "error: 初次建模、处理文档、写入确认和试问轮次不提议建模改动。";
            // Query templates come from common logic across confirmed questions (the "公共视图"
            // extraction, being built), not from one coach batch.
            if (coach && "view".equalsIgnoreCase(kind == null ? "" : kind.strip()))
                return "error: 问数优化不提议查询模板：需要聚合、窗口函数或多步计算的题直接在这道题的 SQL 里写；"
                        + "多道题反复用到的逻辑以后由\"公共视图\"统一提炼。";
            var created =
                    proposals.propose(
                            project.groupId(),
                            kind,
                            name,
                            need,
                            outline,
                            reason,
                            questionIds,
                            !coach && asked);
            return coach
                    ? "已提议（"
                            + created.kind()
                            + "）「"
                            + created.name()
                            + "」，页面方案表会显示，由用户批准后起草。不要写对应文件。"
                    : "已按你的要求提议查询模板「" + created.name() + "」，页面会直接开始起草。不要在本轮写 views/ 文件。";
        } catch (RuntimeException e) {
            return "error: " + e.getMessage();
        }
    }
}
