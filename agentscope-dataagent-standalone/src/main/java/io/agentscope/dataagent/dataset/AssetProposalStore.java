package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Semantic asset proposals (spec113, ADR0131): the assistant suggests a query template or a
 * metric in an ordinary modeling turn, and nothing is drafted until the user chooses to. Kept in
 * the project's platform directory, which the assistant cannot write.
 */
@Component
public class AssetProposalStore {
    public static final String PENDING = "PENDING";
    public static final String DRAFTED = "DRAFTED";
    public static final String DISMISSED = "DISMISSED";

    /** spec114: rules, relationships and calculated fields are proposed alongside templates. */
    static final Set<String> KINDS = Set.of("rule", "relationship", "calculated_field", "view");

    static final int MAX_PENDING = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILE = ".platform/asset-proposals.json";

    /**
     * One proposal. {@code questionIds} are the coach questions it answers (spec114);
     * {@code explicit} marks a query template the user asked for in so many words, which the
     * page drafts without a further click.
     */
    public record Proposal(
            String id,
            String kind,
            String name,
            String need,
            String outline,
            String reason,
            String status,
            String createdAt,
            String decidedBy,
            String decidedAt,
            List<String> questionIds,
            Boolean explicit) {}

    private final MdlWorkspaceService workspace;

    public AssetProposalStore(MdlWorkspaceService workspace) {
        this.workspace = workspace;
    }

    /**
     * Records a pending proposal. The name must be new: not a model, a view, or another pending
     * proposal of this knowledge base.
     */
    public Proposal propose(
            String groupId, String kind, String name, String need, String outline, String reason) {
        return propose(groupId, kind, name, need, outline, reason, List.of(), false);
    }

    public Proposal propose(
            String groupId,
            String kind,
            String name,
            String need,
            String outline,
            String reason,
            List<String> questionIds,
            boolean explicit) {
        String k = kind == null ? "" : kind.strip().toLowerCase(java.util.Locale.ROOT);
        if (!KINDS.contains(k))
            throw new DatasetException(
                    "kind 只能是 rule、relationship、calculated_field 或 view；指标（Cube）尚未开放", 400);
        String n = name == null ? "" : name.strip();
        // A query template's name becomes a model name; other proposals only need a short title.
        if ("view".equals(k) && !n.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,63}"))
            throw new DatasetException("查询模板名称只能用中文、字母、数字和下划线，且不以数字开头", 400);
        if (n.isEmpty() || n.length() > 64) throw new DatasetException("提议需要一个不超过 64 字的名称", 400);
        if (blank(need) || blank(outline) || blank(reason))
            throw new DatasetException("提议需要写明 need（要解决的问题）、outline（起草要点）和 reason（为什么需要建资产）", 400);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path root = workspace.workspaceRoot(groupId);
                    var nodes = QuestionDependencies.read(root).nodes();
                    if ("view".equals(k) && nodes.containsKey(n.toLowerCase(java.util.Locale.ROOT)))
                        throw new DatasetException("已存在同名的模型或资产「" + n + "」，请换一个名称", 409);
                    List<Proposal> all = read(root);
                    if (all.stream()
                            .anyMatch(p -> PENDING.equals(p.status()) && p.name().equals(n)))
                        throw new DatasetException("已有同名的待处理提议「" + n + "」，不要重复提议", 409);
                    if (all.stream().filter(p -> PENDING.equals(p.status())).count() >= MAX_PENDING)
                        throw new DatasetException("待处理提议已达上限，请用户先处理已有提议", 409);
                    Proposal created =
                            new Proposal(
                                    UUID.randomUUID().toString(),
                                    k,
                                    n,
                                    need.strip(),
                                    outline.strip(),
                                    reason.strip(),
                                    PENDING,
                                    Instant.now().toString(),
                                    null,
                                    null,
                                    questionIds == null ? List.of() : List.copyOf(questionIds),
                                    explicit);
                    all.add(created);
                    write(root, all);
                    return created;
                });
    }

    public List<Proposal> list(String groupId) {
        return workspace.withWorkspaceLock(
                groupId, () -> List.copyOf(read(workspace.workspaceRoot(groupId))));
    }

    /** Marks a pending proposal as drafted or dismissed; a decided one cannot change again. */
    public Proposal decide(String groupId, String id, String status, String actor) {
        if (!DRAFTED.equals(status) && !DISMISSED.equals(status))
            throw new DatasetException("提议状态只能是 DRAFTED 或 DISMISSED", 400);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path root = workspace.workspaceRoot(groupId);
                    List<Proposal> all = read(root);
                    for (int i = 0; i < all.size(); i++) {
                        Proposal p = all.get(i);
                        if (!p.id().equals(id)) continue;
                        if (!PENDING.equals(p.status()))
                            throw new DatasetException("这条提议已经处理过", 409);
                        Proposal decided =
                                new Proposal(
                                        p.id(),
                                        p.kind(),
                                        p.name(),
                                        p.need(),
                                        p.outline(),
                                        p.reason(),
                                        status,
                                        p.createdAt(),
                                        actor,
                                        Instant.now().toString(),
                                        p.questionIds(),
                                        p.explicit());
                        all.set(i, decided);
                        write(root, all);
                        return decided;
                    }
                    throw new DatasetException("提议不存在", 404);
                });
    }

    private static List<Proposal> read(Path root) {
        Path file = root.resolve(FILE);
        if (!Files.isRegularFile(file)) return new ArrayList<>();
        try {
            return new ArrayList<>(
                    JSON.readValue(
                            Files.readString(file, StandardCharsets.UTF_8),
                            new TypeReference<List<Proposal>>() {}));
        } catch (IOException e) {
            throw new DatasetException("读取资产提议失败：" + e.getMessage(), e);
        }
    }

    private static void write(Path root, List<Proposal> all) {
        Path file = root.resolve(FILE);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), ".proposals-", ".tmp");
            try {
                Files.writeString(temp, JSON.writeValueAsString(all), StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new DatasetException("保存资产提议失败：" + e.getMessage(), e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
