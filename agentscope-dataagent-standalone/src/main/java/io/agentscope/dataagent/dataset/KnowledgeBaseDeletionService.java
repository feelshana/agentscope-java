package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Retryable deletion saga. The durable journal outlives both group rows and model directories. */
@Service
public class KnowledgeBaseDeletionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseDeletionService.class);
    private static final List<String> GROUP_ENTITIES =
            List.of(
                    "DocEnhanceProposalEntity",
                    "DocEnhanceTaskEntity",
                    "KnowledgeGraphRelationEntity",
                    "KnowledgeGraphEntityEntity",
                    "KnowledgeGraphBuildUnitEntity",
                    "KnowledgeGraphBuildTaskEntity",
                    "DatasetRelationEntity",
                    "SemanticCubeEntity",
                    "SemanticViewEntity",
                    "SemanticTermEntity",
                    "SemanticBusinessRuleEntity",
                    "DatasetKnowledgeEntity",
                    "DatasetEntity");

    public record Journal(
            String ownerId,
            String groupId,
            List<String> tables,
            boolean complete,
            String lastError,
            String updatedAt) {}

    private final KnowledgeBaseOperations operations;
    private final WrenProperties properties;
    private final DatasetGroupRepository groups;
    private final DatasetRepository datasets;
    private final TableProvisioner provisioner;
    private final WrenQueryGateway wren;
    private final InMemoryDataSourceRegistry registry;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService retries =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "kb-deletion-retry");
                        thread.setDaemon(true);
                        return thread;
                    });
    @PersistenceContext private EntityManager entityManager;

    public KnowledgeBaseDeletionService(
            KnowledgeBaseOperations operations,
            WrenProperties properties,
            DatasetGroupRepository groups,
            DatasetRepository datasets,
            TableProvisioner provisioner,
            WrenQueryGateway wren,
            InMemoryDataSourceRegistry registry,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.operations = operations;
        this.properties = properties;
        this.groups = groups;
        this.datasets = datasets;
        this.provisioner = provisioner;
        this.wren = wren;
        this.registry = registry;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void delete(String ownerId, String groupId) {
        synchronized (locks.computeIfAbsent(groupId, ignored -> new Object())) {
            Journal existing = read(groupId);
            if (existing != null) {
                if (!existing.ownerId().equals(ownerId)) throw new DatasetException("知识库不存在", 404);
                if (existing.complete()) return;
            } else {
                groups.findById(groupId)
                        .filter(group -> group.getOwnerId().equals(ownerId))
                        .orElseThrow(() -> new DatasetException("知识库不存在", 404));
                existing =
                        new Journal(
                                ownerId, groupId, List.of(), false, null, Instant.now().toString());
            }
            Journal initial = existing;
            Journal pending = initial;
            operations.block(groupId, () -> write(initial));
            try {
                operations.awaitStopped(groupId, Duration.ofSeconds(15));
                transactions.executeWithoutResult(
                        ignored ->
                                groups.findById(groupId)
                                        .ifPresent(
                                                group -> {
                                                    group.setMdlState("DELETING");
                                                    groups.save(group);
                                                }));
                wren.closeForDeletion(groupId);

                var rows = datasets.findByGroupId(groupId);
                var tables = new LinkedHashSet<>(pending.tables());
                tables.addAll(operations.importedTables(groupId));
                rows.stream()
                        .filter(row -> !"datasource".equals(row.getOrigin()))
                        .forEach(row -> tables.add(row.getTableName()));
                pending =
                        new Journal(
                                ownerId,
                                groupId,
                                new ArrayList<>(tables),
                                false,
                                null,
                                Instant.now().toString());
                write(pending); // journal table names BEFORE any irreversible DROP TABLE

                List<String> failures = new ArrayList<>();
                for (String table : tables) {
                    try {
                        provisioner.dropTableStrict(table);
                    } catch (RuntimeException e) {
                        failures.add("数据表 " + table + ": " + e.getMessage());
                    }
                }
                try {
                    deleteDirectory(properties.groupRoot(groupId));
                } catch (IOException e) {
                    failures.add("建模目录: " + e.getMessage());
                }
                if (!failures.isEmpty())
                    throw new DatasetException(String.join("；", failures), 503);

                transactions.executeWithoutResult(
                        ignored -> {
                            for (String entity : GROUP_ENTITIES) {
                                entityManager
                                        .createQuery(
                                                "delete from " + entity + " where groupId = :id")
                                        .setParameter("id", groupId)
                                        .executeUpdate();
                            }
                            // A task bound to a removed KB must never run against another/default
                            // KB.
                            entityManager
                                    .createQuery(
                                            "update ScheduledTaskEntity set status = 'paused',"
                                                + " knowledgeBaseId = null where knowledgeBaseId ="
                                                + " :id")
                                    .setParameter("id", groupId)
                                    .executeUpdate();
                            entityManager
                                    .createQuery("delete from DatasetGroupEntity where id = :id")
                                    .setParameter("id", groupId)
                                    .executeUpdate();
                        });
                rows.forEach(row -> registry.remove(row.getId()));
                operations.removeResourceFile(groupId);
                write(
                        new Journal(
                                ownerId, groupId, List.of(), true, null, Instant.now().toString()));
                log.info("[kb-delete] complete group={} owner={}", groupId, ownerId);
            } catch (RuntimeException e) {
                String error =
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                try {
                    write(
                            new Journal(
                                    ownerId,
                                    groupId,
                                    pending.tables(),
                                    false,
                                    error,
                                    Instant.now().toString()));
                } catch (RuntimeException journalError) {
                    e.addSuppressed(journalError);
                }
                log.warn("[kb-delete] pending group={}: {}", groupId, error);
                throw new DatasetException(
                        "知识库删除尚未完成，已记录待清理任务，后台将自动重试；也可再次点击删除。原因：" + error, 503, e);
            }
        }
    }

    /** Bounded batch, also resumes interrupted deletions after a process restart. */
    @PostConstruct
    void startRetries() {
        retries.scheduleWithFixedDelay(
                this::retryPending, 30, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopRetries() {
        retries.shutdownNow();
    }

    public void retryPending() {
        Path directory = properties.mdlRoot().resolve(".kb-deletions");
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            int attempted = 0;
            for (Path file :
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .toList()) {
                try {
                    Journal journal = mapper.readValue(file.toFile(), Journal.class);
                    if (!journal.complete()) {
                        if (attempted++ >= 10) break;
                        delete(journal.ownerId(), journal.groupId());
                    }
                } catch (Exception e) {
                    log.warn(
                            "[kb-delete] retry failed for {}: {}",
                            file.getFileName(),
                            e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[kb-delete] cannot read deletion journal: {}", e.getMessage());
        }
    }

    private Journal read(String groupId) {
        Path file = operations.journal(groupId);
        if (!Files.exists(file)) return null;
        try {
            return mapper.readValue(file.toFile(), Journal.class);
        } catch (IOException e) {
            throw new DatasetException("无法读取知识库删除记录，禁止继续删除", e);
        }
    }

    private void write(Journal journal) {
        Path file = operations.journal(journal.groupId());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(temporary.toFile(), journal);
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DatasetException("无法保存知识库删除记录", e);
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        Path allowed = properties.mdlRoot().toAbsolutePath().normalize();
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(allowed) || target.equals(allowed)) throw new IOException("非法知识库目录");
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        // Files.walk does not follow symbolic links; shared targets remain untouched.
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}
