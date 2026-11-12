package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, idempotent physical cleanup after platform metadata deletion commits. */
@Service
public class DatasetDeletionCleanup {
    private static final Logger log = LoggerFactory.getLogger(DatasetDeletionCleanup.class);

    record Intent(int format, String datasetId, String groupId, String table, boolean uploaded) {}

    private final DatasetRepository datasets;
    private final InMemoryDataSourceRegistry registry;
    private final TableProvisioner tables;
    private final WrenProperties props;
    private final ObjectMapper mapper;
    private final KnowledgeBaseOperations operations;
    private final PlatformTransactionManager manager;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledExecutorService retries =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var thread = new Thread(r, "dataset-cleanup-retry");
                        thread.setDaemon(true);
                        return thread;
                    });

    public DatasetDeletionCleanup(
            DatasetRepository datasets,
            InMemoryDataSourceRegistry registry,
            TableProvisioner tables,
            WrenProperties props,
            ObjectMapper mapper,
            KnowledgeBaseOperations operations,
            PlatformTransactionManager manager) {
        this.datasets = datasets;
        this.registry = registry;
        this.tables = tables;
        this.props = props;
        this.mapper = mapper;
        this.operations = operations;
        this.manager = manager;
    }

    Intent prepare(DatasetEntity entity) {
        Intent intent =
                new Intent(
                        1,
                        entity.getId(),
                        entity.getGroupId(),
                        entity.getTableName(),
                        !"datasource".equals(entity.getOrigin()));
        validate(intent);
        if (!inFlight.add(intent.datasetId())) throw new DatasetException("数据集删除正在处理中", 409);
        try {
            Path file = file(intent.datasetId());
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(temporary.toFile(), intent);
            try (var channel =
                    java.nio.channels.FileChannel.open(
                            temporary, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return intent;
        } catch (IOException failure) {
            inFlight.remove(intent.datasetId());
            throw new DatasetException("无法登记数据集删除清理任务", failure);
        }
    }

    void cancel(Intent intent) {
        try {
            Files.deleteIfExists(file(intent.datasetId()));
        } catch (IOException failure) {
            log.warn("Dataset rollback cleanup journal pending: {}", intent.datasetId());
        } finally {
            inFlight.remove(intent.datasetId());
        }
    }

    void complete(Intent intent) {
        try {
            if (rowExists(intent.datasetId())) {
                cancel(intent);
                return;
            }
            registry.remove(intent.datasetId());
            if (intent.uploaded()) {
                tables.dropTableStrict(intent.table());
                operations.forgetImportedTable(intent.groupId(), intent.table());
            }
            Files.deleteIfExists(file(intent.datasetId()));
        } catch (IOException | RuntimeException failure) {
            // Metadata is committed: keep the intent for retry, never report a rollback.
            log.warn(
                    "Dataset physical cleanup pending: id={}, failure={}",
                    intent.datasetId(),
                    failure.getClass().getSimpleName());
        } finally {
            inFlight.remove(intent.datasetId());
        }
    }

    private boolean rowExists(String id) {
        if (manager == null) return datasets.findById(id).isPresent();
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Boolean.TRUE.equals(tx.execute(status -> datasets.findById(id).isPresent()));
    }

    @PostConstruct
    void start() {
        retryPending();
        retries.scheduleWithFixedDelay(
                this::retryPending, 30, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        retries.shutdownNow();
    }

    public void retryPending() {
        Path directory = props.mdlRoot().resolve(".dataset-deletions");
        if (!Files.isDirectory(directory)) return;
        try (var paths = Files.list(directory)) {
            for (var path :
                    paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    Intent intent = mapper.readValue(path.toFile(), Intent.class);
                    validate(intent);
                    if (!path.equals(file(intent.datasetId())))
                        throw new IOException("journal id mismatch");
                    if (inFlight.add(intent.datasetId())) complete(intent);
                } catch (IOException | RuntimeException failure) {
                    log.warn("Dataset cleanup journal unreadable: {}", path.getFileName());
                }
            }
        } catch (IOException failure) {
            log.warn("Dataset cleanup journal directory unavailable");
        }
    }

    private void validate(Intent intent) {
        if (intent.format() != 1
                || intent.datasetId() == null
                || !intent.datasetId().matches("[A-Za-z0-9_-]{1,100}"))
            throw new DatasetException("非法数据集清理记录", 500);
        props.groupRoot(intent.groupId());
        if (intent.uploaded()
                && (intent.table() == null || !intent.table().matches("ds_[A-Za-z0-9_]+")))
            throw new DatasetException("非法上传数据表清理记录", 500);
    }

    private Path file(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}"))
            throw new DatasetException("非法清理任务ID", 500);
        return props.mdlRoot().resolve(".dataset-deletions").resolve(id + ".json");
    }
}
