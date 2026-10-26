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
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static String contextFor(List<DatasetEntity> tables) {
        return probeBoundary(tables) + relationCandidates(tables);
    }

    /**
     * Join candidates from column names alone ({@code operator_id} → {@code sys_operator.id}).
     * Types and sampled value formats are deliberately ignored: samples may hold dirty rows and
     * the database converts types in joins, so the modeler decides, not the sample.
     */
    static String relationCandidates(List<DatasetEntity> tables) {
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        for (DatasetEntity table : tables) columns.put(table.getName(), includedColumns(table));
        List<String> lines = new ArrayList<>();
        for (var source : columns.entrySet()) {
            for (String column : source.getValue()) {
                String lower = column.toLowerCase(Locale.ROOT);
                if (!lower.endsWith("_id") || lower.equals("_id")) continue;
                String prefix = lower.substring(0, lower.length() - 3);
                for (var target : columns.entrySet()) {
                    if (target.getKey().equals(source.getKey())) continue;
                    String name = target.getKey().toLowerCase(Locale.ROOT);
                    if (!name.equals(prefix)
                            && !name.equals(prefix + "s")
                            && !name.endsWith("_" + prefix)) continue;
                    String key =
                            target.getValue().stream()
                                    .filter(
                                            c ->
                                                    c.equalsIgnoreCase("id")
                                                            || c.equalsIgnoreCase(column))
                                    .findFirst()
                                    .orElse(null);
                    if (key != null)
                        lines.add(
                                "- "
                                        + source.getKey()
                                        + "."
                                        + column
                                        + " → "
                                        + target.getKey()
                                        + "."
                                        + key);
                }
            }
        }
        if (lines.isEmpty()) return "";
        return "\n\n# 按字段命名生成的候选关系（平台生成）\n"
                + String.join("\n", lines)
                + "\n"
                + "以上候选只按字段命名得出，与列类型、采样值格式无关：采样可能包含脏数据，类型不同由数据库关联时转换。不得以类型不一致或采样值格式不匹配为由否定、改写或跳过；每条直接用"
                + " request_user_confirmation（topic=relationship）询问用户，第一个选项就是该关联（join_type 建议"
                + " MANY_TO_ONE），类型或格式差异只写进 basis 作为提示。\n";
    }

    private static Set<String> includedColumns(DatasetEntity table) {
        String json = table.getColumnSchemaJson();
        if (json == null || json.isBlank()) return Set.of();
        try {
            List<ColumnSchema> parsed =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(
                                    json,
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            List<ColumnSchema>>() {});
            Set<String> names = new LinkedHashSet<>();
            for (ColumnSchema column : parsed)
                if (column.includedInModel()) names.add(column.name());
            return names;
        } catch (java.io.IOException e) {
            return Set.of();
        }
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
                + "\n未出现在以上列表中的表按外部数据源表处理。外部数据源及其他来源表在基础建模中不执行任何 query（工具会直接拒绝），"
                + "不为补齐枚举、基数、极值或画像执行 SELECT DISTINCT、COUNT、MIN/MAX、GROUP BY，只复用采样资料和元数据；"
                + "试问 SQL 也不查询数据，只用 dry-plan 检查。"
                + "\n上传表数据量有限且不受时间门禁约束，只允许两类探查：每张事实表一次时间列 MIN/MAX，用于选择样例日期；"
                + "每条候选关系一次合并查询，同时得出被连接侧键的全表唯一性与两侧匹配率。维度候选取值已在采样中提取，不执行 SELECT DISTINCT 或分布统计，"
                + "不导出明细行，不重复统计；不得把时间范围扩大到 100 年绕过规则。每轮建模最多执行 "
                + io.agentscope.dataagent.tools.data.OfficialWrenToolkit.MAX_QUERIES_PER_TURN
                + " 条 query；需要的探查在同一轮一次性发起，不逐条等待。发现复合值、脏数据时写入列描述或 caveat，不连续查询去量化它。候选关系用"
                + " request_user_confirmation 请用户确定（每条关系一题，选项写明两侧列与 join_type），确定后写入"
                + " relationships.yml；无法验证的唯一性、空值写为未确认并记入 caveat。\n";
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
        // 2026-10-26 log: without these facts the model spent ~45 s listing empty folders and
        // globbing for sampling files that do not exist.
        java.util.List<String> empty = new java.util.ArrayList<>();
        for (String dir :
                java.util.List.of("rules", "caveats", "glossary", "metrics", "sql", "questions"))
            if (files.stream().noneMatch(f -> f.startsWith("knowledge/" + dir + "/")))
                empty.add("knowledge/" + dir);
        boolean documents = files.stream().anyMatch(f -> f.startsWith("raw/docs/"));
        return "\n\n# 当前工程文件清单\n"
                + String.join("\n", files)
                + "\n以上为草稿工程中的全部建模文件（相对 /project），无需再 list_files、glob 或 grep 查找；"
                + "需要的文件在同一轮中一次性并行 read_file。"
                + (empty.isEmpty() ? "" : "\n空目录（无需查看）：" + String.join("、", empty) + "。")
                + (documents ? "" : "\n没有业务文档：raw/docs/ 不存在或为空。")
                + "\n没有单独的采样文件：采样结论已写在各模型的列描述和 properties（含 temporal_columns）中。\n";
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
                        ? "已编译：target/mdl.json 存在；文件修改后无需手动 build，查询类命令前平台会自动重新编译。\n"
                        : "尚未编译：target/mdl.json 不存在。首次执行 query、dry-plan、dry-run 或 memory describe"
                                + " 时平台会自动先执行 context build。\n");
    }

    /** Same as querying: the English official skill pulls step narration into English. */
    @Override
    public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext context,
            io.agentscope.core.middleware.ModelCallInput input,
            java.util.function.Function<
                            io.agentscope.core.middleware.ModelCallInput,
                            reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent>>
                    next) {
        List<io.agentscope.core.message.Msg> messages = new ArrayList<>(input.messages());
        messages.add(
                io.agentscope.core.message.Msg.builder()
                        .role(io.agentscope.core.message.MsgRole.SYSTEM)
                        .textContent(WrenQueryContextMiddleware.LANGUAGE_REMINDER)
                        .build());
        return next.apply(
                new io.agentscope.core.middleware.ModelCallInput(
                        messages, input.tools(), input.options(), input.model()));
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.fromCallable(
                        () -> {
                            var project = access.resolve(context);
                            var declared =
                                    context == null
                                            ? null
                                            : context.get(
                                                    io.agentscope.dataagent.dataset
                                                            .ModelingTurnPurpose.class);
                            String purpose = declared == null ? null : declared.value();
                            boolean modelsTables = ModelingTurnRules.modelsTables(purpose);
                            String tableContext =
                                    datasets == null || !modelsTables
                                            ? ""
                                            : contextFor(datasets.findByGroupId(project.groupId()));
                            String windowContext = "";
                            if (windows != null && ModelingTurnRules.writesSql(purpose)) {
                                var configured = windows.rules(project.groupId());
                                if (!configured.isEmpty())
                                    windowContext =
                                            "\n\n# 已保存的事实表 SQL 时间策略（独立数据库配置）\n"
                                                    + new com.fasterxml.jackson.databind
                                                                    .ObjectMapper()
                                                            .writeValueAsString(configured)
                                                    + "\n"
                                                    + "这些是数据库中的动态执行策略，不是业务定义或默认起止日期；禁止把具体窗口天数复制到MDL、描述或业务规则。"
                                                    + "建模可自行选择有限样例日期，无需用户确认，生成前参考各表限制；工具执行前仍会独立校验。"
                                                    + io.agentscope.dataagent.dataset
                                                            .TemporalCondition.describe(
                                                            project.root(), configured);
                            }
                            return (prompt == null ? "" : prompt)
                                    + "\n\n# 当前已授权工程\n知识库："
                                    + project.name()
                                    + "\n"
                                    + "草稿工程根目录：/project。用户已在页面选择此知识库；官方技能需要确认项目时只能确认此工程，不能输入其他宿主路径。业务文档（如已上传）位于"
                                    + " raw/docs/（可能有多份，只读）；问题文件位于"
                                    + " knowledge/questions/。修改直接写入草稿，页面发布后才用于正式问数。\n\n"
                                    + io.agentscope.dataagent.dataset.DatasetSampleDocuments.POLICY
                                    + WrenQueryContextMiddleware.serviceTime(clock)
                                    + buildState(project.root())
                                    + (modelsTables ? newTables(project.root()) : "")
                                    + projectFiles(project.root())
                                    + tableContext
                                    + windowContext
                                    + "\n\n"
                                    + ModelingTurnRules.forPurpose(purpose);
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
