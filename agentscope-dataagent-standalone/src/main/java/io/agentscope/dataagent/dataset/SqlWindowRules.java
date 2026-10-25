package io.agentscope.dataagent.dataset;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides the per-table time window of plain SQL without a model call. Only shapes whose window is
 * certain are decided: one SELECT, each registered table used once, and the time column bounded by
 * literal comparisons in a top-level AND of the WHERE clause (the forms the platform tells the
 * model to write, see {@link TemporalCondition}). Anything else returns empty and goes to the
 * model gate, so this never widens what the gate allows.
 */
final class SqlWindowRules {
    private static final String ID = "[\\p{L}\\p{N}_]+";
    private static final Pattern TABLE_REF =
            Pattern.compile(
                    "(?i)\\b(FROM|JOIN)\\s+((?:"
                            + ID
                            + "\\.)*("
                            + ID
                            + "))(?:\\s+(?:AS\\s+)?("
                            + ID
                            + "))?");
    private static final java.util.Set<String> NOT_ALIAS =
            java.util.Set.of(
                    "WHERE", "JOIN", "ON", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS",
                    "GROUP", "ORDER", "LIMIT", "HAVING", "USING", "NATURAL", "UNION", "WINDOW");
    private static final Pattern LITERAL =
            Pattern.compile("(?:(?i:DATE|TIMESTAMP)\\s+)?(__L\\d+__|\\d{8}(?:\\d{6})?)");
    private static final Pattern WRAPPER =
            Pattern.compile(
                    "(?i)^(?:STR_TO_DATE|TO_TIMESTAMP|TO_DATE|DATE|TIMESTAMP)\\s*\\((.*)\\)$");
    private static final Pattern DATE_TEXT =
            Pattern.compile(
                    "^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?:["
                            + " T](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?)?$");

    /** Relative dates and branching need real interpretation; the model gate handles them. */
    private static final Pattern UNDECIDABLE =
            Pattern.compile(
                    "(?i)\\b(UNION|WITH|INTERVAL|NOW|CURRENT_DATE|CURRENT_TIMESTAMP|CURDATE|SYSDATE|DATE_SUB|DATE_ADD|ADDDATE|SUBDATE|DATEADD)\\b");

    private SqlWindowRules() {}

    private record Ref(SqlWindowGate.Rule rule, String alias) {}

    private record Bound(LocalDateTime at, boolean dateOnly) {}

