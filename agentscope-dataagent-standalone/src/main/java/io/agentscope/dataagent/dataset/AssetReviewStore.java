package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Human review of Views and ref_sql models (ADR0128). Records live in the workspace's
 * {@code .platform/}, which is never published or shown to the agent, so only the page can write
 * them. A record binds to the asset's content hash: editing an asset silently voids it. Callers
 * hold the workspace lock.
 */
@Component
public class AssetReviewStore {
    public static final String KIND_VIEW = "view";
    public static final String KIND_REF_SQL = "ref_sql";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String REVIEW_PENDING = "REVIEW_PENDING";
    public static final String VERIFY_PENDING = "VERIFY_PENDING";
    public static final String VERIFIED = "VERIFIED";

    private static final String FILE = ".platform/asset-reviews.json";
    private static final List<String> SOURCES = List.of("metadata.yml", "ref_sql.sql", "sql.yml");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.regex.Pattern DATE_LITERAL =
            java.util.regex.Pattern.compile("'\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}[^']*'");

    private final MdlWorkspaceReader reader;
    private final MdlWorkspaceService workspace;
    private final WrenProperties props;

    public AssetReviewStore(
            MdlWorkspaceReader reader, MdlWorkspaceService workspace, WrenProperties props) {
        this.reader = reader;
        this.workspace = workspace;
        this.props = props;
    }

    /** What a person concluded about one exact version of an asset. */
    public record Review(
            String hash,
            String reviewedBy,
            String reviewedAt,
            List<String> timeColumns,
            List<String> ran,
            Map<String, String> verdicts,
            String verifiedBy,
            String verifiedAt,
            boolean passed) {
        Review withRun(List<String> keys) {
            return new Review(
                    hash, reviewedBy, reviewedAt, timeColumns, keys, Map.of(), null, null, false);
        }

        Review withVerdicts(Map<String, String> values, String by, String at, boolean ok) {
            return new Review(hash, reviewedBy, reviewedAt, timeColumns, ran, values, by, at, ok);
        }
    }

    /** {@code review} is null unless it still matches the asset's current content. */
    public record Asset(
            String kind,
            String name,
            String directory,
            String sql,
            String description,
            String hash,
            String status,
            Review review) {
        public String key() {
            return key(kind, name);
        }

        public boolean changed() {
            return !PUBLISHED.equals(status);
        }

        static String key(String kind, String name) {
            return kind + ":" + name;
        }
    }

    /** Date literals in an asset's SQL: they silently cap every later query to that period. */
    public static List<String> fixedDates(String text) {
        return text == null
                ? List.of()
                : DATE_LITERAL.matcher(text).results().map(m -> m.group()).distinct().toList();
    }

    public List<Asset> assets(String groupId) {
        Path draft = workspace.workspaceRoot(groupId);
        Path published = props.groupRoot(groupId).resolve("published");
        Map<String, Review> reviews = load(draft);
        List<Asset> assets = new ArrayList<>();
        MdlWorkspaceReader.Snapshot snapshot = reader.read(groupId);
        for (var view : snapshot.views())
            assets.add(
                    asset(
                            KIND_VIEW,
                            view.name(),
                            parent(view.path()),
                            view.sql(),
                            view.description(),
                            draft,
                            published,
                            reviews));
        for (var model : snapshot.models()) {
            // A model without table_reference is a ref_sql model even before its SQL is written.
            boolean physical = model.tableName() != null && !model.tableName().isBlank();
            if ((model.refSql() == null || model.refSql().isBlank()) && physical) continue;
            assets.add(
                    asset(
                            KIND_REF_SQL,
                            model.name(),
                            parent(model.path()),
                            model.refSql(),
                            model.description(),
                            draft,
                            published,
                            reviews));
        }
        return List.copyOf(assets);
    }

    private static Asset asset(
            String kind,
            String name,
            String directory,
            String sql,
            String description,
            Path draft,
            Path published,
            Map<String, Review> reviews) {
        String hash = hash(draft.resolve(directory));
        Review review = reviews.get(Asset.key(kind, name));
        if (review != null && !hash.equals(review.hash())) review = null;
        String status =
                hash.equals(hash(published.resolve(directory)))
                        ? PUBLISHED
                        : review == null
                                ? REVIEW_PENDING
                                : review.passed() ? VERIFIED : VERIFY_PENDING;
        return new Asset(kind, name, directory, sql, description, hash, status, review);
    }

    public Asset require(String groupId, String kind, String name) {
        return assets(groupId).stream()
                .filter(a -> a.kind().equals(kind) && a.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new DatasetException("语义资产不存在：" + name, 404));
    }

    public void save(String groupId, Asset asset, Review review) {
        Path root = workspace.workspaceRoot(groupId);
        Map<String, Review> reviews = load(root);
        reviews.put(asset.key(), review);
        try {
            Path file = root.resolve(FILE);
            Files.createDirectories(file.getParent());
            JSON.writeValue(file.toFile(), reviews);
        } catch (IOException e) {
            throw new DatasetException("保存资产核查记录失败：" + e.getMessage(), e);
        }
    }

    /** Publishing is refused while a changed asset lacks a person's conclusion. */
    public List<MdlPublishService.MdlIssue> publishIssues(String groupId) {
        List<MdlPublishService.MdlIssue> issues = new ArrayList<>();
        for (Asset asset : assets(groupId)) {
            if (!asset.changed() || VERIFIED.equals(asset.status())) continue;
            String step = REVIEW_PENDING.equals(asset.status()) ? "尚未核查" : "尚未通过问题验证";
            issues.add(
                    new MdlPublishService.MdlIssue(
                            "error", "语义资产「" + asset.name() + "」" + step + "，不能发布", "local"));
        }
        return issues;
    }

    /** Pins the confirmed time columns beside the snapshot; window sizes stay in the database. */
    public void writeWindows(String groupId, Path publishedDir) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (Asset asset : assets(groupId))
            if (asset.review() != null) columns.put(asset.name(), asset.review().timeColumns());
        try {
            JSON.writeValue(publishedDir.resolve(PublishedAssetWindows.FILE).toFile(), columns);
        } catch (IOException e) {
            throw new DatasetException("写入资产时间配置失败：" + e.getMessage(), e);
        }
    }

    private static Map<String, Review> load(Path root) {
        Path file = root.resolve(FILE);
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            return JSON.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            throw new DatasetException("读取资产核查记录失败：" + e.getMessage(), e);
        }
    }

    private static String parent(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }

    /** Hashes the asset's source files; a missing directory hashes differently from any draft. */
    static String hash(Path directory) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String name : SOURCES) {
                Path file = directory.resolve(name);
                if (!Files.isRegularFile(file)) continue;
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new DatasetException("读取语义资产失败：" + e.getMessage(), e);
        }
    }
}
