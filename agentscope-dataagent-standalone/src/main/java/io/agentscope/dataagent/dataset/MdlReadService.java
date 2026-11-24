package io.agentscope.dataagent.dataset;

import io.agentscope.dataagent.dataset.MdlTypes.CompiledMdl;
import io.agentscope.dataagent.dataset.MdlTypes.MdlColumnView;
import io.agentscope.dataagent.dataset.MdlTypes.MdlCubeMemberView;
import io.agentscope.dataagent.dataset.MdlTypes.MdlCubeView;
import io.agentscope.dataagent.dataset.MdlTypes.MdlFile;
import io.agentscope.dataagent.dataset.MdlTypes.MdlModelView;
import io.agentscope.dataagent.dataset.MdlTypes.MdlPreview;
import io.agentscope.dataagent.dataset.MdlTypes.MdlRelationView;
import io.agentscope.dataagent.dataset.MdlTypes.MdlView;
import io.agentscope.dataagent.dataset.MdlTypes.MdlViewSummary;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads preview, compiled text and view projections without entering the publication pipeline. */
public final class MdlReadService {
    private static final Logger log = LoggerFactory.getLogger(MdlReadService.class);
    private final DatasetGroupRepository groupRepository;
    private final WrenProperties props;
    private final MdlWorkspaceService workspace;
    private final MdlWorkspaceReader reader;

    public MdlReadService(
            DatasetGroupRepository groupRepository,
            WrenProperties props,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader reader) {
        this.groupRepository = groupRepository;
        this.props = props;
        this.workspace = workspace;
        this.reader = reader;
    }

    /** Lists workspace and published files for the YAML diff card; never reconciles. */
    public MdlPreview preview(String groupId) {
        DatasetGroupEntity group = requireGroup(groupId);
        List<MdlFile> files = reader.listFiles(groupId);
        List<MdlFile> published = readPublishedFiles(groupId);
        return new MdlPreview(
                files,
                published,
                List.of(),
                group.getMdlState(),
                group.getMdlVersion(),
                group.getMdlPublishedAt() == null ? null : group.getMdlPublishedAt().toString(),
                !sameFiles(files, published));
    }

    private static final long MAX_COMPILED_BYTES = 4L * 1024 * 1024;

    /**
     * The official compiled MDL ({@code target/mdl.json}): the published one is exactly what the
     * query agent reads as {@code wren://mdl}; the draft one is from the draft's last build.
     */
    public CompiledMdl compiled(String groupId) {
        Path published = props.groupRoot(groupId).resolve("published/target/mdl.json");
        Path draft = workspace.workspaceRoot(groupId).resolve("target/mdl.json");
        return new CompiledMdl(
                compiledText(published), builtAt(published), compiledText(draft), builtAt(draft));
    }

    private static String compiledText(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.size(file) > MAX_COMPILED_BYTES)
                throw new DatasetException("编译后的 MDL 过大，无法在页面显示", 413);
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("读取编译后的 MDL 失败：" + e.getMessage(), e);
        }
    }

    private static String builtAt(Path file) {
        try {
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    ? Files.getLastModifiedTime(file).toInstant().toString()
                    : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Renders the workspace as a structured view; never touches the staging directory. */
    public MdlView view(String groupId) {
        DatasetGroupEntity group = requireGroup(groupId);
        MdlWorkspaceReader.Snapshot snapshot = reader.read(groupId);
        List<MdlModelView> models = new ArrayList<>();
        List<MdlModelView> derivedModels = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceModel m : snapshot.models()) {
            List<MdlColumnView> cols = new ArrayList<>();
            for (MdlWorkspaceReader.WorkspaceColumn c : m.columns()) {
                cols.add(
                        new MdlColumnView(
                                c.name(),
                                c.type(),
                                c.description(),
                                c.relationship() != null,
                                c.calculated(),
                                c.expression(),
                                c.primaryKey(),
                                c.displayName(),
                                c.semanticType(),
                                c.synonyms(),
                                c.values(),
                                c.userDescribed()));
            }
            MdlModelView mv =
                    new MdlModelView(
                            m.datasetId(),
                            m.datasetName(),
                            m.tableName(),
                            m.name(),
                            m.description(),
                            cols,
                            m.path(),
                            m.refSqlPath(),
                            m.primaryKey(),
                            m.tableRole());
            // Derived (ref_sql) models get their own tab — the schema tab stays physical-only.
            if (m.refSql() != null) {
                derivedModels.add(mv);
            } else {
                models.add(mv);
            }
        }
        List<MdlRelationView> relations = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceRelation r : snapshot.relations()) {
            relations.add(
                    new MdlRelationView(
                            r.name(),
                            r.leftModel(),
                            r.rightModel(),
                            r.joinType(),
                            r.condition(),
                            r.sourceColumns(),
                            r.targetColumns(),
                            r.description()));
        }
        List<MdlCubeView> cubes = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceCube c : snapshot.cubes()) {
            cubes.add(
                    new MdlCubeView(
                            c.name(),
                            c.baseModel(),
                            c.description(),
                            toMemberViews(c.measures()),
                            toMemberViews(c.dimensions()),
                            toMemberViews(c.timeDimensions()),
                            c.path()));
        }
        List<MdlViewSummary> views = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceView v : snapshot.views()) {
            views.add(new MdlViewSummary(v.name(), v.sql(), v.description(), v.path()));
        }
        boolean changed = !sameFiles(snapshot.files(), readPublishedFiles(groupId));
        return new MdlView(
                group.getMdlState(),
                group.getMdlVersion(),
                group.getMdlPublishedAt() == null ? null : group.getMdlPublishedAt().toString(),
                changed,
                models,
                relations,
                cubes,
                views,
                snapshot.issues(),
                derivedModels);
    }

    private static List<MdlCubeMemberView> toMemberViews(
            List<MdlWorkspaceReader.WorkspaceMember> members) {
        List<MdlCubeMemberView> out = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceMember m : members) {
            out.add(new MdlCubeMemberView(m.name(), m.expression(), m.type(), m.description()));
        }
        return out;
    }

    private List<MdlFile> readPublishedFiles(String groupId) {
        Path published = props.groupRoot(groupId).resolve("published");
        if (!Files.isDirectory(published)) {
            return List.of();
        }
        List<MdlFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(published)) {
            List<Path> files =
                    walk.filter(Files::isRegularFile)
                            .filter(
                                    p ->
                                            !Set.of(
                                                            "wren-source.properties",
                                                            PublishedAssetWindows.FILE)
                                                    .contains(p.getFileName().toString()))
                            .sorted()
                            .toList();
            for (Path p : files) {
                String rel = published.relativize(p).toString().replace('\\', '/');
                if (MdlWorkspaceReader.isInternal(rel)) {
                    continue;
                }
                out.add(new MdlFile(rel, Files.readString(p, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            log.warn(
                    "MdlReadService: could not read published snapshot for group {}: {}",
                    groupId,
                    e.getMessage());
            return List.of();
        }
        return out;
    }

    private DatasetGroupEntity requireGroup(String groupId) {
        return groupRepository
                .findById(groupId)
                .orElseThrow(
                        () -> new DatasetException("Knowledge base not found: " + groupId, 404));
    }

    private static boolean sameFiles(List<MdlFile> a, List<MdlFile> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, String> byPath = new HashMap<>();
        for (MdlFile f : b) {
            byPath.put(f.path(), f.content());
        }
        for (MdlFile f : a) {
            String other = byPath.get(f.path());
            if (other == null || !other.equals(f.content())) {
                return false;
            }
        }
        return true;
    }
}
