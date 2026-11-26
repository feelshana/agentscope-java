package io.agentscope.dataagent.dataset;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * spec149 P7-3: a caliber list drafted from documents copies them. Words a person reads as facts
 * (metric names, units, wordings, business and audience) must appear in the declaring documents,
 * and every column the documents name for selecting rows must be used by the list. 2026-11-25: a
 * draft named an undefined metric "总订单数" with unit "笔" and dropped channel and payment columns
 * the document listed, so one result met several rows. Lists the user changed in chat are not
 * checked here; their words are the user's own.
 */
public final class CaliberDocumentCheck {
    /** A backticked name, also as the start of a condition such as `channel_type='全部'`. */
    private static final Pattern BACKTICKED = Pattern.compile("`([A-Za-z_][A-Za-z0-9_]*)");

    private CaliberDocumentCheck() {}

    /** Problems in Chinese for the drafting assistant; empty when the list copies the documents. */
    public static List<String> problems(
            CaliberList list, String documents, Set<String> modelColumns) {
        List<String> out = new ArrayList<>();
        if (list == null || documents == null || documents.isBlank()) return out;
        String text = documents;
        CaliberList.Scenario s = list.scenario();
        List<String> invented = new ArrayList<>();
        for (CaliberList.Measure m : s.measures()) {
            String where = "度量 " + m.column();
            if (m.label() != null
                    && !m.label().equalsIgnoreCase(m.column())
                    && !text.contains(m.label()))
                invented.add(where + " 的 label「" + m.label() + "」");
            if (m.unit() != null && !text.contains(m.unit()))
                invented.add(where + " 的 unit「" + m.unit() + "」");
            for (String w : m.synonyms())
                if (!text.contains(w)) invented.add(where + " 的说法「" + w + "」");
        }
        key(s.business(), "business", text, invented);
        key(s.audience(), "audience", text, invented);
        for (CaliberList.Caliber c : list.calibers()) {
            if (c.unit() != null && !text.contains(c.unit()))
                invented.add("口径「" + c.name() + "」的 unit「" + c.unit() + "」");
            for (String w : c.synonyms())
                if (!text.contains(w)) invented.add("口径「" + c.name() + "」的说法「" + w + "」");
        }
        if (!invented.isEmpty())
            out.add(
                    "以下内容在业务文档中找不到，口径清单只照抄文档："
                            + String.join("、", invented)
                            + "。文档没有定义的度量 label 写列名、description 写\"含义文档未定义，待业务确认\"，不写 unit"
                            + " 和说法；业务、统计对象和说法只写文档原文。");

        Set<String> used = new LinkedHashSet<>();
        s.measures().forEach(m -> used.add(m.column().toLowerCase(Locale.ROOT)));
        if (s.time() != null) used.add(s.time().column().toLowerCase(Locale.ROOT));
        s.scope().forEach(d -> used.add(d.column().toLowerCase(Locale.ROOT)));
        s.fixedScope().keySet().forEach(k -> used.add(k.toLowerCase(Locale.ROOT)));
        list.calibers()
                .forEach(
                        c -> c.where().keySet().forEach(k -> used.add(k.toLowerCase(Locale.ROOT))));
        Set<String> missing = new LinkedHashSet<>();
        Matcher named = BACKTICKED.matcher(text);
        while (named.find()) {
            String column = named.group(1);
            String key = column.toLowerCase(Locale.ROOT);
            if (modelColumns.contains(key) && !used.contains(key)) missing.add(column);
        }
        if (!missing.isEmpty())
            out.add(
                    "文档写到的列 "
                            + String.join("、", missing)
                            + " 没有出现在口径条件、scope 或 fixed_scope 中：汇总值与明细值并存的列（如渠道、支付方式）写进 scope"
                            + " 并照抄文档的汇总值和默认值；所有口径都只取一个值的列写进 fixed_scope。漏写会让一个结果对应多行。");
        return out;
    }

    private static void key(CaliberList.Key key, String field, String text, List<String> out) {
        if (key == null) return;
        for (String word : key.all()) if (!text.contains(word)) out.add(field + " 的「" + word + "」");
    }
}
