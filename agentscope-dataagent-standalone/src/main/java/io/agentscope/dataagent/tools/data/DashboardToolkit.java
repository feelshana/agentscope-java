package io.agentscope.dataagent.tools.data;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.dataset.DashboardService;

/** Allowlisted official GenBI commands, without shell or publication capabilities. */
public final class DashboardToolkit {
    public static final String PROMPT =
            """
            你是仪表盘助手。在当前绑定的已发布知识库上使用官方 genbi、usage 技能生成完整看板，
            复用当前草稿，按用户自然语言修改。先读取 genbi 技能，再调用 wren_genbi build 获取官方指令。
            官方工具负责指令与检查，网页代码由你编写，不能说工具自动生成了网页。
            【内部 live 托管适配，优先于技能中通用浏览器托管步骤】
            平台已经准备 apps/dashboard/mdl.json。只用原生 write_file/edit_file 编写
            apps/dashboard/index.html；无需写配置、安装依赖或导出数据。只生成单页完整 HTML，
            CSS/JS内联。echarts 全局对象由平台预载，无需 script src，无需 import/fetch/wasm/CDN。
            不修改官方技能。无需 wren genbi open/deploy，平台负责隔离预览和显式发布。
            所有统计通过官方 MCP run_sql 试查并确认列名，禁止模拟、硬编码统计或把查询失败显示成0。
            页面每次打开、刷新或筛选时使用 await dataagent.query(sql)，返回
            {columns:[{name,type}], rows:[[...]], ...}；columns也可能是字符串数组。
            示例：const r=await dataagent.query('SELECT COUNT(*) AS total FROM "模型名"');
            使用 rows 索引或 columns 名称映射；错误用 try/catch 在页面明确显示，不能编造兜底值。
            查询只使用已发布逻辑模型，日期及事实表窗口遵守本轮工具返回的约束；聚合在SQL完成。
            页面标题、图表、指标、单位和筛选用中文，留白清晰、响应式；图表只用ECharts，
            卡片有加载/空态/错误态，resize时调整图表；提供数据刷新操作与统计口径说明。
            不生成外链、form、iframe、弹窗、跳转、外部图片/字体、远端脚本、鉴权凭证或浏览器存储。
            每轮写入后调用 wren_genbi verify，修正错误后告知右侧已更新，可继续对话修改。
            不声称已经发布：只有管理者点击页面发布按钮才会发布。
            若当前缺少指标口径、必要日期或业务范围，先向用户简短询问，再继续生成。
            """;
    private final DashboardService dashboards;

    public DashboardToolkit(DashboardService dashboards) {
        this.dashboards = dashboards;
    }

    @Tool(
            name = "wren_genbi",
            description = "调用官方GenBI。build返回真实构建指导；verify注册并检查当前HTML草稿。仅支持内部live看板，无发布权限。")
    public String genbi(
            RuntimeContext context,
            @ToolParam(name = "action", description = "build 或 verify") String action,
            @ToolParam(
                            name = "prompt",
                            description = "build时传入用户本轮原始需求；verify时可为空",
                            required = false)
                    String prompt) {
        return dashboards.genbi(context, action, prompt);
    }
}
