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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/** Limits shared by HTTP intake, streaming import and the upload UI. */
@Component
@ConfigurationProperties(prefix = "dataagent.upload")
public class DatasetUploadProperties {
    private DataSize maxFileSize = DataSize.ofMegabytes(50);
    private DataSize maxSelectionSize = DataSize.ofMegabytes(100);
    private int maxFiles = 10;
    private long maxRows = 100000;
    private int maxColumns = 100;
    private long maxCells = 2000000;
    private int concurrentFiles = 8;

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize value) {
        require(value.toBytes() > 0);
        maxFileSize = value;
    }

    public DataSize getMaxSelectionSize() {
        return maxSelectionSize;
    }

    public void setMaxSelectionSize(DataSize value) {
        require(value.toBytes() > 0);
        maxSelectionSize = value;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int value) {
        require(value > 0);
        maxFiles = value;
    }

    public long getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(long value) {
        require(value > 0);
        maxRows = value;
    }

    public int getMaxColumns() {
        return maxColumns;
    }

    public void setMaxColumns(int value) {
        require(value > 0);
        maxColumns = value;
    }

    public long getMaxCells() {
        return maxCells;
    }

    public void setMaxCells(long value) {
        require(value > 0);
        maxCells = value;
    }

    public int getConcurrentFiles() {
        return concurrentFiles;
    }

    public void setConcurrentFiles(int value) {
        require(value > 0 && value <= 32);
        concurrentFiles = value;
    }

    private void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("上传限制必须为正数，并发数不能超过 32");
    }
}
