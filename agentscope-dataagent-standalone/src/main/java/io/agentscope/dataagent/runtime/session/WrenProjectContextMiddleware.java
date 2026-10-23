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
package io.agentscope.dataagent.runtime.session;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Injects project identity and independent SQL policy; schema stays with official tools. */
public final class WrenProjectContextMiddleware implements HarnessRuntimeMiddleware {
    private final WrenProjectAccess access;
    private final io.agentscope.dataagent.dataset.FactQueryWindowPolicy windows;
    private final DatasetRepository datasets;
    private final Clock clock;

    public WrenProjectContextMiddleware(WrenProjectAccess access) {
        this(access, null);
    }

    public WrenProjectContextMiddleware(
            WrenProjectAccess access,
            io.agentscope.dataagent.dataset.FactQueryWindowPolicy windows) {
        this(access, windows, null);
    }

    public WrenProjectContextMiddleware(
            WrenProjectAccess access,
            io.agentscope.dataagent.dataset.FactQueryWindowPolicy windows,
            DatasetRepository datasets) {
        this(access, windows, datasets, Clock.systemDefaultZone());
    }

    WrenProjectContextMiddleware(
            WrenProjectAccess access,
            io.agentscope.dataagent.dataset.FactQueryWindowPolicy windows,
            DatasetRepository datasets,
            Clock clock) {
        this.access = access;
        this.windows = windows;
        this.datasets = datasets;
        this.clock = clock;
    }

    /**
     * Uploaded tables are small and carry no time gate, so official skill probing is allowed;
     * every other origin (external datasource, manual, unknown) stays probe-free.
     */
    static String probeBoundary(List<DatasetEntity> tables) {
        List<String> uploaded = new ArrayList<>();
        List<String> guarded = new ArrayList<>();
        for (DatasetEntity table : tables) {
            ("upload".equals(table.getOrigin()) ? uploaded : guarded).add(table.getName());
        }
        return "\n\n# 表来源与探查边界（按当前知识库运行时生成）\n"
                + "上传表："
                + (uploaded.isEmpty() ? "无" : String.join("、", uploaded))
                + "\n外部数据源及其他来源表："
                + (guarded.isEmpty() ? "无" : String.join("、", guarded))
                + "\n上传表数据量有限且不受时间门禁约束，只允许两类按用途限定的探查："
                + "每张事实表一次时间列 MIN/MAX，用于选择样例日期；每条候选关系一次合并查询，同时得出被连接侧键的全表唯一性与两侧匹配率。"
                + "维度候选取值已在采样阶段提取，不执行 SELECT DISTINCT 或取值分布统计；不导出明细行，不统计自由文本或高基数列分布，不重复计算同一统计量。"
                + "一般每张上传表不超过 2 条探查查询，能合并的统计写在同一条 SQL 中。"
                + "发现复合值、脏数据等数据问题时写入列描述或 caveat 说明，不连续编写查询去量化它。"
                + "工具层硬性限制：每轮建模最多执行 "
                + io.agentscope.dataagent.tools.data.OfficialWrenToolkit.MAX_QUERIES_PER_TURN
                + " 条 query（探查与样例验证合计），禁止 CROSS JOIN 和 FROM 后逗号并列多表的笛卡尔积写法。外部数据源及其他来源表在基础建模中不执行任何"
                + " query（工具会直接拒绝），只复用采样资料和元数据；字段同名或同属一套编码的候选关系用 request_user_confirmation"
                + " 请用户确定（每条关系一题，选项写明两侧列与 join_type），确定后写入 relationships.yml；无法验证的唯一性、空值写为未确认并记入"
                + " caveat。只有用户提交问题后的生成、重试或修复问题 SQL"
                + " 的轮次可以查询外部表，且必须带明确日期条件并通过工具时间门禁，不探查极值、不换日期找数据。未出现在以上列表中的表按外部数据源表处理。建模时本段的逐表探查边界优先于上文通用采样约束中的探查限制。\n";
    }

    /**
     * spec092: once a version is published, tables added later are modeled on their own; existing
     * models, relationships and rules stay as reviewed unless the user asks otherwise.
     */
    /**
     * The draft's modeling files, so the assistant reads what it needs in one round instead of
     * listing directories turn by turn (the 2026-10-22 run spent six model rounds doing that).
     */
    static String projectFiles(java.nio.file.Path root) {
        if (root == null || !java.nio.file.Files.isDirectory(root)) return "";
        java.util.List<String> files = new java.util.ArrayList<>();
        try (var walk = java.nio.file.Files.walk(root)) {
            walk.filter(p -> java.nio.file.Files.isRegularFile(p))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(
                            p ->
                                    p.matches("models/[^/]+/.+")
                                            || p.equals("relationships.yml")
                                            || p.equals("wren_project.yml")
                                            || p.matches(
                                                    "knowledge/(rules|caveats|glossary|metrics|questions)/[^/]+")
                                            || p.equals("knowledge/knowledge.yml")
                                            || p.matches("raw/docs/[^/]+"))
                    .filter(p -> !p.endsWith(".gitkeep"))
                    .sorted()
                    .limit(200)
                    .forEach(files::add);
        } catch (java.io.IOException e) {
            return "";
        }
        if (files.isEmpty()) return "";
        return "\n\n# 当前工程文件清单\n"
                + String.join("\n", files)
                + "\n以上为草稿工程中的建模文件（相对 /project），无需再 list_files 或 glob 查找；"
                + "需要的文件在同一轮中一次性并行 read_file。\n";
    }

