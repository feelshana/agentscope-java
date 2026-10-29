package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Business terms of one knowledge base (spec095, ADR0115). Terms live in the Wren project as the
 * official {@code ## Naming conventions} section of {@code knowledge/rules/naming.md}, so querying
 * reads them through the official {@code get_instructions}. Edits go to the draft like any modeling
 * change; QUERY users see the published version only.
 */
@Service
public class BusinessTermService {
    public static final String PATH = "knowledge/rules/naming.md";
    public static final String HEADING = "Naming conventions";
    static final int MAX_TERM = 50;
    static final int MAX_EXPLANATION = 500;

    /** {@code "术语"（同义词："甲"、"乙"）= 解释}; the synonym part is optional. */
    private static final Pattern ENTRY =
            Pattern.compile(
                    "^\"([^\"]+)\"\\s*(?:[（(]\\s*同义词\\s*[:：]\\s*([^）)]*)[）)])?\\s*[=＝]\\s*(.+)$",
                    Pattern.DOTALL);

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    /** One bullet of the file; {@code structured} is false when it does not follow the format. */
    public record Term(
            String text,
            String term,
            List<String> synonyms,
            String explanation,
            boolean structured) {}

    /** A term as the page shows it: the draft text against the published one. */
    public record Row(
            String term,
            List<String> synonyms,
            String explanation,
            String text,
            boolean structured,
            String status,
            String publishedText) {}

    public record Terms(boolean editable, int publishedVersion, List<Row> rows) {}

    /** Page input: either the three fields, or {@code text} for a free-form entry. */
    public record Input(String term, String synonyms, String explanation, String text) {}

    private final KnowledgeBaseAccessService access;
    private final MdlWorkspaceService workspace;
    private final DraftItemService items;

    public BusinessTermService(
            KnowledgeBaseAccessService access,
            MdlWorkspaceService workspace,
            DraftItemService items) {
        this.access = access;
        this.workspace = workspace;
        this.items = items;
    }

    public Terms list(String userId, String groupId) {
        DatasetGroupEntity group = access.requireQuery(userId, groupId);
        boolean manage = access.role(userId, group) == KnowledgeBaseAccessService.Role.MANAGE;
        int version = group.getMdlVersion();
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path draftRoot = workspace.workspaceRoot(groupId);
                    List<Term> published =
                            parse(read(draftRoot.getParent().resolve("published").resolve(PATH)));
                    if (!manage)
                        return new Terms(
                                false,
                                version,
                                published.stream().map(t -> row(t, "PUBLISHED", null)).toList());
                    return new Terms(
                            true, version, merge(parse(read(draftRoot.resolve(PATH))), published));
                });
    }

    public Terms add(String userId, String groupId, Input input) {
        String text = format(input);
        requireUnique(userId, groupId, parseEntry(text), null);
        items.appendBullet(userId, groupId, PATH, HEADING, text);
        return list(userId, groupId);
    }

    public Terms update(String userId, String groupId, String original, Input input) {
        String text = format(input);
        requireUnique(userId, groupId, parseEntry(text), original);
        items.updateBullet(userId, groupId, PATH, original, text);
        return list(userId, groupId);
    }

    public Terms delete(String userId, String groupId, String original) {
        items.updateBullet(userId, groupId, PATH, original, null);
        return list(userId, groupId);
    }

    /** Builds the bullet text from the page fields, or validates a free-form entry. */
    static String format(Input input) {
        if (input == null) throw new DatasetException("请填写术语", 400);
        if (input.text() != null && !input.text().isBlank()) {
            String text = input.text().strip();
            if (text.length() > MAX_TERM + MAX_EXPLANATION)
                throw new DatasetException("条目过长，请精简", 400);
            return text;
        }
        String term = clean(input.term());
        String explanation = clean(input.explanation()).replaceAll("\\s*\\R\\s*", " ");
        if (term.isEmpty()) throw new DatasetException("请填写术语", 400);
        if (explanation.isEmpty()) throw new DatasetException("请填写术语解释", 400);
        if (term.length() > MAX_TERM) throw new DatasetException("术语不能超过 " + MAX_TERM + " 字", 400);
        if (explanation.length() > MAX_EXPLANATION)
            throw new DatasetException("解释不能超过 " + MAX_EXPLANATION + " 字", 400);
        if (term.contains("\"") || term.contains("\n"))
            throw new DatasetException("术语不能包含英文双引号或换行", 400);
        List<String> synonyms = splitSynonyms(input.synonyms());
        synonyms.removeIf(s -> s.equalsIgnoreCase(term));
        for (String s : synonyms)
            if (s.length() > MAX_TERM)
                throw new DatasetException("同义词不能超过 " + MAX_TERM + " 字", 400);
        StringBuilder text = new StringBuilder("\"").append(term).append('"');
        if (!synonyms.isEmpty())
            text.append("（同义词：")
                    .append(String.join("、", synonyms.stream().map(s -> "\"" + s + "\"").toList()))
                    .append('）');
        // Matches the spec095 examples: "术语"（同义词：…）= 解释 / "术语" = 解释.
        return text.append(synonyms.isEmpty() ? " = " : "= ").append(explanation).toString();
    }

    static List<String> splitSynonyms(String raw) {
        if (raw == null) return new ArrayList<>();
        return new ArrayList<>(
                Arrays.stream(raw.split("[,，、;；\\n]"))
                        .map(BusinessTermService::clean)
                        .map(s -> s.replace("\"", ""))
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList());
    }

    /** Every bullet of the file, in order. */
    static List<Term> parse(String markdown) {
        List<Term> out = new ArrayList<>();
        if (markdown == null) return out;
        List<String> lines = List.of(markdown.split("\n", -1));
        for (int i = 0; i < lines.size(); i++) {
            if (!DraftItemService.isBullet(lines.get(i))) continue;
            int end = i + 1;
            while (end < lines.size() && DraftItemService.isContinuation(lines.get(end))) end++;
            out.add(parseEntry(DraftItemService.bulletText(lines.subList(i, end))));
            i = end - 1;
        }
        return out;
    }

    static Term parseEntry(String text) {
        Matcher m = ENTRY.matcher(text.strip());
        if (!m.matches()) return new Term(text, null, List.of(), null, false);
        List<String> synonyms = new ArrayList<>();
        if (m.group(2) != null) {
            Matcher q = QUOTED.matcher(m.group(2));
            while (q.find()) synonyms.add(q.group(1).strip());
            if (synonyms.isEmpty()) synonyms.addAll(splitSynonyms(m.group(2)));
        }
        return new Term(text, m.group(1).strip(), synonyms, m.group(3).strip(), true);
    }

    /** Draft rows with their published counterpart, then rows deleted from the draft. */
    static List<Row> merge(List<Term> draft, List<Term> published) {
        Map<String, Term> online = new LinkedHashMap<>();
        for (Term t : published) online.putIfAbsent(key(t), t);
        List<Row> rows = new ArrayList<>();
        for (Term t : draft) {
            Term before = online.remove(key(t));
            if (before == null) rows.add(row(t, "ADDED", null));
            else if (Objects.equals(before.text(), t.text())) rows.add(row(t, "PUBLISHED", null));
            else rows.add(row(t, "MODIFIED", before.text()));
        }
        for (Term t : online.values()) rows.add(row(t, "DELETED", t.text()));
        return rows;
    }

    private void requireUnique(String userId, String groupId, Term candidate, String original) {
        if (!candidate.structured()) return;
        List<Term> draft =
                workspace.withWorkspaceLock(
                        groupId,
                        () -> {
                            access.requireManage(userId, groupId);
                            return parse(read(workspace.workspaceRoot(groupId).resolve(PATH)));
                        });
        List<String> names = names(candidate);
        for (Term t : draft) {
            if (!t.structured() || t.text().equals(original)) continue;
            for (String name : names(t))
                if (names.contains(name))
                    throw new DatasetException("术语或同义词「" + name + "」已存在于「" + t.term() + "」", 409);
        }
    }

    private static List<String> names(Term t) {
        List<String> out = new ArrayList<>();
        out.add(t.term().toLowerCase(Locale.ROOT));
        t.synonyms().forEach(s -> out.add(s.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String key(Term t) {
        return t.structured() ? "T:" + t.term().toLowerCase(Locale.ROOT) : "X:" + t.text();
    }

    private static Row row(Term t, String status, String publishedText) {
        return new Row(
                t.term(),
                t.synonyms(),
                t.explanation(),
                t.text(),
                t.structured(),
                status,
                publishedText);
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : null;
        } catch (IOException e) {
            throw new DatasetException("读取术语失败：" + e.getMessage(), e);
        }
    }
}
