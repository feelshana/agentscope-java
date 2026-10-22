package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Questions the modeling assistant needs the user to decide (spec092). Only genuinely ambiguous
 * points that change statistics are asked; everything the samples, documents or the user already
 * settled is written directly. Questions live under the workspace's {@code .platform/}, so they
 * are never published and never read by queries; answers go back to the assistant, which writes
 * them into business rules or model descriptions.
 */
@Service
public class ModelingConfirmationService {
    public static final int MAX_PENDING = 10;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIRECTORY = ".platform/confirmations";

    private final MdlWorkspaceService workspace;
    private final DatasetGroupService groups;

    public ModelingConfirmationService(MdlWorkspaceService workspace, DatasetGroupService groups) {
        this.workspace = workspace;
        this.groups = groups;
    }

    /** {@code rule} is the exact text the answer would establish; the first option is recommended. */
    public record Option(String label, String rule) {}

    public record Confirmation(
            String id, String question, String basis, List<Option> options, String createdAt) {}

    /** One user decision: an option's rule or the user's own text, or dismissed (no rule). */
    public record Answer(String id, String answer, boolean dismissed) {}

    /** A decided question handed back to the assistant to write into rules or descriptions. */
    public record Decision(String question, String answer) {}

    /** Called by the assistant's tool; the caller has resolved the project with MANAGE. */
    public Confirmation create(
            String groupId,
            String question,
            String basis,
            List<String> labels,
            List<String> rules) {
        String q = text(question, 300, "问题");
        String b = basis == null ? "" : basis.trim();
        if (b.length() > 500) throw new DatasetException("依据说明不能超过 500 字", 400);
        if (labels == null || rules == null || labels.size() != rules.size())
            throw new DatasetException("选项名称与规则原文数量必须一致", 400);
        if (labels.size() < 2 || labels.size() > 4)
            throw new DatasetException("每个问题需要 2–4 个选项，第一个为推荐项", 400);
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++)
            options.add(
                    new Option(text(labels.get(i), 40, "选项名称"), text(rules.get(i), 500, "规则原文")));
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    List<Confirmation> pending = read(groupId);
                    String same = normalized(q);
                    var existing =
                            pending.stream()
                                    .filter(c -> normalized(c.question()).equals(same))
                                    .findFirst();
                    if (existing.isPresent())
                        throw new DatasetException(
                                "已有相同问题待用户确认（" + existing.get().question() + "），不要重复提问", 409);
                    if (pending.size() >= MAX_PENDING)
                        throw new DatasetException(
                                "已有 " + MAX_PENDING + " 个问题等待用户确认，不再新增；请直接完成其余工作", 409);
                    Confirmation created =
                            new Confirmation(
                                    UUID.randomUUID().toString(),
                                    q,
                                    b,
                                    List.copyOf(options),
                                    Instant.now().toString());
                    write(groupId, created);
                    return created;
                });
    }

    public List<Confirmation> list(String userId, String groupId) {
        groups.getGroup(userId, groupId);
        return workspace.withWorkspaceLock(groupId, () -> read(groupId));
    }

    /**
     * Removes the answered questions and returns the decisions to hand to the assistant.
     * Dismissed questions produce no decision; unknown ids are ignored (already resolved).
     */
    public List<Decision> resolve(String userId, String groupId, List<Answer> answers) {
        groups.getGroup(userId, groupId);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    List<Decision> out = new ArrayList<>();
                    for (Answer answer : answers == null ? List.<Answer>of() : answers) {
                        Confirmation question =
                                read(groupId).stream()
                                        .filter(c -> c.id().equals(answer.id()))
                                        .findFirst()
                                        .orElse(null);
                        if (question == null) continue;
                        if (!answer.dismissed())
                            out.add(
                                    new Decision(
                                            question.question(),
                                            text(answer.answer(), 500, "确认内容")));
                        delete(groupId, question.id());
                    }
                    return out;
                });
    }

    private List<Confirmation> read(String groupId) {
        Path directory = workspace.workspaceRoot(groupId).resolve(DIRECTORY);
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            List<Confirmation> out = new ArrayList<>();
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    out.add(JSON.readValue(file.toFile(), Confirmation.class));
                } catch (IOException ignored) {
                    // A torn file loses one question, never the others.
                }
            }
            out.sort(Comparator.comparing(Confirmation::createdAt));
            return out;
        } catch (IOException e) {
            throw new DatasetException("读取待确认问题失败：" + e.getMessage(), e);
        }
    }

    private void write(String groupId, Confirmation confirmation) {
        try {
            Path file =
                    workspace
                            .workspaceRoot(groupId)
                            .resolve(DIRECTORY)
                            .resolve(confirmation.id() + ".json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(confirmation), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("保存待确认问题失败：" + e.getMessage(), e);
        }
    }

    private void delete(String groupId, String id) {
        if (!id.matches("[0-9a-f-]{36}")) return;
        try {
            Files.deleteIfExists(
                    workspace.workspaceRoot(groupId).resolve(DIRECTORY).resolve(id + ".json"));
        } catch (IOException e) {
            throw new DatasetException("删除待确认问题失败：" + e.getMessage(), e);
        }
    }

    private static String text(String value, int max, String name) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw new DatasetException(name + "不能为空", 400);
        if (trimmed.length() > max) throw new DatasetException(name + "不能超过 " + max + " 字", 400);
        return trimmed;
    }

    /**
     * Question text without bracketed asides, whitespace and punctuation: the 2026-10-22 log had
     * the same relationship asked twice, differing only in a parenthetical note.
     */
    static String normalized(String question) {
        if (question == null) return "";
        return question.replaceAll("[（(【\\[][^）)】\\]]*[）)】\\]]", "")
                .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }
}
