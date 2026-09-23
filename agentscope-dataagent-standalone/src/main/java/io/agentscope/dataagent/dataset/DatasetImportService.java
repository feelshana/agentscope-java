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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

        long importStart = System.currentTimeMillis();
        String tableName = TableProvisioner.buildTableName(ownerId, datasetId, name);
        BatchListener listener = new BatchListener(tableName, provisioner, schemaGenerationService);

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
        } finally {
            listener.shutdownExecutor();
        }

        if (listener.getOriginalHeaders().isEmpty()) {
            throw new DatasetException("No data found in file: " + fileName);
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
                tableName);
    }

    // -----------------------------------------------------------------
    //  EasyExcel read listener
    // -----------------------------------------------------------------

    /**
     * Streaming listener that collects samples, generates schema via AI after the first batch,
     * creates the table, and inserts rows in batches of {@value #BATCH_SIZE}.
     */
    static class BatchListener extends AnalysisEventListener<Map<Integer, String>> {

        private final String tableName;
        private final TableProvisioner provisioner;
        private final SchemaGenerationService schemaGenerationService;
        private final ExecutorService insertExecutor;
        private final List<CompletableFuture<Void>> pendingInserts = new ArrayList<>();

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
            columnCount = headMap.size();
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
            totalRows++;

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
            }
            batch.add(row);

            // Flush when batch is full
            if (batch.size() >= BATCH_SIZE) {
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
            final List<List<String>> batchToInsert = batch;
            final List<ColumnSchema> cols = columns;
            final int batchIndex = pendingInserts.size() + 1;
            batch = new ArrayList<>();

            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(
                            () -> {
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
        }

        private void waitForPendingInserts() {
            if (pendingInserts.isEmpty()) {
                return;
            }
            log.info(
                    "DatasetImportService: waiting for {} pending batch inserts to complete",
                    pendingInserts.size());
            CompletableFuture.allOf(pendingInserts.toArray(new CompletableFuture[0])).join();
            log.info("DatasetImportService: all batch inserts completed");
        }

        // Accessors for the result
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
            String tableName) {}
}
