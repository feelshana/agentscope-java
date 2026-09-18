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
package io.agentscope.dataagent.dataset.parser;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.dataset.Identifiers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Uses the configured LLM to generate English field names, field descriptions (including date
 * format annotations), and a table-level description from the Chinese column headers and sample
 * values collected during the first batch of an EasyExcel streaming import.
 *
 * <p>When no {@link Model} bean is available the service falls back to a rule-based heuristic that
 * produces reasonable defaults so the import never fails due to a missing model.
 */
@Service
public class SchemaGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SchemaGenerationService.class);

    private static final int SAMPLE_VALUES_FOR_AI = 3;

    private final Optional<Model> model;

    public SchemaGenerationService(Optional<Model> model) {
        this.model = model;
    }

    // -----------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------

    /**
     * Generate enriched column metadata and a table description.
     *
     * @param originalHeaders Chinese (or original) column header names
     * @param columnSamples per-column list of up to 10 distinct sample values
     * @return result containing AI-generated (or fallback) column info and table description
     */
    public SchemaResult generateSchema(
            List<String> originalHeaders, Map<String, List<String>> columnSamples) {

        if (model.isPresent()) {
            try {
                return generateWithAi(originalHeaders, columnSamples);
            } catch (Exception e) {
                log.warn("AI schema generation failed, falling back to rules: {}", e.getMessage());
            }
        }
        return generateWithRules(originalHeaders, columnSamples);
    }

    // -----------------------------------------------------------------
    //  AI-powered generation
    // -----------------------------------------------------------------

    private SchemaResult generateWithAi(
            List<String> originalHeaders, Map<String, List<String>> columnSamples) {

        String prompt = buildPrompt(originalHeaders, columnSamples);
        log.info("SchemaGenerationService: calling AI model for schema generation...");
        log.info("SchemaGenerationService: [PROMPT]\n{}", prompt);
        long aiStart = System.currentTimeMillis();
        Msg userMsg = new UserMessage((String) null, prompt);
        List<String> chunks =
                model.get().stream(List.of(userMsg), null, null)
                        .map(
                                cr ->
                                        cr.getContent().stream()
                                                .filter(TextBlock.class::isInstance)
                                                .map(TextBlock.class::cast)
                                                .map(TextBlock::getText)
                                                .reduce("", (a, b) -> a + b))
                        .collectList()
                        .block(Duration.ofSeconds(60));
        long aiElapsed = System.currentTimeMillis() - aiStart;
        if (chunks == null || chunks.isEmpty()) {
            throw new RuntimeException("AI model returned empty response");
        }
        String response = String.join("", chunks);
        log.info(
                "SchemaGenerationService: AI model returned response ({} chars) in {} ms",
                response.length(),
                aiElapsed);
        log.info("SchemaGenerationService: [RESPONSE]\n{}", response);

        return parseAiResponse(response, originalHeaders);
    }

    private String buildPrompt(
            List<String> originalHeaders, Map<String, List<String>> columnSamples) {

        StringBuilder sb = new StringBuilder();
        sb.append("你是一个数据库建模专家。请根据以下 Excel 列名和样本值，为每个列生成英文字段名和字段描述。\n\n");
        sb.append("要求：\n");
        sb.append("1. 英文字段名使用 snake_case 格式，简洁明了\n");
        sb.append("2. 字段描述用中文，简明扼要\n");
        sb.append("3. 对于日期类型字段，在描述中注明日期格式，如：格式为'yyyyMMdd'\n");
        sb.append("4. 描述示例：order_day（英文字段名）：格式为'yyyy-MM-dd'，包含2026-10-01,2026-10-02等\n");
        sb.append("5. 最后生成一句表描述，概括该表的数据内容和用途\n\n");
        sb.append("请按以下 JSON 格式返回：\n");
        sb.append(
                "{\"columns\":[{\"original\":\"原始列名\",\"english\":\"english_name\",\"description\":\"字段描述\"}],\"tableDescription\":\"表描述\"}\n\n");
        sb.append("列信息：\n");
        for (String header : originalHeaders) {
            sb.append("- 列名: ").append(header);
            List<String> samples = columnSamples.getOrDefault(header, List.of());
            if (!samples.isEmpty()) {
                List<String> top3 =
                        samples.subList(0, Math.min(SAMPLE_VALUES_FOR_AI, samples.size()));
                sb.append("，样本值: ").append(String.join(", ", top3));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Parse the AI response JSON. Tolerant of markdown code fences and partial JSON. Falls back to
     * rule-based for any column that cannot be parsed.
     */
    @SuppressWarnings("unchecked")
    private SchemaResult parseAiResponse(String response, List<String> originalHeaders) {
        // Strip markdown code fences if present
        String json = response.trim();
        if (json.contains("```")) {
            Pattern fence = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);
            Matcher m = fence.matcher(json);
            if (m.find()) {
                json = m.group(1).trim();
            }
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> parsed = mapper.readValue(json, Map.class);

            // Build column info map keyed by original name
            Map<String, String[]> aiColumns = new LinkedHashMap<>();
            List<Map<String, String>> cols =
                    (List<Map<String, String>>) parsed.getOrDefault("columns", List.of());
            for (Map<String, String> col : cols) {
                String orig = col.get("original");
                if (orig != null) {
                    aiColumns.put(
                            orig,
                            new String[] {
                                col.getOrDefault("english", ""), col.getOrDefault("description", "")
                            });
                }
            }

            String tableDesc = (String) parsed.getOrDefault("tableDescription", "");

            // Build result aligned with originalHeaders
            List<ColumnInfo> columnInfos = new ArrayList<>();
            for (String header : originalHeaders) {
                String[] ai = aiColumns.get(header);
                if (ai != null && ai[0] != null && !ai[0].isBlank()) {
                    columnInfos.add(new ColumnInfo(ai[0], ai[1]));
                } else {
                    // fallback for this column
                    ColumnInfo fb = fallbackColumn(header, List.of());
                    columnInfos.add(fb);
                }
            }

            return new SchemaResult(columnInfos, tableDesc);
        } catch (Exception e) {
            log.warn(
                    "Failed to parse AI response as JSON, falling back to rules: {}",
                    e.getMessage());
            return generateWithRules(originalHeaders, Map.of());
        }
    }

    // -----------------------------------------------------------------
    //  Rule-based fallback
    // -----------------------------------------------------------------

    private SchemaResult generateWithRules(
            List<String> originalHeaders, Map<String, List<String>> columnSamples) {

        List<ColumnInfo> columnInfos = new ArrayList<>();
        for (String header : originalHeaders) {
            List<String> samples = columnSamples.getOrDefault(header, List.of());
            columnInfos.add(fallbackColumn(header, samples));
        }

        // Build a simple table description from column names
        StringBuilder tableDesc = new StringBuilder();
        tableDesc.append("包含以下字段：");
        for (int i = 0; i < originalHeaders.size(); i++) {
            if (i > 0) {
                tableDesc.append("、");
            }
            tableDesc.append(originalHeaders.get(i));
        }
        return new SchemaResult(columnInfos, tableDesc.toString());
    }

    private ColumnInfo fallbackColumn(String header, List<String> samples) {
        String english = Identifiers.sanitize(header, "col");
        String description = header;

        // Detect date-like columns by name or sample values
        if (isDateLike(header, samples)) {
            String dateFormat = detectDateFormat(samples);
            description = header + "：格式为'" + dateFormat + "'";
        }

        // Append sample values to description
        if (!samples.isEmpty()) {
            List<String> top3 = samples.subList(0, Math.min(SAMPLE_VALUES_FOR_AI, samples.size()));
            description += "，包含" + String.join(",", top3) + "等";
        }

        return new ColumnInfo(english, description);
    }

    private boolean isDateLike(String header, List<String> samples) {
        String h = header.toLowerCase();
        if (h.contains("日期") || h.contains("date") || h.contains("时间") || h.contains("time")) {
            return true;
        }
        // Check sample values
        for (String s : samples) {
            if (s != null && s.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")) {
                return true;
            }
        }
        return false;
    }

    private String detectDateFormat(List<String> samples) {
        for (String s : samples) {
            if (s == null) continue;
            if (s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*")) {
                return "yyyy-MM-dd HH:mm:ss";
            }
            if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                return "yyyy-MM-dd";
            }
            if (s.matches("\\d{4}/\\d{2}/\\d{2}.*")) {
                return "yyyy/MM/dd";
            }
            if (s.matches("\\d{8}")) {
                return "yyyyMMdd";
            }
        }
        return "yyyy-MM-dd";
    }

    // -----------------------------------------------------------------
    //  Result types
    // -----------------------------------------------------------------

    /** AI-generated (or fallback) info for a single column. */
    public record ColumnInfo(String englishName, String description) {}

    /** Result of schema generation: per-column info + table description. */
    public record SchemaResult(List<ColumnInfo> columns, String tableDescription) {}
}
