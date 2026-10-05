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
package io.agentscope.dataagent.dataset;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lexical rewriter that resolves logical model references inside a {@code ref_sql} statement to
 * their physical, schema-qualified table names (specs/035, ADR 0043).
 *
 * <p>Empirical basis — official wren CLI 0.15.0, zero platform code (experiment matrix in
 * {@code target/wren-refsql-verify} plus {@code wren/mdl/cte_rewriter.py}): the engine expands a
 * ref_sql model's body verbatim, and bare table names inside it resolve against the connection's
 * default database instead of the MDL models (MySQL error 1146 at dry-run/query time). Views do
 * not need this rewrite — the engine injects physical CTEs for the models their statement
 * references. Materialising the publish manifest's refSql keeps the workspace file human-friendly
 * (logical names) while the shipped artifact is fully qualified.
 *
 * <p>The scanner is deliberately conservative: strings and comments are blanked on a
 * length-preserving copy, so quoted text or comments can never produce references. Only bare
 * identifiers in FROM/JOIN position are collected (comma-separated FROM lists included);
 * WITH-declared CTE names are excluded; dotted (already qualified) names are never touched;
 * functional sub-clauses such as {@code EXTRACT(YEAR FROM col)} are not table positions.
 */
public final class RefSqlMaterializer {

    private RefSqlMaterializer() {}

    /** One collected reference: the exact source span (quotes included) plus its decoded name. */
    private record Ref(int start, int end, String name) {}

    /** One identifier token with its source span. */
    private record Ident(int start, int end, String name) {}

    /** Words that can never be a bare table alias after a FROM/JOIN item. */
    private static final Set<String> ALIAS_STOP_WORDS =
            Set.of(
                    "ON",
                    "USING",
                    "WHERE",
                    "GROUP",
                    "ORDER",
                    "HAVING",
                    "LIMIT",
                    "OFFSET",
                    "FETCH",
                    "UNION",
                    "INTERSECT",
                    "EXCEPT",
                    "WINDOW",
                    "QUALIFY",
                    "JOIN",
                    "INNER",
                    "LEFT",
                    "RIGHT",
                    "FULL",
                    "CROSS",
                    "NATURAL",
                    "STRAIGHT_JOIN",
                    "SET",
                    "VALUES",
                    "FOR");

    /** Functions whose parenthesised body may contain a sub-clause FROM (never a table). */
    private static final Set<String> FROM_SUBCLAUSE_FUNCTIONS =
            Set.of("EXTRACT", "TRIM", "SUBSTRING", "POSITION", "OVERLAY");

