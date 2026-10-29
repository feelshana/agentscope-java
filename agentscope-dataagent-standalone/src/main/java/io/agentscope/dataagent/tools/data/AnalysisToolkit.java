package io.agentscope.dataagent.tools.data;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;

/**
 * spec102: the parameter card of an analysis turn. The assistant prefills the parameters from the
 * question and the model; the page renders this call as an editable card and the user starts the
 * analysis from it. Nothing runs here — the call only records the proposal.
 */
public final class AnalysisToolkit {

    @Tool(
            name = "propose_analysis",
            description =
                    """
                    提交分析参数，由页面展示为可修改的参数确认卡。仅在用户选择了分析方法、且参数尚未确认的轮次使用：\
                    根据问题和语义模型预填参数后调用一次，然后用一句话请用户确认参数并结束本轮，不执行 SQL。\
                    指标需写明口径来源；日期写成 YYYY-MM-DD 至 YYYY-MM-DD；candidate_dimensions 列出可选的分组字段。\
                    """)
    public String proposeAnalysis(
            RuntimeContext context,
            @ToolParam(name = "method", description = "分析方法：attribution / anomaly / forecast")
                    String method,
            @ToolParam(name = "metric", description = "分析指标及口径，如 访问次数（报表访问明细记录行数）") String metric,
            @ToolParam(name = "metric_note", description = "口径来源说明，如 来自业务规则", required = false)
                    String metricNote,
            @ToolParam(
                            name = "current_period",
                            description = "归因的当前期，或异常检测、预测使用的历史时间范围",
                            required = false)
                    String currentPeriod,
            @ToolParam(name = "base_period", description = "归因的对比期", required = false)
                    String basePeriod,
            @ToolParam(
                            name = "granularity",
                            description = "时间粒度：day / hour / week / month",
                            required = false)
                    String granularity,
            @ToolParam(name = "dimensions", description = "预选的归因维度（最多 4 个）", required = false)
                    List<String> dimensions,
            @ToolParam(
                            name = "candidate_dimensions",
                            description = "可供用户勾选的分组字段（中文名）",
                            required = false)
                    List<String> candidateDimensions,
            @ToolParam(name = "depth", description = "归因下钻层数：1 或 2", required = false)
                    Integer depth,
            @ToolParam(name = "horizon", description = "预测期数", required = false) Integer horizon,
            @ToolParam(name = "filters", description = "来自业务规则的默认过滤条件（中文描述）", required = false)
                    List<String> filters) {
        AnalysisTurn turn = context == null ? null : context.get(AnalysisTurn.class);
        if (turn == null) return "error: 本轮用户没有选择分析方法，不要提交分析参数；按普通问数回答。";
        if (turn.confirmed()) return "error: 参数已经确认，直接按已确认的参数取数并运行分析脚本。";
        String id = AnalysisSkills.normalize(method);
        if (id == null || !id.equals(turn.method()))
            return "error: method 必须是用户选择的分析方法 " + turn.method();
        if (metric == null || metric.isBlank()) return "error: 请填写分析指标";
        if (dimensions != null && dimensions.size() > 4) return "error: 归因维度最多 4 个";
        return "已向用户展示《" + turn.name() + "》参数确认卡。请用一句话请用户确认参数，然后结束本轮回复，不要执行查询。";
    }
}
