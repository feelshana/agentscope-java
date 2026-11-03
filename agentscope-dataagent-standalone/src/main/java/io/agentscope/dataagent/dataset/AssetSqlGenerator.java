package io.agentscope.dataagent.dataset;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Writes one SQL that answers a verification question from a single semantic asset (spec111).
 * The asset is the thing under test, so it is the only table the model is offered; the result is
 * shown to the developer, who can edit it before anything runs.
 */
@Service
public class AssetSqlGenerator {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String PROMPT =
            """
            你为语义资产写一条验证 SQL，用来回答用户的业务问题。
            只输出一条 SQL，不要解释，不要 Markdown。
            语义资产就是一张表，FROM 必须是资产名本身。不要展开资产的内部逻辑，不引用其他表或模型，只使用给定的输出列。
            资产已有的列直接使用，不要在 SQL 里用 CASE WHEN 重新计算同样的含义，否则验证不到这一列。
            只写只读 SELECT，使用 MySQL 8.0 语法，日期写成常量，不用 NOW()、CURDATE() 等函数。
            如果给了时间列和最大查询天数，必须在 WHERE 中写明时间范围，且不超过最大天数；问题没有日期时，用当前日期之前的最近范围。
            """;

    private final ObjectProvider<Model> models;
    private final Clock clock;

    @Autowired
    public AssetSqlGenerator(ObjectProvider<Model> models) {
        this(models, Clock.systemDefaultZone());
    }

    AssetSqlGenerator(ObjectProvider<Model> models, Clock clock) {
        this.models = models;
        this.clock = clock;
    }

    /** {@code maxDays} is null when the asset reads no limited table. */
    public String generate(
            RuntimeContext context,
            String assetName,
            String description,
            List<String> columns,
            List<String> timeColumns,
            Integer maxDays,
            String question) {
        Model model =
                io.agentscope.dataagent.runtime.model.SwitchingModel.forCall(
                        models.getIfAvailable(), context);
        if (model == null) throw new DatasetException("模型不可用，请手写 SQL 或稍后重试", 503);
        String input =
                "语义资产："
                        + assetName
                        + "\n输出列："
                        + String.join("、", columns)
                        + "\n说明：\n"
                        + (description == null ? "" : description)
                        + (maxDays == null
                                ? ""
                                : "\n时间列：" + String.join("、", timeColumns) + "\n最大查询天数：" + maxDays)
                        + "\n当前日期："
                        + LocalDate.now(clock)
                        + "\n业务问题："
                        + question;
        String sql = call(model, input);
        // The asset is what is being tested, so one nudge is worth more than a failed verification.
        if (!PublishedAssetWindows.names(sql)
                .contains(assetName.toLowerCase(java.util.Locale.ROOT)))
            sql =
                    call(
                            model,
                            input
                                    + "\n上一次的 SQL 没有查询语义资产，已被拒绝：\n"
                                    + sql
                                    + "\n请重写，FROM 必须是 "
                                    + assetName
                                    + "。");
        if (sql.isBlank()) throw new DatasetException("模型没有生成 SQL，请重试或手写", 502);
        return sql;
    }

    private static String call(Model model, String input) {
        String raw =
                model.stream(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.SYSTEM)
                                                .textContent(PROMPT)
                                                .build(),
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent(input)
                                                .build()),
                                List.of(),
                                null)
                        .flatMapIterable(r -> r.getContent() == null ? List.of() : r.getContent())
                        .filter(TextBlock.class::isInstance)
                        .cast(TextBlock.class)
                        .map(TextBlock::getText)
                        .reduce("", String::concat)
                        .timeout(TIMEOUT)
                        .block();
        return clean(raw);
    }

    static String clean(String raw) {
        String text =
                raw == null
                        ? ""
                        : raw.strip()
                                .replaceFirst("(?i)^```(?:sql)?\\s*", "")
                                .replaceFirst("\\s*```$", "")
                                .strip();
        return text.endsWith(";") ? text.substring(0, text.length() - 1).strip() : text;
    }
}