    static String newTables(java.nio.file.Path root) {
        if (root == null) return "";
        java.nio.file.Path published = root.getParent().resolve("published/models");
        if (!java.nio.file.Files.isDirectory(published)) return "";
        java.util.List<String> added = new java.util.ArrayList<>();
        try (var models = java.nio.file.Files.list(root.resolve("models"))) {
            for (var model : models.filter(java.nio.file.Files::isDirectory).sorted().toList()) {
                String name = model.getFileName().toString();
                if (!java.nio.file.Files.isDirectory(published.resolve(name))) added.add(name);
            }
        } catch (java.io.IOException e) {
            return "";
        }
        if (added.isEmpty()) return "";
        return "\n# 本次新增的表（草稿中有、已发布版本中没有）\n"
                + String.join("、", added)
                + "\n自动建模只为这些表补充描述和与它们相关的口径；已发布的模型、关系和规则保持不变，除非用户明确要求修改。\n";
    }

    /** Reports the compiled-MDL state so the agent builds before its first query-class command. */
    static String buildState(java.nio.file.Path root) {
        boolean compiled =
                root != null && java.nio.file.Files.isRegularFile(root.resolve("target/mdl.json"));
        return "\n# 工程编译状态\n"
                + (compiled
                        ? "已编译：target/mdl.json 存在；文件修改后按需重新执行 context build。\n"
                        : "尚未编译：target/mdl.json 不存在。首次执行 query、dry-plan、dry-run 或 memory describe"
                                + " 时平台会自动先执行 context build。\n");
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.fromCallable(
                        () -> {
                            var project = access.resolve(context);
                            String probeContext =
                                    datasets == null
                                            ? ""
                                            : probeBoundary(
                                                    datasets.findByGroupId(project.groupId()));
                            String windowContext = "";
                            if (windows != null) {
                                var configured = windows.rules(project.groupId());
                                if (!configured.isEmpty())
                                    windowContext =
                                            "\n\n# 已保存的事实表 SQL 时间策略（独立数据库配置）\n"
                                                    + new com.fasterxml.jackson.databind
                                                                    .ObjectMapper()
                                                            .writeValueAsString(configured)
                                                    + "\n"
                                                    + "这些是数据库中的动态执行策略，不是业务定义或默认起止日期；禁止把具体窗口天数复制到MDL、字段/模型描述或业务规则，只保留明确日期、小窗口查询的通用建议。建模可自行选择有限样例日期，无需用户确认；生成前参考各表限制，确保问题文字与SQL的日期、指标和已确认过滤一致，不探查日期找数据。不缩短用户问题、历史问答或固定业务周期来通过门禁，不能验证时保留定义并说明。工具执行前仍会独立校验；被拒绝后停止本轮SQL尝试，不要求用户替技术样例选择日期。SQL实际执行成功但为空可视为执行验证成功，不追加查询；编译或dry-plan不能代替实际执行验证。"
                                                    + io.agentscope.dataagent.dataset
                                                            .TemporalCondition.describe(
                                                            project.root(), configured);
                            }
                            return (prompt == null ? "" : prompt)
                                    + "\n\n# 当前已授权工程\n知识库："
                                    + project.name()
                                    + "\n"
                                    + "草稿工程根目录：/project\n"
                                    + "用户已在页面选择此知识库。官方技能需要确认项目时，只能确认此工程，不能输入其他宿主路径。业务文档（如已上传）位于"
                                    + " raw/docs/ 目录（可能有多份，只读）；可选问题表格位于 knowledge/questions/。"
                                    + "先加载官方技能，再按其要求读取工程；当前工程为草稿，你的修改直接写入草稿，不会逐项弹出确认，由用户在发布前统一审阅，页面发布后才用于正式问数。没有业务文档依据、也不是用户明确要求的口径（推断的过滤条件、主键、关系基数等）不写入"
                                    + " knowledge/rules/：会改变统计结果的歧义用 request_user_confirmation"
                                    + " 提问，其余写入 knowledge/caveats/ 说明；规则所注依据必须能在文档原文中找到。\n\n"
                                    + io.agentscope.dataagent.dataset.DatasetSampleDocuments.POLICY
                                    + WrenQueryContextMiddleware.serviceTime(clock)
                                    + buildState(project.root())
                                    + newTables(project.root())
                                    + projectFiles(project.root())
                                    + probeContext
                                    + windowContext;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
