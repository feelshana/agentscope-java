package io.agentscope.dataagent.runtime.session;

import io.agentscope.dataagent.dataset.ModelingTurnPurpose;

/**
 * Turn-specific modeling rules, chosen by the purpose the page declared for the turn (spec092).
 * Rules that override official Wren defaults stay in the always-on modeling prompt; only what a
 * single kind of turn needs lives here.
 */
public final class ModelingTurnRules {
    static final String AUTO =
            """
            # 本轮任务要求：自动建模
            按官方 enrich-context Auto-pilot 完成基础语义建模：补充模型与列描述，按运行上下文的命名候选与表来源处理关系，提出表角色建议，然后校验。没有业务文档时不写业务规则。
            工程现状以运行上下文为准：不执行 context show、memory status，不 list_files/glob 查找其他资料；一次读完清单中的模型文件、知识文件和 raw/docs/ 下的业务文档（有文档时逐份读完并与模型对比）后直接写入，需要提问的口径和关系一次性提出。
            上传表的被连接侧键经全表查询证实唯一（总行数等于去重数且无空键）时，可以声明 MANY_TO_ONE，并在关系描述中注明"依据当前上传数据的全表验证，非数据库约束，重新上传后需复核"；外部数据源表仍需约束或用户确认。
            最终回复 3–6 行：理解了哪些表及各自的粒度（一行代表什么）、写入了哪些关键业务口径、还有几个问题需要用户确定。
            """;

    static final String DOCUMENT =
            """
            # 本轮任务要求：业务文档更新
            按官方 enrich-context 的 sink 决策写入文档中的固定过滤、指标和日期口径，规则注明来源文档；只修改与文档有差异的内容，不重新生成模型。规则已记录不等于已执行验证。
            工程现状以运行上下文为准：不执行 context show、memory status，不 list_files/glob 查找；只读有变化的文档和与之相关的模型、规则文件，不重新加载已加载的技能。
            最终回复 3–6 行：写入了哪些口径、还有几个问题需要用户确定。
            """;

    static final String CONFIRM =
            """
            # 本轮任务要求：写入用户的确定
            把每条答复整理后写入 knowledge/rules/（指标定义、默认过滤、术语）或对应模型的列描述（字段含义），确定建立的关系写入 relationships.yml；不再就这些内容提问。
            最终回复 1–2 行：按用户的选择更新了哪些口径。
            """;

    static final String QUESTION =
            """
            # 本轮任务要求：试问 SQL
            逐题读取当前模型、关系与业务规则确定口径，写一条只读 SQL（多表必须 JOIN … ON）。试问不查询数据：只用 wren_cli dry-plan 检查 SQL 能否生成（不访问数据库），失败时修正一次；不要执行 query。然后写入 knowledge/questions/<ID>.yml：question 原文不改写，definition 按指标/粒度/时间范围/过滤/假设分五行、每行一句业务语言且不写 SQL 片段，以及 sql。用户在试问面板核对 SQL 并确认。
            生成 SQL 前逐项核对名称、已确认口径与 SQL 过滤一致：例如已确认排除的部门、软删、退款、内部测试账号保持对应过滤和必要关联；只检查已确认规则，不擅自新增业务条件。
            问题缺日期时自选符合各表时间配置的近期有限范围并在 definition 写明，不冒充用户指定日期。用户原始问题和固定业务周期（如月度、滚动30天）不能缩短后冒充原问题。
            最终回复每题 1 行（如"已生成，请在试问面板核对 SQL"），口径细节写在 definition 中不重复，不声称已查询或给出数值。
            """;

    static final String CHAT =
            """
            # 本轮任务要求：用户对话
            直接回答或按用户要求修改，修改后校验编译；用户在对话中说明的口径直接写入 knowledge/rules/ 或对应列描述，不再向用户确认。改动影响已有试问问题时，按"试问 SQL"要求同步更新问题文件。回复直接回答，避免技术细节。
            """;

    private ModelingTurnRules() {}

    /**
     * The section for a declared purpose. A missing purpose is the user's chat request: it may
     * change rules and so the trial questions that depend on them, so it also gets the SQL rules.
     */
    public static String forPurpose(String purpose) {
        if (ModelingTurnPurpose.AUTO.equals(purpose)) return AUTO;
        if (ModelingTurnPurpose.DOCUMENT.equals(purpose)) return DOCUMENT;
        if (ModelingTurnPurpose.CONFIRM.equals(purpose)) return CONFIRM;
        if (ModelingTurnPurpose.QUESTION.equals(purpose)) return QUESTION;
        return CHAT + "\n" + QUESTION;
    }

    /** Turns that may model tables need the probe boundary and relationship candidates. */
    static boolean modelsTables(String purpose) {
        return purpose == null || ModelingTurnPurpose.AUTO.equals(purpose);
    }

    /** Turns that write SQL need the saved per-table time windows and date formats. */
    static boolean writesSql(String purpose) {
        return purpose == null || ModelingTurnPurpose.QUESTION.equals(purpose);
    }
}
