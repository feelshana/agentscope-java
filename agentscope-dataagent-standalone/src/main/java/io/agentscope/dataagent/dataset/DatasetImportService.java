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

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.dataset.parser.SchemaGenerationService;
import io.agentscope.dataagent.dataset.parser.TypeInferrer;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Streaming file import service backed by EasyExcel. Reads uploaded Excel / CSV files in a
 * streaming fashion, collects per-column sample values, calls the {@link SchemaGenerationService}
 * to generate English field names and descriptions via AI, and batch-inserts rows into the dataset
 * database every {@value #BATCH_SIZE} rows.
 *
 * <p>The import flow:
 *
 * <ol>
 *   <li>Read the header row and capture original column names.
 *   <li>Stream data rows, collecting up to 10 distinct sample values per column.
 *   <li>After the first {@value #BATCH_SIZE} rows, call AI to generate schema metadata, create
 *       the physical table, and insert the buffered rows.
 *   <li>Continue streaming subsequent batches of {@value #BATCH_SIZE} rows, inserting each batch.
 * </ol>
 */
@Service
public class DatasetImportService {
    private KnowledgeBaseOperations operations;

    @org.springframework.beans.factory.annotation.Autowired
    private DatasetUploadProperties uploadProperties = new DatasetUploadProperties();

    private static final Logger log = LoggerFactory.getLogger(DatasetImportService.class);

    /** Number of rows to buffer before each batch insert. */
    static final int BATCH_SIZE = 5000;

    /** Maximum number of distinct sample values to collect per column. */
    static final int MAX_SAMPLES_PER_COLUMN = 10;

    /** Number of threads for concurrent batch inserts. */
    private static final int INSERT_THREADS = 4;

    private final TableProvisioner provisioner;
    private final SchemaGenerationService schemaGenerationService;

    public DatasetImportService(
            TableProvisioner provisioner, SchemaGenerationService schemaGenerationService) {
        this.provisioner = provisioner;
        this.schemaGenerationService = schemaGenerationService;
    }

    /**
     * Import a tabular file (xlsx / xls / csv) into the dataset database.
     *
     * @param ownerId tenant owner id
     * @param datasetId dataset uuid (used for table name generation)
     * @param name friendly dataset name (used for table name generation)
     * @param data file input stream
     * @param fileName original file name (determines parser: xlsx/xls vs csv)
     * @return the import result with generated schema and row counts
     */
    public ImportResult importFile(
            String ownerId, String datasetId, String name, InputStream data, String fileName) {
        return importFileForGroup(null, ownerId, datasetId, name, data, fileName);
    }

    public ImportResult importFileForGroup(
            String groupId,
            String ownerId,
            String datasetId,
            String name,
            InputStream data,
            String fileName) {

        long importStart = System.currentTimeMillis();
        String tableName = TableProvisioner.buildTableName(ownerId, datasetId, name);
        BatchListener listener = new BatchListener(tableName, provisioner, schemaGenerationService);
        listener.operations = operations;
        listener.groupId = groupId;
        listener.limits = uploadProperties;

        try {
            String lower = fileName != null ? fileName.toLowerCase() : "";
            if (lower.endsWith(".csv")) {
                EasyExcel.read(data, listener)
                        .excelType(com.alibaba.excel.support.ExcelTypeEnum.CSV)
                        .sheet()
                        .doRead();
            } else {
                EasyExcel.read(data, listener).sheet().doRead();
            }
            if (listener.getOriginalHeaders().isEmpty() || listener.getTotalRows() == 0) {
                throw new DatasetException("No data rows found in file: " + fileName);
            }
        } catch (RuntimeException failure) {
            boolean interrupted = Thread.interrupted();
            interrupted |= listener.abortAndDrain();
            try {
                if (listener.tableAttempted) cleanupFailedImport(groupId, tableName);
                else if (operations != null && groupId != null)
                    operations.forgetImportedTable(groupId, tableName);
            } catch (RuntimeException cleanup) {
                cleanup.addSuppressed(failure);
                throw new DatasetException("导入失败，临时表待清理：" + tableName, cleanup);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            throw importFailure(failure);
        } finally {
            listener.shutdownExecutor();
        }

        log.info(
                "DatasetImportService: import completed for {} — {} rows, {} ms total",
                fileName,
                listener.getTotalRows(),
                System.currentTimeMillis() - importStart);

        return new ImportResult(
                listener.getColumns(),
                listener.getTotalRows(),
                listener.getTableDescription(),
                tableName,
                listener.sampleSummary());
    }

    public void cleanupFailedImport(String groupId, String tableName) {
        provisioner.dropTableStrict(tableName);
        if (operations != null && groupId != null)
            operations.forgetImportedTable(groupId, tableName);
    }

    static DatasetException importFailure(RuntimeException failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof DatasetException domain) return domain;
            cause = cause.getCause();
        }
        return new DatasetException("文件解析或入库失败：" + failure.getMessage(), failure);
    }

    // -----------------------------------------------------------------
    //  EasyExcel read listener
    // -----------------------------------------------------------------

    /**
     * Streaming listener that collects samples, generates schema via AI after the first batch,
     * creates the table, and inserts rows in batches of {@value #BATCH_SIZE}.
     */
    static class BatchListener extends AnalysisEventListener<Map<Integer, String>> {
        private KnowledgeBaseOperations operations;
        private String groupId;

        private final String tableName;
        private final TableProvisioner provisioner;
        private final SchemaGenerationService schemaGenerationService;
        private final ExecutorService insertExecutor;
        private final Deque<CompletableFuture<Void>> pendingInserts = new ArrayDeque<>();
        private DatasetUploadProperties limits = new DatasetUploadProperties();
        private volatile boolean aborting;
        private boolean tableAttempted;
        private long batchNumber, bufferedChars;

        // Header info
        private List<String> originalHeaders;
        private int columnCount;

        // Sample collection: column header -> distinct sample values (up to MAX_SAMPLES_PER_COLUMN)
        private Map<String, Set<String>> sampleSets;

        // Batch buffer
        private List<List<String>> batch = new ArrayList<>();
        private long totalRows = 0;
        private boolean tableCreated = false;
        private List<ColumnSchema> columns;
        private String tableDescription;

        BatchListener(
                String tableName,
                TableProvisioner provisioner,
                SchemaGenerationService schemaGenerationService) {
            this.tableName = tableName;
            this.provisioner = provisioner;
            this.schemaGenerationService = schemaGenerationService;
            this.insertExecutor = Executors.newFixedThreadPool(INSERT_THREADS);
        }

        @Override
        public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
            columnCount = headMap.keySet().stream().mapToInt(i -> i + 1).max().orElse(0);
            if (columnCount == 0) throw new DatasetException("文件表头为空");
            if (columnCount > limits.getMaxColumns())
                throw new DatasetException("文件列数超过限制：" + limits.getMaxColumns(), 413);
            originalHeaders = new ArrayList<>(columnCount);
            sampleSets = new LinkedHashMap<>();
            for (int i = 0; i < columnCount; i++) {
                String header = headMap.get(i);
                if (header == null || header.isBlank()) {
                    header = "col_" + (i + 1);
                }
                originalHeaders.add(header.trim());
                sampleSets.put(originalHeaders.get(i), new LinkedHashSet<>());
            }
        }

        @Override
        public void invoke(Map<Integer, String> rowData, AnalysisContext context) {
            if (Thread.currentThread().isInterrupted()) throw new DatasetException("导入已取消", 409);
            int observedColumns = rowData.keySet().stream().mapToInt(i -> i + 1).max().orElse(0);
            if (observedColumns > columnCount) throw new DatasetException("数据列超出表头范围，请检查文件");
            totalRows++;
            if (totalRows > limits.getMaxRows())
                throw new DatasetException("文件行数超过限制：" + limits.getMaxRows(), 413);
            if (totalRows > limits.getMaxCells() / columnCount)
                throw new DatasetException("文件总单元格数超过限制：" + limits.getMaxCells(), 413);
            reapCompleted();

            // Collect sample values (up to MAX_SAMPLES_PER_COLUMN distinct per column)
            for (int i = 0; i < columnCount; i++) {
                String val = rowData.get(i);
                if (val != null && !val.isBlank()) {
                    val = val.trim();
                    Set<String> samples = sampleSets.get(originalHeaders.get(i));
                    if (samples != null && samples.size() < MAX_SAMPLES_PER_COLUMN) {
                        samples.add(val);
                    }
                }
            }

            // Build row
            List<String> row = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                String val = rowData.get(i);
                row.add(val == null || val.isBlank() ? null : val.trim());
                if (val != null) bufferedChars += val.length();
            }
            batch.add(row);

            // Flush when batch is full
            if (batch.size() >= BATCH_SIZE || bufferedChars >= 1000000) {
                if (!tableCreated) {
                    createTableAndInsertFirstBatch();
                } else {
                    flushBatchAsync();
                }
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // Flush remaining rows
            if (!batch.isEmpty()) {
                if (!tableCreated) {
                    createTableAndInsertFirstBatch();
                } else {
                    flushBatchAsync();
                }
            }
            // Wait for all pending inserts to complete
            waitForPendingInserts();
        }

        void shutdownExecutor() {
            insertExecutor.shutdown();
        }

        boolean abortAndDrain() {
            aborting = true;
            insertExecutor.shutdown();
            boolean interrupted = false;
            while (!insertExecutor.isTerminated()) {
                try {
                    insertExecutor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            return interrupted;
        }

        private void await(CompletableFuture<?> future) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DatasetException("导入已取消", 409, e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new DatasetException("批量导入失败", e.getCause());
            }
        }

        private void reapCompleted() {
            var iterator = pendingInserts.iterator();
            while (iterator.hasNext()) {
                var task = iterator.next();
                if (task.isDone()) {
                    await(task);
                    iterator.remove();
                }
            }
        }

        private void createTableAndInsertFirstBatch() {
            log.info(
                    "DatasetImportService: first batch ready ({} rows, {} cols), generating"
                            + " schema...",
                    batch.size(),
                    columnCount);
            // Convert sample sets to lists
            Map<String, List<String>> sampleLists = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : sampleSets.entrySet()) {
                sampleLists.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }

            // Call AI to generate schema
            long schemaStart = System.currentTimeMillis();
            SchemaGenerationService.SchemaResult schema =
                    schemaGenerationService.generateSchema(originalHeaders, sampleLists);
            long schemaElapsed = System.currentTimeMillis() - schemaStart;
            log.info("DatasetImportService: schema generation completed in {} ms", schemaElapsed);

            // Build ColumnSchema list
            columns = new ArrayList<>(columnCount);
            for (int i = 0; i < columnCount; i++) {
                SchemaGenerationService.ColumnInfo aiCol = schema.columns().get(i);
                String englishName = aiCol.englishName();
                String description = aiCol.description();

                // Infer SQL type from sample values
                List<String> samples = sampleLists.getOrDefault(originalHeaders.get(i), List.of());
                String sqlType = TypeInferrer.infer(samples);

                columns.add(
                        new ColumnSchema(
                                englishName, originalHeaders.get(i), sqlType, true, description));
            }

            tableDescription = schema.tableDescription();

            // Create table
            long createStart = System.currentTimeMillis();
            log.info("DatasetImportService: creating table {}", tableName);
            tableAttempted = true;
            provisioner.createTable(tableName, columns);
            log.info(
                    "DatasetImportService: table {} created in {} ms, inserting first batch",
                    tableName,
                    System.currentTimeMillis() - createStart);
            tableCreated = true;

            // Insert first batch synchronously (table was just created)
            flushBatchSync();
        }

        private void flushBatchAsync() {
            if (batch.isEmpty() || columns == null) {
                return;
            }
            reapCompleted();
            while (pendingInserts.size() >= INSERT_THREADS) {
                await(CompletableFuture.anyOf(pendingInserts.toArray(new CompletableFuture[0])));
                reapCompleted();
            }
            final List<List<String>> batchToInsert = batch;
            final List<ColumnSchema> cols = columns;
            final long batchIndex = ++batchNumber;
            batch = new ArrayList<>();
            bufferedChars = 0;

            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(
                            () -> {
                                if (aborting) return;
                                if (operations != null && groupId != null) {
                                    operations.run(
                                            groupId,
                                            () -> {
                                                provisioner.bulkInsert(
                                                        tableName, cols, batchToInsert);
                                                return null;
                                            });
                                    return;
                                }
                                long start = System.currentTimeMillis();
                                provisioner.bulkInsert(tableName, cols, batchToInsert);
                                log.info(
                                        "DatasetImportService: async batch #{} inserted {} rows"
                                                + " into {} in {} ms",
                                        batchIndex,
                                        batchToInsert.size(),
                                        tableName,
                                        System.currentTimeMillis() - start);
                            },
                            insertExecutor);
            pendingInserts.add(future);
        }

        private void flushBatchSync() {
            if (batch.isEmpty() || columns == null) {
                return;
            }
            long start = System.currentTimeMillis();
            int rowCount = batch.size();
            provisioner.bulkInsert(tableName, columns, batch);
            log.info(
                    "DatasetImportService: sync batch inserted {} rows into {} in {} ms",
                    rowCount,
                    tableName,
                    System.currentTimeMillis() - start);
            batch = new ArrayList<>();
            bufferedChars = 0;
        }

        private void waitForPendingInserts() {
            if (pendingInserts.isEmpty()) {
                return;
            }
            log.info(
                    "DatasetImportService: waiting for {} pending batch inserts to complete",
                    pendingInserts.size());
            while (!pendingInserts.isEmpty()) await(pendingInserts.removeFirst());
            log.info("DatasetImportService: all batch inserts completed");
        }

        // Accessors for the result
        DatasetSampling.Summary sampleSummary() {
            List<DatasetSampling.ColumnSample> samples = new ArrayList<>();
            for (ColumnSchema column : getColumns()) {
                var values = sampleSets.getOrDefault(column.originalName(), Set.of());
                samples.add(
                        new DatasetSampling.ColumnSample(
                                column.name(),
                                values.stream()
                                        .map(v -> v.length() > 256 ? v.substring(0, 256) : v)
                                        .toList(),
                                -1,
                                -1,
                                true,
                                false));
            }
            return new DatasetSampling.Summary(
                    1,
                    "SUCCESS",
                    java.time.Instant.now().toString(),
                    (int) Math.min(totalRows, Integer.MAX_VALUE),
                    0,
                    "文件流式导入期间收集每列最多10个非空样例；NULL数和基数未统计；非完整枚举",
                    samples);
        }

        List<String> getOriginalHeaders() {
            return originalHeaders != null ? originalHeaders : List.of();
        }

        List<ColumnSchema> getColumns() {
            return columns != null ? columns : List.of();
        }

        long getTotalRows() {
            return totalRows;
        }

        String getTableDescription() {
            return tableDescription;
        }
    }

    // -----------------------------------------------------------------
    //  Result record
    // -----------------------------------------------------------------

    /** Result of a streaming file import. */
    public record ImportResult(
            List<ColumnSchema> columns,
            long totalRows,
            String tableDescription,
            String tableName,
            DatasetSampling.Summary sampling) {
        public ImportResult(
                List<ColumnSchema> columns,
                long totalRows,
                String tableDescription,
                String tableName) {
            this(columns, totalRows, tableDescription, tableName, null);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DatasetImportService(
            TableProvisioner provisioner,
            SchemaGenerationService schemaGenerationService,
            KnowledgeBaseOperations operations) {
        this(provisioner, schemaGenerationService);
        this.operations = operations;
    }
}