    static Optional<SqlWindowGate.Decision> evaluate(String sql, List<SqlWindowGate.Rule> rules) {
        if (sql == null || rules.isEmpty()) return Optional.empty();
        List<String> literals = new ArrayList<>();
        String code = mask(sql, literals);
        if (code == null) return Optional.empty();
        String upper = code.toUpperCase(Locale.ROOT);

        Map<String, SqlWindowGate.Rule> byName = new LinkedHashMap<>();
        for (SqlWindowGate.Rule rule : rules)
            byName.put(rule.table().toLowerCase(Locale.ROOT), rule);
        List<Ref> refs = new ArrayList<>();
        Map<String, Integer> refCount = new LinkedHashMap<>();
        Matcher m = TABLE_REF.matcher(code);
        while (m.find()) {
            SqlWindowGate.Rule rule = byName.get(m.group(3).toLowerCase(Locale.ROOT));
            if (rule == null) continue;
            String alias = m.group(4);
            if (alias != null && NOT_ALIAS.contains(alias.toUpperCase(Locale.ROOT))) alias = null;
            refs.add(new Ref(rule, alias == null ? null : alias.toLowerCase(Locale.ROOT)));
            refCount.merge(rule.table(), 1, Integer::sum);
        }
        // A registered name used other than as a FROM/JOIN target or a column qualifier is a shape
        // this checker does not understand.
        for (var entry : byName.entrySet()) {
            Matcher uses =
                    Pattern.compile(
                                    "(?i)(?<![\\p{L}\\p{N}_])"
                                            + Pattern.quote(entry.getKey())
                                            + "(?![\\p{L}\\p{N}_])(?!\\s*\\.)")
                            .matcher(code);
            int count = 0;
            while (uses.find()) count++;
            if (count != refCount.getOrDefault(entry.getValue().table(), 0))
                return Optional.empty();
        }
        if (refs.isEmpty())
            return Optional.of(new SqlWindowGate.Decision(true, List.of(), "规则判断：SQL未使用已登记的受限事实表"));
        if (count(upper, "\\bSELECT\\b") != 1 || UNDECIDABLE.matcher(code).find())
            return Optional.empty();
        if (refCount.values().stream().anyMatch(n -> n > 1)) return Optional.empty();

        String where = whereClause(code);
        String joinConditions = joinConditions(code);
        List<String> conjuncts = where == null ? List.of() : conjuncts(where);
        if (conjuncts == null) return Optional.empty();

        List<SqlWindowGate.TableCheck> checks = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        boolean allowed = true;
        for (Ref ref : refs) {
            SqlWindowGate.TableCheck best = null;
            String bestText = null;
            for (String column : ref.rule().timeColumns()) {
                Pattern columnRef = columnRef(ref, refs.size() == 1, column);
                if (joinConditions != null && columnRef.matcher(joinConditions).find())
                    return Optional.empty();
                Bound lower = null;
                Bound upperBound = null;
                for (String conjunct : conjuncts) {
                    if (!columnRef.matcher(conjunct).find()) continue;
                    Bound[] range = range(conjunct, columnRef, literals);
                    if (range == null) return Optional.empty();
                    if (range[0] != null && (lower == null || range[0].at().isAfter(lower.at())))
                        lower = range[0];
                    if (range[1] != null
                            && (upperBound == null || end(range[1]).isBefore(end(upperBound))))
                        upperBound = range[1];
                }
                SqlWindowGate.TableCheck check;
                String text;
                if (lower == null || upperBound == null) {
                    check =
                            new SqlWindowGate.TableCheck(
                                    ref.rule().table(),
                                    column,
                                    false,
                                    null,
                                    ref.rule().maxWindowDays());
                    text = ref.rule().table() + " 缺少 " + column + " 的完整日期范围";
                } else {
                    double days =
                            Math.max(
                                    0,
                                    ChronoUnit.SECONDS.between(lower.at(), end(upperBound))
                                            / 86400.0);
                    days = Math.round(days * 1000) / 1000.0;
                    check =
                            new SqlWindowGate.TableCheck(
                                    ref.rule().table(),
                                    column,
                                    true,
                                    days,
                                    ref.rule().maxWindowDays());
                    text =
                            ref.rule().table()
                                    + "."
                                    + column
                                    + " 范围 "
                                    + lower.at().toLocalDate()
                                    + " 起共 "
                                    + format(days)
                                    + " 天"
                                    + (days <= ref.rule().maxWindowDays()
                                            ? "，未超过 " + ref.rule().maxWindowDays() + " 天"
                                            : "，超过上限 " + ref.rule().maxWindowDays() + " 天");
                }
                boolean passes =
                        check.hasCondition() && check.windowDays() <= check.maxWindowDays();
                if (best == null || passes) {
                    best = check;
                    bestText = text;
                }
                if (passes) break;
            }
            checks.add(best);
            reasons.add(bestText);
            allowed &= best.hasCondition() && best.windowDays() <= best.maxWindowDays();
        }
        return Optional.of(
                new SqlWindowGate.Decision(allowed, checks, "规则判断：" + String.join("；", reasons)));
    }

