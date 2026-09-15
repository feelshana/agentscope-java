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
package io.agentscope.dataagent.semantic.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.semantic.model.CubeMeasure;
import io.agentscope.dataagent.semantic.model.CubeQuery;
import io.agentscope.dataagent.semantic.model.SemanticCube;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.model.SemanticModelTable;
import io.agentscope.dataagent.web.persistence.jpa.QueryHistoryEntity;
import io.agentscope.dataagent.web.persistence.jpa.QueryHistoryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 查询历史管理服务。实现：
 * <ul>
 *   <li>存储成功查询对（NL → JSON）</li>
 *   <li>召回相似历史查询作为 few-shot 示例</li>
 *   <li>Seed Queries 自动生成</li>
 * </ul>
 */
@Service
public class QueryHistoryService {

    private static final Logger log = LoggerFactory.getLogger(QueryHistoryService.class);
    private static final int DEFAULT_RECALL_LIMIT = 5;

    private final QueryHistoryRepository repository;
    private final ObjectMapper mapper;

    public QueryHistoryService(QueryHistoryRepository repository) {
        this.repository = repository;
        this.mapper = new ObjectMapper();
    }

    /**
     * 存储成功的查询对。
     */
    @Transactional
    public void store(String groupId, String nlQuery, String queryJson, String sqlGenerated) {
        QueryHistoryEntity entity = new QueryHistoryEntity();
        entity.setGroupId(groupId);
        entity.setNlQuery(nlQuery);
        entity.setQueryJson(queryJson);
        entity.setSqlGenerated(sqlGenerated);
        entity.setSource("user");
        entity.setCreatedAt(Instant.now());
        repository.save(entity);
    }

    /**
     * 召回相似历史查询。当前使用关键词 LIKE 匹配，后续可升级为向量搜索。
     *
     * @param groupId 知识库 ID
     * @param question 用户问题
     * @param limit 最多返回几条
     * @return 格式化的召回文本
     */
    public String recallSimilarQueries(String groupId, String question, int limit) {
        int maxResults = limit <= 0 ? DEFAULT_RECALL_LIMIT : limit;

        // 提取关键词（简单按空格分割）
        String[] keywords = question.split("[\\s,，、]+");
        List<QueryHistoryEntity> results = new ArrayList<>();

        for (String keyword : keywords) {
            if (keyword.length() < 2) {
                continue;
            }
            List<QueryHistoryEntity> hits = repository.findByGroupIdAndKeyword(groupId, keyword);
            for (QueryHistoryEntity hit : hits) {
                if (!results.contains(hit) && results.size() < maxResults) {
                    results.add(hit);
                }
            }
        }

        // 如果关键词匹配不足，补充最近的查询
        if (results.size() < maxResults) {
            List<QueryHistoryEntity> recent =
                    repository.findTop20ByGroupIdOrderByCreatedAtDesc(groupId);
            for (QueryHistoryEntity r : recent) {
                if (!results.contains(r) && results.size() < maxResults) {
                    results.add(r);
                }
            }
        }

        if (results.isEmpty()) {
            return "暂无历史查询记录。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 相似历史查询（").append(results.size()).append(" 条）\n\n");
        for (int i = 0; i < results.size(); i++) {
            QueryHistoryEntity r = results.get(i);
            sb.append(i + 1).append(". **问题**: ").append(r.getNlQuery()).append("\n");
            sb.append("   **查询JSON**: `").append(truncate(r.getQueryJson(), 200)).append("`\n");
            if (r.getSqlGenerated() != null) {
                sb.append("   **SQL**: ```sql\n").append(r.getSqlGenerated()).append("\n```\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 从语义模型自动生成 Seed Queries（基础 NL-SQL 对）。
     * 参考 WrenAI 的 seed_queries.py。
     */
    @Transactional
    public int generateSeedQueries(String groupId, SemanticModel model) {
        if (model == null) {
            return 0;
        }

        int count = 0;

        // 基本列出查询
        if (model.getModels() != null) {
            for (SemanticModelTable table : model.getModels()) {
                String nl = "列出所有 " + table.getName();
                if (table.getLabel() != null) {
                    nl = "列出所有" + table.getLabel();
                }
                String sql = "SELECT * FROM " + table.getTableName() + " LIMIT 100";
                saveSeed(groupId, nl, sql);
                count++;

                // 简单聚合（对每个非主键数值列）
                if (table.getColumns() != null) {
                    for (var col : table.getColumns()) {
                        if (isNumericType(col.getType())
                                && !col.getName().equals(table.getPrimaryKey())
                                && !col.getName().toLowerCase().endsWith("_id")) {
                            String aggNl =
                                    (table.getLabel() != null ? table.getLabel() : table.getName())
                                            + " 的 "
                                            + col.getLabel()
                                            + " 合计";
                            String aggSql =
                                    "SELECT SUM("
                                            + col.getName()
                                            + ") AS total_"
                                            + col.getName()
                                            + " FROM "
                                            + table.getTableName();
                            saveSeed(groupId, aggNl, aggSql);
                            count++;
                        }
                    }
                }
            }
        }

        // Cube 聚合查询
        if (model.getCubes() != null) {
            for (SemanticCube cube : model.getCubes()) {
                if (cube.getMeasures() != null && !cube.getMeasures().isEmpty()) {
                    for (CubeMeasure measure : cube.getMeasures()) {
                        String nl =
                                cube.getLabel() != null
                                        ? cube.getLabel() + " 的 " + measure.getName()
                                        : cube.getName() + " 的 " + measure.getName();

                        CubeQuery query = new CubeQuery();
                        query.setType("cube_query");
                        query.setCube(cube.getName());
                        query.setMeasures(List.of(measure.getName()));
                        try {
                            String json = mapper.writeValueAsString(query);
                            saveSeed(groupId, nl, json);
                            count++;
                        } catch (Exception e) {
                            log.warn("Seed query JSON 序列化失败", e);
                        }
                    }
                }
            }
        }

        log.info("QueryHistoryService: 为 group {} 生成 {} 条 seed queries", groupId, count);
        return count;
    }

    private void saveSeed(String groupId, String nl, String jsonOrSql) {
        QueryHistoryEntity entity = new QueryHistoryEntity();
        entity.setGroupId(groupId);
        entity.setNlQuery(nl);
        entity.setQueryJson(jsonOrSql);
        entity.setSource("seed");
        entity.setCreatedAt(Instant.now());
        repository.save(entity);
    }

    /**
     * 获取指定 group 的查询历史数量。
     */
    public long countByGroup(String groupId) {
        return repository.findByGroupIdOrderByCreatedAtDesc(groupId).size();
    }

    private boolean isNumericType(String type) {
        if (type == null) return false;
        String upper = type.toUpperCase();
        return upper.contains("INT")
                || upper.contains("DOUBLE")
                || upper.contains("DECIMAL")
                || upper.contains("FLOAT")
                || upper.contains("BIGINT");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
