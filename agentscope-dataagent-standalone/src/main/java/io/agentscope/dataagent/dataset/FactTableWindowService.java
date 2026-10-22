package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowEntity;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves sampled external temporal tables and persists only user-confirmed windows. */
@Service
public class FactTableWindowService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasets;
    private final FactQueryWindowRepository windows;
    private final KnowledgeBaseAccessService access;

    public FactTableWindowService(
            io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasets,
            FactQueryWindowRepository windows) {
        this(datasets, windows, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FactTableWindowService(
            io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasets,
            FactQueryWindowRepository windows,
            KnowledgeBaseAccessService access) {
        this.datasets = datasets;
        this.windows = windows;
        this.access = access;
    }

    public record Candidate(
            String datasetId,
            String tableName,
            List<String> temporalColumns,
            String mode,
            Integer windowDays,
            String timeColumn,
            String tableRole) {}

    public List<Candidate> candidates(String groupId) {
        List<Candidate> result = new ArrayList<>();
        for (DatasetEntity dataset : datasets.findByGroupId(groupId)) {
            if (!"datasource".equalsIgnoreCase(dataset.getOrigin())) continue;
            String role =
                    DatasetSampling.role(DatasetSampling.decode(dataset.getSampleSummaryJson()));
            if ("DIMENSION".equals(role)) continue;
            List<String> columns = temporalColumns(dataset);
            if (columns.isEmpty()) continue;
            var saved = windows.findByGroupIdAndDatasetId(groupId, dataset.getId());
            result.add(
                    new Candidate(
                            dataset.getId(),
                            dataset.getName(),
                            columns,
                            saved.map(FactQueryWindowEntity::getMode).orElse(null),
                            saved.map(FactQueryWindowEntity::getWindowDays).orElse(null),
                            saved.map(this::selectedColumn)
                                    .orElse(columns.size() == 1 ? columns.get(0) : null),
                            DatasetSampling.role(
                                    DatasetSampling.decode(dataset.getSampleSummaryJson()))));
        }
        return result;
    }

    @Transactional
    public Candidate save(
            String actor, String groupId, String datasetId, String mode, Integer days) {
        return save(actor, groupId, datasetId, mode, days, null);
    }

    @Transactional
    public Candidate save(
            String actor,
            String groupId,
            String datasetId,
            String mode,
            Integer days,
            String timeColumn) {
        if (!"LIMITED".equals(mode) && !"UNLIMITED".equals(mode))
            throw new DatasetException("时间范围模式只能是 LIMITED 或 UNLIMITED", 400);
        if ("LIMITED".equals(mode)
                && (days == null || (days != 7 && days != 14 && days != 31 && days != 90)))
            throw new DatasetException("时间范围只能选择 7、14、31 或 90 天", 400);
        if (access != null) access.requireManage(actor, groupId);
        DatasetEntity dataset =
                datasets.lockById(datasetId)
                        .filter(
                                d ->
                                        groupId.equals(d.getGroupId())
                                                && "datasource".equalsIgnoreCase(d.getOrigin()))
                        .orElseThrow(() -> new DatasetException("外部数据表不存在", 404));
        if (access != null) access.requireManage(actor, groupId);
        if (!"FACT"
                .equals(
                        DatasetSampling.role(
                                DatasetSampling.decode(dataset.getSampleSummaryJson()))))
            throw new DatasetException("请先确认该表为事实表；维度表不需要时间范围配置", 422);
        List<String> columns = temporalColumns(dataset);
        if (columns.isEmpty()) throw new DatasetException("该表没有采样识别出的数据时间字段", 400);
        if (timeColumn == null && columns.size() == 1) timeColumn = columns.get(0);
        if ("LIMITED".equals(mode) && (timeColumn == null || !columns.contains(timeColumn)))
            throw new DatasetException("请选择采样识别出的一个数据时间字段", 400);
        List<String> selected = timeColumn == null ? columns : List.of(timeColumn);
        String json;
        try {
            json = JSON.writeValueAsString(selected);
        } catch (Exception e) {
            throw new DatasetException("保存时间字段失败", e);
        }
        FactQueryWindowEntity entity =
                windows.findByGroupIdAndDatasetId(groupId, datasetId)
                        .orElseGet(
                                () ->
                                        new FactQueryWindowEntity(
                                                UUID.randomUUID().toString(),
                                                groupId,
                                                datasetId,
                                                dataset.getName(),
                                                json,
                                                mode,
                                                "LIMITED".equals(mode) ? days : null,
                                                actor));
        if (entity.getId() != null && windows.existsById(entity.getId()))
            entity.update(json, mode, "LIMITED".equals(mode) ? days : null, actor);
        windows.save(entity);
        return new Candidate(
                datasetId,
                dataset.getName(),
                columns,
                mode,
                "LIMITED".equals(mode) ? days : null,
                timeColumn,
                "FACT");
    }

    /** Persists role confirmation in sampling metadata, independently of the Wren project. */
    @Transactional
    public void confirmRole(String actor, String groupId, String datasetId, String role) {
        if (!java.util.Set.of("FACT", "DIMENSION").contains(java.util.Objects.toString(role, "")))
            throw new DatasetException("表角色只能选择 FACT 或 DIMENSION", 400);
        if (access != null) access.requireManage(actor, groupId);
        DatasetEntity dataset =
                datasets.lockById(datasetId)
                        .filter(
                                d ->
                                        groupId.equals(d.getGroupId())
                                                && "datasource".equalsIgnoreCase(d.getOrigin()))
                        .orElseThrow(() -> new DatasetException("外部数据表不存在", 404));
        if (access != null) access.requireManage(actor, groupId);
        var summary = DatasetSampling.decode(dataset.getSampleSummaryJson());
        if (summary == null || summary.columns() == null || summary.columns().isEmpty())
            throw new DatasetException("缺少采样摘要，请先刷新采样", 422);
        dataset.setSampleSummaryJson(
                DatasetSampling.encode(DatasetSampling.withRole(summary, role, true)));
        datasets.save(dataset);
        if ("DIMENSION".equals(role))
            windows.findByGroupIdAndDatasetId(groupId, datasetId).ifPresent(windows::delete);
    }

    private String selectedColumn(FactQueryWindowEntity entity) {
        try {
            var node = JSON.readTree(entity.getTemporalColumnsJson());
            return node.isArray() && node.size() == 1 ? node.get(0).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> temporalColumns(DatasetEntity dataset) {
        DatasetSampling.Summary summary = DatasetSampling.decode(dataset.getSampleSummaryJson());
        if (summary == null || summary.columns() == null) return List.of();
        return summary.columns().stream()
                .filter(c -> c.dateFormat() != null)
                .map(DatasetSampling.ColumnSample::name)
                .toList();
    }
}