    /** Strips comments and quoted identifiers; string literals become __L<n>__ placeholders. */
    private static String mask(String sql, List<String> literals) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                StringBuilder value = new StringBuilder();
                int j = i + 1;
                for (; j < sql.length(); j++) {
                    if (sql.charAt(j) == '\'') {
                        if (j + 1 < sql.length() && sql.charAt(j + 1) == '\'') {
                            value.append('\'');
                            j++;
                            continue;
                        }
                        break;
                    }
                    value.append(sql.charAt(j));
                }
                if (j >= sql.length()) return null;
                out.append(" __L").append(literals.size()).append("__ ");
                literals.add(value.toString());
                i = j;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
                out.append(' ');
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                if (close < 0) return null;
                i = close + 1;
                out.append(' ');
            } else if (c == '`' || c == '"') {
                // Quoted identifiers compare the same unquoted.
            } else out.append(c);
        }
        return out.toString();
    }

    private static int count(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** The WHERE clause of the single SELECT, up to its first trailing clause. */
    private static String whereClause(String code) {
        Matcher where = Pattern.compile("(?i)\\bWHERE\\b").matcher(code);
        if (!where.find()) return null;
        String rest = code.substring(where.end());
        Matcher end =
                Pattern.compile(
                                "(?i)\\b(GROUP\\s+BY|ORDER\\s+BY|HAVING|LIMIT|OFFSET|FETCH|WINDOW)\\b")
                        .matcher(rest);
        return end.find() ? rest.substring(0, end.start()) : rest;
    }

    /** All JOIN ... ON conditions; a time bound there is not decided here. */
    private static String joinConditions(String code) {
        Matcher on =
                Pattern.compile(
                                "(?i)\\bON\\b(.*?)(?=\\b(?:JOIN|WHERE|GROUP|ORDER|HAVING|LIMIT|LEFT|RIGHT|INNER|FULL|CROSS)\\b|$)",
                                Pattern.DOTALL)
                        .matcher(code);
        StringBuilder all = new StringBuilder();
        while (on.find()) all.append(on.group(1)).append(' ');
        return all.length() == 0 ? null : all.toString();
    }

    /** Top-level AND parts; null when the clause has a top-level OR or unbalanced parentheses. */
    private static List<String> conjuncts(String where) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        String upper = where.toUpperCase(Locale.ROOT);
        for (int i = 0; i < where.length(); i++) {
            char c = where.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth < 0) return null;
            if (depth == 0 && keywordAt(upper, i, "OR")) return null;
            if (depth == 0 && keywordAt(upper, i, "AND")) {
                String before = where.substring(start, i);
                // BETWEEN x AND y keeps its AND.
                if (!Pattern.compile("(?i)\\bBETWEEN\\s+\\S+\\s*$").matcher(before).find()) {
                    parts.add(before.trim());
                    start = i + 3;
                }
            }
        }
        if (depth != 0) return null;
        parts.add(where.substring(start).trim());
        return parts;
    }

    private static boolean keywordAt(String upper, int i, String word) {
        if (!upper.startsWith(word, i)) return false;
        boolean before =
                i == 0
                        || !Character.isLetterOrDigit(upper.charAt(i - 1))
                                && upper.charAt(i - 1) != '_';
        int after = i + word.length();
        return before
                && (after >= upper.length()
                        || !Character.isLetterOrDigit(upper.charAt(after))
                                && upper.charAt(after) != '_');
    }

    private static Pattern columnRef(Ref ref, boolean bareAllowed, String column) {
        List<String> qualifiers = new ArrayList<>();
        qualifiers.add(Pattern.quote(ref.rule().table()));
        if (ref.alias() != null) qualifiers.add(Pattern.quote(ref.alias()));
        String qualified = "(?:" + String.join("|", qualifiers) + ")\\s*\\.\\s*";
        String prefix = bareAllowed ? "(?:" + qualified + "|(?<![\\p{L}\\p{N}_.]))" : qualified;
        return Pattern.compile("(?i)" + prefix + Pattern.quote(column) + "(?![\\p{L}\\p{N}_])");
    }

    /**
     * [lower, upper] bounds of one conjunct on the time column, either may be null; null when the
     * conjunct is not a plain literal comparison of that column.
     */
    private static Bound[] range(String conjunct, Pattern columnRef, List<String> literals) {
        String text = conjunct.trim();
        while (text.startsWith("(")
                && text.endsWith(")")
                && balanced(text.substring(1, text.length() - 1)))
            text = text.substring(1, text.length() - 1).trim();
        Matcher between =
                Pattern.compile("(?is)^(.+?)\\s+BETWEEN\\s+(.+?)\\s+AND\\s+(.+)$").matcher(text);
        if (between.matches()) {
            if (!isColumn(between.group(1), columnRef)) return null;
            Bound low = literal(between.group(2), literals);
            Bound high = literal(between.group(3), literals);
            return low == null || high == null ? null : new Bound[] {low, inclusive(high)};
        }
        Matcher cmp = Pattern.compile("(?s)^(.+?)\\s*(>=|<=|<>|!=|=|>|<)\\s*(.+)$").matcher(text);
        if (!cmp.matches()) return null;
        String left = cmp.group(1);
        String op = cmp.group(2);
        String right = cmp.group(3);
        if (!isColumn(left, columnRef)) {
            if (!isColumn(right, columnRef)) return null;
            String swap = left;
            left = right;
            right = swap;
            op =
                    switch (op) {
                        case ">=" -> "<=";
                        case "<=" -> ">=";
                        case ">" -> "<";
                        case "<" -> ">";
                        default -> op;
                    };
        }
        Bound value = literal(right, literals);
        if (value == null) return null;
        return switch (op) {
            case ">=", ">" -> new Bound[] {value, null};
            case "<" -> new Bound[] {null, value};
            case "<=" -> new Bound[] {null, inclusive(value)};
            case "=" -> new Bound[] {value, inclusive(value)};
            default -> null;
        };
    }

    private static boolean balanced(String text) {
        int depth = 0;
        for (char c : text.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')' && --depth < 0) return false;
        }
        return depth == 0;
    }

    /** The column itself, or the column inside one date-parsing wrapper with a format literal. */
    private static boolean isColumn(String expression, Pattern columnRef) {
        String text = expression.trim();
        Matcher wrapped = WRAPPER.matcher(text);
        if (wrapped.matches())
            text = wrapped.group(1).replaceFirst("\\s*,\\s*__L\\d+__\\s*$", "").trim();
        Matcher column = columnRef.matcher(text);
        return column.find() && column.start() == 0 && column.end() == text.length();
    }

    private static Bound literal(String expression, List<String> literals) {
        Matcher m = LITERAL.matcher(expression.trim());
        if (!m.matches()) return null;
        String token = m.group(1);
        String value =
                token.startsWith("__L")
                        ? literals.get(Integer.parseInt(token.substring(3, token.length() - 2)))
                        : token;
        value = value.trim();
        try {
            if (value.matches("\\d{8}"))
                return new Bound(
                        LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                                .atStartOfDay(),
                        true);
            if (value.matches("\\d{14}"))
                return new Bound(
                        LocalDateTime.parse(
                                value,
                                java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
                        false);
            Matcher d = DATE_TEXT.matcher(value);
            if (!d.matches()) return null;
            LocalDate date =
                    LocalDate.of(
                            Integer.parseInt(d.group(1)),
                            Integer.parseInt(d.group(2)),
                            Integer.parseInt(d.group(3)));
            if (d.group(4) == null) return new Bound(date.atStartOfDay(), true);
            return new Bound(
                    date.atTime(
                            Integer.parseInt(d.group(4)),
                            Integer.parseInt(d.group(5)),
                            d.group(6) == null ? 0 : Integer.parseInt(d.group(6))),
                    false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** An inclusive date-only upper bound covers that whole day. */
    private static Bound inclusive(Bound bound) {
        return bound.dateOnly() ? new Bound(bound.at().plusDays(1), false) : bound;
    }

    private static LocalDateTime end(Bound bound) {
        return bound.at();
    }

    private static String format(double days) {
        return days == Math.rint(days) ? String.valueOf((long) days) : String.valueOf(days);
    }
}
