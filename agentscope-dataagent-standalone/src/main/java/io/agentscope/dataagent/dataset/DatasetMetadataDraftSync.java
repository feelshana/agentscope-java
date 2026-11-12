package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Apply explicit metadata edits to the mapped draft, never to published query snapshots. */
@Component
public class DatasetMetadataDraftSync {
    private final MdlWorkspaceService workspace;
    private final KnowledgeBaseAccessService access;
    private final EntityManager entities;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public DatasetMetadataDraftSync(
            MdlWorkspaceService workspace, KnowledgeBaseAccessService access) {
        this(workspace, access, null);
    }

    @Autowired
    public DatasetMetadataDraftSync(
            MdlWorkspaceService workspace,
            KnowledgeBaseAccessService access,
            EntityManager entities) {
        this.workspace = workspace;
        this.access = access;
        this.entities = entities;
    }

    public <T> T update(
            String actor,
            DatasetEntity dataset,
            String description,
            List<DatasetService.ColumnDescUpdate> columns,
            Supplier<T> save) {
        String groupId = dataset.getGroupId();
        return workspace.withWorkspaceTransactionLock(
                groupId,
                () -> {
                    access.requireManage(actor, groupId);
                    // A managed entity may have been read before waiting for another writer.
                    // The locking refresh reads the latest committed row even under MySQL RR.
                    if (entities != null) entities.refresh(dataset, LockModeType.PESSIMISTIC_WRITE);
                    if (!groupId.equals(dataset.getGroupId()))
                        throw new DatasetException("数据集归属已变化，请刷新后重试", 409);
                    Path root = workspace.workspaceRoot(dataset.getGroupId());
                    Path mapping = root.resolve(".platform/datasets.json");
                    try {
                        if (Files.notExists(mapping, LinkOption.NOFOLLOW_LINKS)) return save.get();
                        if (!Files.isRegularFile(mapping, LinkOption.NOFOLLOW_LINKS)
                                || !mapping.toRealPath().startsWith(root.toRealPath()))
                            throw new DatasetException("无法读取模型映射，请先检查建模草稿", 409);
                        String dir =
                                json.readTree(mapping.toFile()).path(dataset.getId()).asText("");
                        if (dir.isEmpty()) return save.get();
                        if (!dir.matches("[\\p{L}\\p{N}_-]+"))
                            throw new DatasetException("模型映射无效，请先检查建模草稿", 409);
                        String relative = "models/" + dir + "/metadata.yml";
                        Path file = workspace.resolveWritable(dataset.getGroupId(), relative);
                        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return save.get();
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                            throw new DatasetException("模型文件无效，请先检查建模草稿", 409);
                        if (!file.toRealPath().startsWith(root.toRealPath()))
                            throw new DatasetException("模型文件路径无效", 409);
                        byte[] before = Files.readAllBytes(file);
                        JsonNode parsed = yaml.readTree(before);
                        if (!(parsed instanceof ObjectNode model))
                            throw new DatasetException("模型草稿格式错误，请先在建模工作台修复", 409);
                        if (!model.path("columns").isArray())
                            throw new DatasetException("模型字段格式错误，请先在建模工作台修复", 409);
                        ObjectNode original = model.deepCopy();
                        if (description != null)
                            properties(model).put("description", description.strip());
                        for (JsonNode node : model.path("columns")) {
                            if (!(node instanceof ObjectNode column)
                                    || column.path("isCalculated").asBoolean(false)
                                    || column.path("is_calculated").asBoolean(false)
                                    || column.hasNonNull("relationship")) continue;
                            for (var edit : columns) {
                                if (!edit.name().equals(column.path("name").asText())) continue;
                                ObjectNode props = properties(column);
                                props.put(
                                        "description",
                                        edit.description() == null ? "" : edit.description());
                                props.put("description_source", "user");
                                if (edit.displayName() != null)
                                    props.put("displayName", edit.displayName().strip());
                            }
                        }
                        if (original.equals(model)) return save.get();
                        Path log = root.resolve(".platform/draft-changes.jsonl");
                        byte[] logBefore = Files.exists(log) ? Files.readAllBytes(log) : null;
                        Runnable rollback =
                                () -> {
                                    try {
                                        Files.write(file, before);
                                        if (logBefore == null) Files.deleteIfExists(log);
                                        else Files.write(log, logBefore);
                                    } catch (IOException e) {
                                        throw new DatasetException("恢复模型草稿失败，请核对工作台", e);
                                    }
                                };
                        if (TransactionSynchronizationManager.isSynchronizationActive())
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public int getOrder() {
                                            return 0;
                                        }

                                        @Override
                                        public void afterCompletion(int status) {
                                            if (status != STATUS_COMMITTED) rollback.run();
                                        }
                                    });
                        try {
                            Files.writeString(file, yaml.writeValueAsString(model));
                            DraftChangeLog.record(root, relative, ModelingTurnPurpose.EDIT, actor);
                            return save.get();
                        } catch (RuntimeException | IOException e) {
                            rollback.run();
                            throw e;
                        }
                    } catch (IOException e) {
                        throw new DatasetException("同步模型草稿失败，资料未保存，请核对建模文件", e);
                    }
                });
    }

    private static ObjectNode properties(ObjectNode node) {
        JsonNode value = node.get("properties");
        if (value != null && !value.isObject()) throw new DatasetException("模型属性格式错误，请先修复草稿", 409);
        return value instanceof ObjectNode existing ? existing : node.putObject("properties");
    }
}
