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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Process-wide admission; a disconnected client does not release a running worker's lease. */
@Component
public class DatasetUploadAdmission {
    private final DatasetUploadProperties properties;
    private int active;
    private final Map<String, Lease> names = new LinkedHashMap<>();
    private final Map<String, Lease> recent = new LinkedHashMap<>();

    public DatasetUploadAdmission(DatasetUploadProperties properties) {
        this.properties = properties;
    }

    public record Status(
            String uploadId,
            String name,
            String fileName,
            String phase,
            String datasetId,
            String error,
            boolean active) {}

    public synchronized Lease acquire(String user, String group, String name, String uploadId) {
        prune();
        String key = group + ":" + name.trim().toLowerCase(Locale.ROOT);
        if (names.containsKey(key)) throw new DatasetException("该数据集正在导入，请勿重复提交", 409);
        if (active >= properties.getConcurrentFiles())
            throw new DatasetException("当前导入名额已满，请稍后重试", 429);
        String id =
                uploadId == null || uploadId.isBlank() ? UUID.randomUUID().toString() : uploadId;
        if (!id.matches("[a-zA-Z0-9_-]{1,80}")) throw new DatasetException("无效的上传编号");
        if (recent.containsKey(id)) throw new DatasetException("此上传编号已使用，请刷新确认结果", 409);
        Lease lease = new Lease(user, group, name, id, key);
        names.put(key, lease);
        recent.put(id, lease);
        active++;
        return lease;
    }

    public synchronized List<Status> statuses(String user, String group) {
        prune();
        return recent.values().stream()
                .filter(x -> x.user.equals(user) && x.group.equals(group))
                .map(Lease::snapshot)
                .toList();
    }

    public synchronized void published(String user, String group, List<String> ids, String error) {
        for (Lease item : recent.values())
            if (item.user.equals(user)
                    && item.group.equals(group)
                    && ids.contains(item.datasetId)) {
                item.phase = error == null ? "COMPLETED" : "IMPORTED_MODEL_PENDING";
                item.error = error;
            }
    }

    public synchronized void publishing(String user, String group, List<String> ids) {
        for (Lease item : recent.values()) {
            if (item.user.equals(user)
                    && item.group.equals(group)
                    && ids.contains(item.datasetId)) {
                item.phase = "PUBLISHING";
                item.error = null;
            }
        }
    }

    public synchronized int activeCount() {
        return active;
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - 30 * 60 * 1000;
        recent.values().removeIf(x -> x.released && x.finishedAt < cutoff);
        for (String id : new ArrayList<>(recent.keySet())) {
            if (recent.size() < 2000) break;
            if (recent.get(id).released) recent.remove(id);
        }
        if (recent.size() >= 2000) throw new DatasetException("当前上传任务过多，请稍后重试", 429);
    }

    public final class Lease implements AutoCloseable {
        private final String user, group, name, id, key;
        private String phase = "UPLOADING", datasetId, error, fileName;
        private boolean running, released;
        private long finishedAt;

        private Lease(String user, String group, String name, String id, String key) {
            this.user = user;
            this.group = group;
            this.fileName = name;
            this.name = name;
            this.id = id;
            this.key = key;
        }

        public void filename(String value) {
            synchronized (DatasetUploadAdmission.this) {
                fileName = value;
            }
        }

        public boolean start() {
            synchronized (DatasetUploadAdmission.this) {
                if (released) return false;
                running = true;
                phase = "IMPORTING";
                return true;
            }
        }

        public void saved(String id) {
            synchronized (DatasetUploadAdmission.this) {
                datasetId = id;
                phase = "PUBLISHING";
            }
        }

        public void completed() {
            synchronized (DatasetUploadAdmission.this) {
                phase = "COMPLETED";
            }
        }

        public void failed(Throwable failure) {
            synchronized (DatasetUploadAdmission.this) {
                error = failure.getMessage();
                phase = datasetId == null ? "FAILED" : "IMPORTED_MODEL_PENDING";
            }
        }

        public void cancelIntake() {
            synchronized (DatasetUploadAdmission.this) {
                if (!running && !released) {
                    if (phase.equals("UPLOADING")) phase = "CANCELLED";
                    close();
                }
            }
        }

        public Status snapshot() {
            synchronized (DatasetUploadAdmission.this) {
                return new Status(id, name, fileName, phase, datasetId, error, !released);
            }
        }

        public void close() {
            synchronized (DatasetUploadAdmission.this) {
                if (!released) {
                    if (phase.equals("PUBLISHING")) {
                        phase = "IMPORTED_MODEL_PENDING";
                        if (error == null) error = "数据已保存，待检查与发布";
                    }
                    released = true;
                    running = false;
                    finishedAt = System.currentTimeMillis();
                    names.remove(key, this);
                    active--;
                }
            }
        }
    }
}