    /**
     * Decoded bare table references in order of first appearance (case preserved,
     * deduplicated case-insensitively).
     */
    public static List<String> extractTableRefs(String sql) {
        List<Ref> refs = scan(sql);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Ref ref : refs) {
            if (seen.add(ref.name().toLowerCase(Locale.ROOT))) {
                out.add(ref.name());
            }
        }
        return out;
    }

    /**
     * Rewrites every bare reference that has a mapping entry to its replacement text; unmapped
     * bare identifiers stay as-is (callers detect them via {@link #extractTableRefs} first).
     * Lookup is exact first, then case-insensitive. Replacements run from the tail so earlier
     * source offsets stay valid.
     */
    public static String materialize(String sql, Map<String, String> physicalByLogical) {
        if (sql == null
                || sql.isEmpty()
                || physicalByLogical == null
                || physicalByLogical.isEmpty()) {
            return sql;
        }
        List<Ref> refs = scan(sql);
        if (refs.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql);
        for (int i = refs.size() - 1; i >= 0; i--) {
            Ref ref = refs.get(i);
            String physical = resolve(physicalByLogical, ref.name());
            if (physical != null) {
                sb.replace(ref.start(), ref.end(), physical);
            }
        }
        return sb.toString();
    }

    /**
     * Resolves one bare reference to its replacement text: exact match first, then
     * case-insensitive; {@code null} when the reference has no mapping.
     */
    public static String resolve(Map<String, String> physicalByLogical, String name) {
        if (physicalByLogical == null || name == null) {
            return null;
        }
        String direct = physicalByLogical.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : physicalByLogical.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Physical reference for a table_reference pair: {@code schema.table}, each part backticked
     * only when it is not a plain SQL identifier. Returns {@code null} when the table is blank.
     */
    public static String physicalName(String schema, String table) {
        if (table == null || table.isBlank()) {
            return null;
        }
        String t = quotePart(table.trim());
        if (schema == null || schema.isBlank()) {
            return t;
        }
        return quotePart(schema.trim()) + "." + t;
    }

    // ------------------------------------------------------------------ scanning

    private static List<Ref> scan(String sql) {
        List<Ref> out = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return out;
        }
        char[] masked = mask(sql);
        Set<String> ctes = cteNames(sql, masked);
        Deque<String> fnStack = new ArrayDeque<>();
        String pendingFn = null;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = masked[i];
            if (c == '"' || c == '`') {
                // Quoted identifier outside FROM/JOIN position: skip so quoted keywords never
                // match. Single-quoted strings were already blanked by the mask.
                i = skipQuoted(sql, i, c);
                pendingFn = null;
                continue;
            }
            if (c == '(') {
                fnStack.push(pendingFn == null ? "" : pendingFn);
                pendingFn = null;
                i++;
                continue;
            }
            if (c == ')') {
                if (!fnStack.isEmpty()) {
                    fnStack.pop();
                }
                pendingFn = null;
                i++;
                continue;
            }
            if (!isIdentStart(c)) {
                if (!Character.isWhitespace(c)) {
                    pendingFn = null;
                }
                i++;
                continue;
            }
            int start = i;
            while (i < n && isIdentChar(masked[i])) {
                i++;
            }
            String word = sql.substring(start, i).toUpperCase(Locale.ROOT);
            int afterWord = skipWs(masked, i);
            boolean parenAfter = afterWord < n && masked[afterWord] == '(';
            if (word.equals("FROM") || word.equals("JOIN")) {
                if (word.equals("FROM") && insideFromSubclause(fnStack)) {
                    pendingFn = null;
                    continue;
                }
                i = parseTableItem(sql, masked, i, ctes, out);
                if (word.equals("FROM")) {
                    while (true) {
                        int k = skipWs(masked, i);
                        if (k >= n || masked[k] != ',') {
                            break;
                        }
                        int itemStart = skipWs(masked, k + 1);
                        int next = parseTableItem(sql, masked, k + 1, ctes, out);
                        if (next <= itemStart) {
                            break;
                        }
                        i = next;
                    }
                }
                pendingFn = null;
                continue;
            }
            pendingFn = parenAfter ? word : null;
        }
        return out;
    }

    /**
     * Parses one table item after FROM/JOIN: a bare or quoted name (collected unless it is a
     * WITH-declared CTE), a dotted name (skipped — already qualified), or a parenthesised
     * subquery (transparent: scanning resumes inside it so nested FROM clauses are still seen).
     * The optional alias is consumed. Returns the index scanning should resume from.
     */
    private static int parseTableItem(
            String sql, char[] m, int start, Set<String> ctes, List<Ref> out) {
        int n = sql.length();
        int i = skipWs(m, start);
        if (i >= n) {
            return i;
        }
        if (m[i] == '(') {
            return i + 1;
        }
        Ident id = readIdent(sql, m, i);
        if (id == null) {
            return i;
        }
        int j = skipWs(m, id.end());
        if (j < n && m[j] == '(') {
            // Table function (UNNEST/JSON_TABLE/...): not a model reference.
            return j + 1;
        }
        if (j < n && m[j] == '.') {
            int k = j;
            while (k < n && m[k] == '.') {
                k = skipWs(m, k + 1);
                Ident part = readIdent(sql, m, k);
                if (part == null) {
                    break;
                }
                k = skipWs(m, part.end());
            }
            return skipAlias(sql, m, k);
        }
        if (!ctes.contains(id.name().toLowerCase(Locale.ROOT))) {
            out.add(new Ref(id.start(), id.end(), id.name()));
        }
        return skipAlias(sql, m, id.end());
    }

    /** Consumes an optional {@code AS alias} / bare alias; returns the resume index. */
    private static int skipAlias(String sql, char[] m, int i) {
        int n = sql.length();
        int j = skipWs(m, i);
        if (j >= n) {
            return j;
        }
        if (wordAt(m, j, "AS")) {
            int k = skipWs(m, j + 2);
            Ident alias = readIdent(sql, m, k);
            return alias == null ? k : alias.end();
        }
        Ident next = readIdent(sql, m, j);
        if (next != null && !ALIAS_STOP_WORDS.contains(next.name().toUpperCase(Locale.ROOT))) {
            return next.end();
        }
        return i;
    }

    /** CTE names declared in the statement's leading WITH clause (case-insensitive). */
    private static Set<String> cteNames(String sql, char[] m) {
        Set<String> names = new HashSet<>();
        int n = sql.length();
        int i = skipWs(m, 0);
        if (!wordAt(m, i, "WITH")) {
            return names;
        }
        i = skipWs(m, i + 4);
        if (wordAt(m, i, "RECURSIVE")) {
            i = skipWs(m, i + 9);
        }
        while (i < n) {
            Ident name = readIdent(sql, m, i);
            if (name == null) {
                break;
            }
            names.add(name.name().toLowerCase(Locale.ROOT));
            i = skipWs(m, name.end());
            if (i < n && m[i] == '(') {
                i = skipWs(m, skipBalanced(sql, m, i));
            }
            if (!wordAt(m, i, "AS")) {
                break;
            }
            i = skipWs(m, i + 2);
            if (i >= n || m[i] != '(') {
                break;
            }
            i = skipWs(m, skipBalanced(sql, m, i));
            if (i < n && m[i] == ',') {
                i = skipWs(m, i + 1);
                continue;
            }
            break;
        }
        return names;
    }

    // ------------------------------------------------------------------ lexical helpers

    /**
     * Length-preserving copy of the statement with single-quoted strings and line/block comments
     * blanked, so structure scanning never trips over their contents.
     */
    private static char[] mask(String sql) {
        char[] m = sql.toCharArray();
        int n = m.length;
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = blank(m, i, skipQuoted(sql, i, '\''));
                continue;
            }
            if (c == '-'
                    && i + 1 < n
                    && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || isSpaceOrControl(sql.charAt(i + 2)))) {
                int end = i;
                while (end < n && sql.charAt(end) != '\n') {
                    end++;
                }
                i = blank(m, i, end);
                continue;
            }
            if (c == '#') {
                int end = i;
                while (end < n && sql.charAt(end) != '\n') {
                    end++;
                }
                i = blank(m, i, end);
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                i = blank(m, i, close < 0 ? n : close + 2);
                continue;
            }
            i++;
        }
        return m;
    }

    /** Blanks {@code [start, end)} on the working copy and returns {@code end}. */
    private static int blank(char[] m, int start, int end) {
        for (int k = start; k < end; k++) {
            m[k] = ' ';
        }
        return end;
    }

    /** Index after the closing quote (doubling and backslash escapes honoured); {@code n} if open. */
    private static int skipQuoted(String sql, int start, char q) {
        int n = sql.length();
        int i = start + 1;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == q) {
                if (i + 1 < n && sql.charAt(i + 1) == q) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /** Reads one identifier at {@code start}: plain, double-quoted or backtick-quoted. */
    private static Ident readIdent(String sql, char[] m, int start) {
        int n = sql.length();
        if (start >= n) {
            return null;
        }
        char c = m[start];
        if (c == '"' || c == '`') {
            int end = skipQuoted(sql, start, c);
            int close = end <= n && end - 1 > start && sql.charAt(end - 1) == c ? end - 1 : end;
            String inner = sql.substring(start + 1, Math.max(start + 1, close));
            String decoded =
                    c == '"'
                            ? inner.replace("\"\"", "\"")
                            : inner.replace("``", "`").replace("\\`", "`");
            return new Ident(start, end, decoded);
        }
        if (!isIdentStart(c)) {
            return null;
        }
        int i = start;
        while (i < n && isIdentChar(m[i])) {
            i++;
        }
        return new Ident(start, i, sql.substring(start, i));
    }

    /** Index after the ')' matching the '(' at {@code start}; {@code n} when unbalanced. */
    private static int skipBalanced(String sql, char[] m, int start) {
        int n = sql.length();
        int depth = 0;
        int i = start;
        while (i < n) {
            char c = m[i];
            if (c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return n;
    }

    /** Case-insensitive whole-word match at {@code i} on the masked copy. */
    private static boolean wordAt(char[] m, int i, String word) {
        int n = m.length;
        int len = word.length();
        if (i < 0 || i + len > n) {
            return false;
        }
        if (i > 0 && isIdentChar(m[i - 1])) {
            return false;
        }
        for (int k = 0; k < len; k++) {
            if (Character.toUpperCase(m[i + k]) != word.charAt(k)) {
                return false;
            }
        }
        return i + len >= n || !isIdentChar(m[i + len]);
    }

    /** True when any enclosing parenthesis was opened by an EXTRACT/TRIM/... sub-clause. */
    private static boolean insideFromSubclause(Deque<String> fnStack) {
        for (String fn : fnStack) {
            if (FROM_SUBCLAUSE_FUNCTIONS.contains(fn)) {
                return true;
            }
        }
        return false;
    }

    private static int skipWs(char[] m, int i) {
        while (i < m.length && Character.isWhitespace(m[i])) {
            i++;
        }
        return i;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isSpaceOrControl(char c) {
        return Character.isWhitespace(c) || Character.isISOControl(c);
    }

    private static String quotePart(String part) {
        return part.matches("[A-Za-z_][A-Za-z0-9_]*") ? part : "`" + part.replace("`", "``") + "`";
    }
}
