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
            把每条答复整理后写入 knowledge/rules/（指标定义、默认过滤、术语）或对应模型的列描述（字段含义），确定建立的关系写入 relationships.yml；不再就这些内容提问。默认排除、只保留某些记录的口径写在 knowledge/rules/general.md 的"## Default filters"小节下（没有就新建），即使文件里已有其他自建小节也不另起标题。
            最终回复 1–2 行：按用户的选择更新了哪些口径。
            """;

    static final String QUESTION =
            """
            # 本轮任务要求：试问 SQL
            逐题读取当前模型、关系与业务规则确定口径，写一条只读 SQL（多表必须 JOIN … ON）。试问不查询数据：只用 wren_cli dry-plan 检查 SQL 能否生成（不访问数据库），失败时修正一次；不要执行 query。然后写入 knowledge/questions/<ID>.yml：question 原文不改写，definition 按指标/粒度/时间范围/过滤/假设分五行、每行一句业务语言且不写 SQL 片段，以及 sql；文件里已有 analysis 列表时保留，口径已补齐的缺口项改为 ok: true。用户在试问面板核对 SQL 并确认。
            生成 SQL 前逐项核对名称、已确认口径与 SQL 过滤一致：例如已确认排除的部门、软删、退款、内部测试账号保持对应过滤和必要关联；只检查已确认规则，不擅自新增业务条件。
            问题缺日期时自选符合各表时间配置的近期有限范围并在 definition 写明，不冒充用户指定日期。用户原始问题和固定业务周期（如月度、滚动30天）不能缩短后冒充原问题。
            最终回复每题 1 行（如"已生成，请在试问面板核对 SQL"），口径细节写在 definition 中不重复，不声称已查询或给出数值。
            """;

    static final String ASSET =
            """
            # 本轮任务要求：建设语义资产
            本轮例外：可以读写 views/，用于建设用户要求的语义资产。cubes/ 仍不读不写。不写问答对，不发布，不声明资产已核查或已验证。
            用户用业务语言描述需求，不会说出资产类型。面向用户的回复把 View 称为"查询模板"，不出现 View、ref_sql、statement 等技术名词。
            按官方 enrich-context 的去处选择（ADR0131）：
            - 默认过滤（默认排除、只保留某些记录，例如排除测试账号、只算已支付订单）不建资产：在 knowledge/rules/general.md 的"## Default filters"小节下追加规则，写清判断条件，以及用户明确要求包含时改为不过滤；回复中说明"这类需求写成规则即可，没有建资产"。
            - 单行计算用计算字段，不建资产：写在对应模型的 columns 里（is_calculated: true 和 expression），描述列出全部取值。
            - 查询模板（View）：把多个模型固定地拼接，或用窗口函数、CTE 算出一个可以直接查询的结果，例如访问明细拼上访问人的公司、部门。写 views/<名称>/metadata.yml，statement 用逻辑模型名，可以使用计算字段，不能引用其他查询模板。保留应用已有默认过滤所需的列（例如判断账号是否存在所需的关联表账号列），不要用 IFNULL、COALESCE 等默认值把"关联不上"和"值为空"合成同一个值；窗口函数列在描述中写明统计范围（例如"按全部数据计算的第几次访问"）。
            - 加工表（ref_sql 模型）不再作为默认选项：只有用户明确要求一张加工后的表、且查询模板无法表达时才写 models/<名称>/metadata.yml 加 ref_sql.sql（SQL 用物理全限定名 库名.表名），并在描述中写明原因和实际读取的物理表。面向用户称为"加工表"。
            默认使用规则：问数助手按业务规则选择模型，资产没有对应规则就不会被默认使用。建好查询模板后，按官方 enrich-context 的写回路由，在 knowledge/rules/general.md 的"## Canonical tables"下追加一条规则（小节不存在时新建），写明哪些统计默认使用该资产，以及什么情况改用原模型。是否默认使用不明确时用 request_user_confirmation 提问；不修改已有规则。写规则前先读 knowledge/rules/ 下已有规则：新规则与已有规则对同一类统计说法相反时（例如已有规则要求保留某类记录，新规则要求默认排除），这是官方的冲突情形，先用 request_user_confirmation 请用户选择以哪条为准，未经选择不要写入两条相反的规则。用户选择原口径时，新规则不得与之相反（缩小资产的默认使用范围）；用户选择新口径时，在新规则中写明它取代旧说法的范围，已有规则仍不改写，并在最终回复中提醒用户到"业务知识"页删除或修改旧规则。
            用户粘贴已有 SQL 时，保留其业务逻辑，按上面的去处选择；写查询模板时把物理表换成对应逻辑模型，换不了时说明原因并请用户决定是否改用加工表。先确定粒度、口径和过滤条件：文档、规则或用户说明里没有依据时，用 request_user_confirmation 提问，不自行选定。一对多关联必须先聚合明细再 JOIN，避免重复计算。
            资产的 properties.description 按以下小节写，供用户核查：用途、粒度（一行代表什么）、依赖（用到的模型）、JOIN（连接键与基数）、口径（每个 CASE WHEN 或过滤的含义和来源）、时间列、风险（可能丢行或放大的地方）。依赖事实表的资产必须输出明确的日期或时间列。
            资产定义不写固定的日期范围或当前日期：不要在 statement 或 ref_sql 里加起止日期，除非用户明确要求这是业务口径；时间过滤留给查询时按输出的时间列添加。检查用的 SQL 可以带日期条件，资产本身不带。
            不查询任何表数据，也不执行 dry-run，只用 dry-plan 检查（不连接数据库），由用户在公共视图页用问题核对 SQL（ADR0132）。写完执行 context validate --strict，再用 dry-plan 检查 SELECT * FROM 资产名。依赖事实表且含窗口函数或聚合时，在描述的风险小节写明：问数在外层加的时间条件推不进内部计算，会先计算全部数据，窗口或聚合的统计范围是全部数据而不是查询的日期范围。
            最终回复 3–6 行：做了什么（查询模板、规则或计算字段）、粒度、关键口径、写了哪条默认使用规则、还需要用户确认什么。建了查询模板或加工表时提示到公共视图页核查，默认使用规则在草稿栏查看；只写规则或计算字段时提示在草稿栏审阅后发布，它们不进公共视图页。
            """;

    /** spec114 / ADR0132: the only turn that proposes modeling changes, from listed questions. */
    static final String COACH =
            """
            # 本轮任务要求：问数优化
            用户列出了业务常问的问题（每题带 [ID: …]）。本轮只分析和提议，不写规则、关系、计算字段、views/ 或加工表文件，不查询数据。逐题：
            1. 按顺序查口径：先读当前草稿的模型、关系和 knowledge/rules/ 下的业务规则；规则里没有的，再读 raw/docs/ 下的业务文档（只读，不搜索外部）。
            2. 规则已有定义、能稳定答对的：写一条只读 SQL，只用 wren_cli dry-plan 检查能否生成（失败时最多修正一次），写入 knowledge/questions/<ID>.yml（question 原文不改写、definition、sql）。
            3. 规则没有、但业务文档里写明了的口径：不提问，用 propose_modeling_change 提议 kind=rule，outline 写规则原文并注明出处（文档名和小节）；这道题的 SQL 按文档口径写好，在"假设"行写明"口径来自文档，规则批准后生效"。
            4. 规则和文档都没有、或两者矛盾，且不同理解会改变统计结果（范围、截止日期、门槛、未匹配记录怎么算等）：只用 request_user_confirmation 提问，question_ids 填相关问题 ID；先定口径再写 SQL，这道题的 sql 留空，definition 的"假设"行写"待确认口径"，不要按猜测写 SQL；同一口径不再用 propose_modeling_change 重复提议规则。
            5. 其他缺口用 propose_modeling_change 逐项提议，question_ids 填相关问题 ID；同一项改动服务多个问题时只提议一次。kind 按去处判断：关联靠 relationship（不为带出关联表的属性提议计算字段或查询模板）。需要的聚合、窗口函数、多步计算直接写在各题的 SQL 里，不算缺口；本轮不提议 view（查询模板），多道题反复用到的逻辑以后由"公共视图"统一提炼。calculated_field 只为本批反复使用的单行业务分类提议。
            6. 每道题（包括 SQL 留空的题）都写 knowledge/questions/<ID>.yml，并加 analysis 列表（最多 5 项），每项为 {ok: true 或 false, text: 一句业务语言}：ok 为 true 写可直接复用的模型、关系或规则；ok 为 false 只写本轮已用确认卡提问或已提议的缺口，自行按假设处理的细节（如并列排序、边界取值）写在 definition 的"假设"行，不列为缺口。每项是一句完整的话，不写文件路径、SQL 片段和技术名词。已有查询模板时可以读取 views/ 判断能否复用，但不修改。
            最终回复逐题一行：能否答对、口径从哪来（规则、文档或待你确认）、提议了什么；不列文件路径和命令。
            """;

    static final String CHAT =
            """
            # 本轮任务要求：用户对话
            直接回答或按用户要求修改，修改后校验编译；用户在对话中说明的口径直接写入 knowledge/rules/ 或对应列描述，不再向用户确认。默认排除、只保留某些记录的口径写在 knowledge/rules/general.md 的"## Default filters"小节下（没有就新建），即使文件里已有其他自建小节也不另起标题。不主动提议资产：需要聚合、窗口函数或多步计算才能稳定回答的需求，写下能写的规则，并提示用户可以把相关问题加入"问数优化"；用户明确要求建视图或查询模板时，调用 propose_modeling_change（kind=view，explicit=true），不写 views/。改动影响已有试问问题时，按"试问 SQL"要求同步更新问题文件。回复直接回答，避免技术细节。
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
        if (ModelingTurnPurpose.ASSET.equals(purpose)) return ASSET;
        if (ModelingTurnPurpose.COACH.equals(purpose)) return COACH;
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
