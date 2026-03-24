/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.dataanalysis.client;

import io.agentscope.examples.dataanalysis.dto.DatasetInfo;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Client for calling the external data API.
 *
 * <p>Two APIs are wrapped:
 *
 * <ul>
 *   <li>{@code GET /datasets} - returns the list of available datasets (id + description)
 *   <li>{@code GET /query?datasetId=xxx&question=yyy} - queries a specific dataset
 * </ul>
 *
 * <p>The base URL is configured via {@code data.api.base-url} in application.yml. Set {@code
 * data.api.mock=true} to use built-in mock responses (useful during development).
 */
@Component
public class DataApiClient {

    private static final Logger log = LoggerFactory.getLogger(DataApiClient.class);

    private final WebClient webClient;
    private final boolean mockEnabled;

    public DataApiClient(
            @Value("${data.api.base-url:http://localhost:9090}") String baseUrl,
            @Value("${data.api.mock:true}") boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .codecs(
                                configurer ->
                                        configurer
                                                .defaultCodecs()
                                                .maxInMemorySize(10 * 1024 * 1024))
                        .build();
        log.info("DataApiClient initialized, baseUrl={}, mock={}", baseUrl, mockEnabled);
    }

    /**
     * Retrieve the list of available datasets.
     *
     * @return Mono wrapping the list of DatasetInfo
     */
    public Mono<List<DatasetInfo>> listDatasets() {
        if (mockEnabled) {
            return mockListDatasets();
        }
        return webClient
                .get()
                .uri("/datasets")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<DatasetInfo>>() {})
                .timeout(Duration.ofSeconds(30))
                .doOnError(e -> log.error("Failed to list datasets", e))
                .onErrorResume(
                        e ->
                                Mono.error(
                                        new RuntimeException(
                                                "Failed to list datasets: " + e.getMessage(), e)));
    }

    /**
     * Query a specific dataset.
     *
     * @param datasetId the ID of the dataset to query
     * @param question the question or query string
     * @return Mono wrapping the query result as a String
     */
    public Mono<String> queryDataset(String datasetId, String question) {
        if (mockEnabled) {
            return mockQueryDataset(datasetId, question);
        }
        return webClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/query")
                                        .queryParam("datasetId", datasetId)
                                        .queryParam("question", question)
                                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .doOnError(
                        e ->
                                log.error(
                                        "Failed to query dataset={}, question={}",
                                        datasetId,
                                        question,
                                        e))
                .onErrorResume(e -> Mono.just("Query failed: " + e.getMessage()));
    }

    // ==================== Mock Implementations ====================

    private Mono<List<DatasetInfo>> mockListDatasets() {
        log.debug("Using mock dataset list");
        return Mono.just(
                List.of(
                        new DatasetInfo(
                                "ds_dau_index",
                                "咪咕视频APP日指数，基于高质量日活跃样本用户计算的活跃指数，用于反映核心活跃用户的变化趋势。"
                                        + "包含每日活跃指数值、环比变化率、同比变化率。"),
                        new DatasetInfo(
                                "ds_community",
                                "咪咕视频APP社区化相关指标，包含："
                                        + "社区场景月累计活跃用户规模、"
                                        + "社区化日活跃用户规模、"
                                        + "社区内容消费用户次日留存率、"
                                        + "全量创作者规模、"
                                        + "B级及以上创作者规模、"
                                        + "B级及以上创作者日人均涨粉数、"
                                        + "社区内容日总赞数量、"
                                        + "B级及以上创作者TOP1视频点赞量。"),
                        new DatasetInfo(
                                "ds_kpi",
                                "##咪咕各产品的考核指标情况"
                                        + "### 元数据（字段顺序）：产品名称, 指标名称, 日期, 指标值, 目标值, 完成进度, 上月同期值, 环比上月变化情况\n"
                                        + "### 示例数据：\n"
                                        + "#### 全量, RFE高价值活跃规模, 20260318, 45911864, 3.808E+7, 20.57, 45377751, 1.18\n"
                                        + "#### 元宇宙, AI数智人使用用户, 20260318, 1963914, 5000000, 39.28, 1353245, 45.13\n"
                                        + "#### 咪咕视频, AI+咪咕视频自研产品使用用户数, 20260318, 3721628, 6000000, 62.03, 2792373, 33.28\n"),
                        new DatasetInfo(
                                "ds_content_play",
                                "内容播放情况，包含咪咕视频各类型的内容播放数据，"
                                        + "分类：动漫、少儿、电视剧、电影、纪实、体育、综艺、总榜。"
                                        + "包含热门内容名称、热门内容热度指数、热度排名。")));
    }

    private Mono<String> mockQueryDataset(String datasetId, String question) {
        log.debug("Using mock query, datasetId={}, question={}", datasetId, question);
        String lowerQuestion = question.toLowerCase();

        return switch (datasetId) {
            case "ds_dau_index" -> Mono.just(buildDauIndexMockResponse(lowerQuestion));
            case "ds_community" -> Mono.just(buildCommunityMockResponse(lowerQuestion));
            case "ds_kpi" -> Mono.just(buildKpiMockResponse(lowerQuestion));
            case "ds_content_play" -> Mono.just(buildContentPlayMockResponse(lowerQuestion));
            default ->
                    Mono.just(
                            "Dataset '"
                                    + datasetId
                                    + "' not found. Available datasets: ds_dau_index, ds_community, ds_kpi, ds_content_play");
        };
    }

    private String buildDauIndexMockResponse(String question) {
        if (question.contains("趋势") || question.contains("月") || question.contains("变化")) {
            return """
                    {
                      "dataset": "ds_dau_index",
                      "query": "近30日DAU活跃指数趋势",
                      "result": [
                        {"date": "2025-02-16", "dau_index": 82.3, "wow_change": "+1.2%"},
                        {"date": "2025-02-23", "dau_index": 84.7, "wow_change": "+2.9%"},
                        {"date": "2025-03-02", "dau_index": 83.1, "wow_change": "-1.9%"},
                        {"date": "2025-03-09", "dau_index": 86.5, "wow_change": "+4.1%"},
                        {"date": "2025-03-16", "dau_index": 88.2, "wow_change": "+2.0%"}
                      ]
                    }""";
        }
        if (question.contains("同比") || question.contains("yoy")) {
            return """
                    {
                      "dataset": "ds_dau_index",
                      "query": "DAU指数同比情况",
                      "result": {
                        "current_month_avg_index": 87.4,
                        "yoy_change": "+12.6%",
                        "same_period_last_year_avg_index": 77.6,
                        "peak_day_index": 96.3,
                        "peak_day": "2025-03-14"
                      }
                    }""";
        }
        return """
                {
                  "dataset": "ds_dau_index",
                  "query": "最新DAU活跃指数概况",
                  "result": {
                    "latest_date": "2025-03-17",
                    "latest_dau_index": 88.2,
                    "wow_change": "+2.0%",
                    "yoy_change": "+12.6%",
                    "month_avg_index": 85.1,
                    "peak_day_index": 96.3
                  }
                }""";
    }

    private String buildCommunityMockResponse(String question) {
        if (question.contains("创作者") || question.contains("creator")) {
            return """
                    {
                      "dataset": "ds_community",
                      "query": "创作者相关指标",
                      "result": {
                        "total_creators": 1250000,
                        "b_level_and_above_creators": 38600,
                        "b_level_daily_avg_new_fans": 124,
                        "b_level_top1_video_likes": 892400,
                        "mom_change_total_creators": "+5.3%",
                        "mom_change_b_level_creators": "+8.1%"
                      }
                    }""";
        }
        if (question.contains("留存") || question.contains("retention")) {
            return """
                    {
                      "dataset": "ds_community",
                      "query": "社区内容消费用户次日留存率",
                      "result": {
                        "next_day_retention_rate": "43.7%",
                        "wow_change": "+0.8pp",
                        "mom_change": "+2.1pp",
                        "benchmark_industry_avg": "38.5%"
                      }
                    }""";
        }
        if (question.contains("点赞") || question.contains("赞")) {
            return """
                    {
                      "dataset": "ds_community",
                      "query": "社区内容点赞数据",
                      "result": {
                        "daily_total_likes": 6830000,
                        "wow_change": "+3.5%",
                        "b_level_top1_video_likes": 892400,
                        "top1_video_title": "2025赛季CBA全明星精彩集锦",
                        "top1_video_creator": "体育精选号"
                      }
                    }""";
        }
        return """
                {
                  "dataset": "ds_community",
                  "query": "社区化核心指标概览",
                  "result": {
                    "monthly_active_community_users": 42800000,
                    "daily_active_community_users": 8760000,
                    "next_day_retention_rate": "43.7%",
                    "total_creators": 1250000,
                    "b_level_and_above_creators": 38600,
                    "b_level_daily_avg_new_fans": 124,
                    "daily_total_likes": 6830000,
                    "b_level_top1_video_likes": 892400
                  }
                }""";
    }

    private String buildKpiMockResponse(String question) {
        // 咪咕视频相关指标
        if (question.contains("视频")
                || question.contains("video")
                || question.contains("ai+")
                || question.contains("自研")) {
            return """
                    {
                      "dataset": "ds_kpi",
                      "query": "咪咕视频考核指标完成情况",
                      "fields": ["product_type","index_name","period_id","index_value","target_value","progress_value","lastmonth_value","lastmonth_rate"],
                      "result": [
                        {"product_type":"咪咕视频","index_name":"AI+咪咕视频自研产品使用用户数",  "period_id":"20260318","index_value":3721628, "target_value":6000000,"progress_value":62.03,"lastmonth_value":2792373,"lastmonth_rate":33.28},
                        {"product_type":"咪咕视频","index_name":"视频月活跃用户规模(MAU)",         "period_id":"20260318","index_value":91200000,"target_value":85000000,"progress_value":107.29,"lastmonth_value":87600000,"lastmonth_rate":4.11},
                        {"product_type":"咪咕视频","index_name":"视频日活跃用户规模(DAU)",         "period_id":"20260318","index_value":17650000,"target_value":18000000,"progress_value":98.06,"lastmonth_value":16900000,"lastmonth_rate":4.44},
                        {"product_type":"咪咕视频","index_name":"付费会员数",                       "period_id":"20260318","index_value":11340000,"target_value":12000000,"progress_value":94.50,"lastmonth_value":11100000,"lastmonth_rate":2.16},
                        {"product_type":"咪咕视频","index_name":"人均日使用时长(分钟)",             "period_id":"20260318","index_value":46.8,    "target_value":42,      "progress_value":111.43,"lastmonth_value":44.2,    "lastmonth_rate":5.88}
                      ]
                    }""";
        }
        // 咪咕音乐相关指标
        if (question.contains("音乐") || question.contains("music")) {
            return """
                    {
                      "dataset": "ds_kpi",
                      "query": "咪咕音乐考核指标完成情况",
                      "fields": ["product_type","index_name","period_id","index_value","target_value","progress_value","lastmonth_value","lastmonth_rate"],
                      "result": [
                        {"product_type":"咪咕音乐","index_name":"音乐月活跃用户规模(MAU)",  "period_id":"20260318","index_value":63800000,"target_value":60000000,"progress_value":106.33,"lastmonth_value":61200000,"lastmonth_rate":4.25},
                        {"product_type":"咪咕音乐","index_name":"付费会员数",              "period_id":"20260318","index_value":7920000, "target_value":8000000, "progress_value":99.00, "lastmonth_value":7750000, "lastmonth_rate":2.19},
                        {"product_type":"咪咕音乐","index_name":"人均日播放曲目数",        "period_id":"20260318","index_value":13.5,    "target_value":12,      "progress_value":112.50,"lastmonth_value":12.8,    "lastmonth_rate":5.47},
                        {"product_type":"咪咕音乐","index_name":"AI音乐创作工具使用用户数","period_id":"20260318","index_value":2180000, "target_value":3000000, "progress_value":72.67, "lastmonth_value":1650000, "lastmonth_rate":32.12}
                      ]
                    }""";
        }
        // 元宇宙 / AI数智人相关指标
        if (question.contains("元宇宙") || question.contains("数智人") || question.contains("ai")) {
            return """
                    {
                      "dataset": "ds_kpi",
                      "query": "元宇宙/AI考核指标完成情况",
                      "fields": ["product_type","index_name","period_id","index_value","target_value","progress_value","lastmonth_value","lastmonth_rate"],
                      "result": [
                        {"product_type":"元宇宙","index_name":"AI数智人使用用户",        "period_id":"20260318","index_value":1963914,"target_value":5000000,"progress_value":39.28,"lastmonth_value":1353245,"lastmonth_rate":45.13},
                        {"product_type":"元宇宙","index_name":"元宇宙月活跃用户规模",    "period_id":"20260318","index_value":820000, "target_value":2000000,"progress_value":41.00,"lastmonth_value":610000, "lastmonth_rate":34.43},
                        {"product_type":"元宇宙","index_name":"虚拟形象创建数",          "period_id":"20260318","index_value":345000, "target_value":600000, "progress_value":57.50,"lastmonth_value":268000, "lastmonth_rate":28.73},
                        {"product_type":"元宇宙","index_name":"元宇宙内容消费用户规模",  "period_id":"20260318","index_value":1240000,"target_value":2500000,"progress_value":49.60,"lastmonth_value":980000, "lastmonth_rate":26.53}
                      ]
                    }""";
        }
        // 全量/集团考核汇总（默认兜底）
        return """
                {
                  "dataset": "ds_kpi",
                  "query": "咪咕旗下各产品考核指标全量汇总",
                  "fields": ["product_type","index_name","period_id","index_value","target_value","progress_value","lastmonth_value","lastmonth_rate"],
                  "result": [
                    {"product_type":"全量",    "index_name":"RFE高价值活跃规模",            "period_id":"20260318","index_value":45911864,"target_value":38080000,"progress_value":120.57,"lastmonth_value":45377751,"lastmonth_rate":1.18},
                    {"product_type":"全量",    "index_name":"集团考核月活跃用户(MAU)",       "period_id":"20260318","index_value":198500000,"target_value":195000000,"progress_value":101.79,"lastmonth_value":192300000,"lastmonth_rate":3.22},
                    {"product_type":"咪咕视频","index_name":"AI+咪咕视频自研产品使用用户数","period_id":"20260318","index_value":3721628, "target_value":6000000, "progress_value":62.03, "lastmonth_value":2792373, "lastmonth_rate":33.28},
                    {"product_type":"咪咕视频","index_name":"视频月活跃用户规模(MAU)",       "period_id":"20260318","index_value":91200000,"target_value":85000000,"progress_value":107.29,"lastmonth_value":87600000,"lastmonth_rate":4.11},
                    {"product_type":"咪咕视频","index_name":"付费会员数",                   "period_id":"20260318","index_value":11340000,"target_value":12000000,"progress_value":94.50, "lastmonth_value":11100000,"lastmonth_rate":2.16},
                    {"product_type":"咪咕音乐","index_name":"音乐月活跃用户规模(MAU)",       "period_id":"20260318","index_value":63800000,"target_value":60000000,"progress_value":106.33,"lastmonth_value":61200000,"lastmonth_rate":4.25},
                    {"product_type":"咪咕音乐","index_name":"付费会员数",                   "period_id":"20260318","index_value":7920000, "target_value":8000000, "progress_value":99.00, "lastmonth_value":7750000, "lastmonth_rate":2.19},
                    {"product_type":"咪咕音乐","index_name":"AI音乐创作工具使用用户数",     "period_id":"20260318","index_value":2180000, "target_value":3000000, "progress_value":72.67, "lastmonth_value":1650000, "lastmonth_rate":32.12},
                    {"product_type":"咪咕阅读","index_name":"阅读月活跃用户规模(MAU)",       "period_id":"20260318","index_value":28400000,"target_value":30000000,"progress_value":94.67, "lastmonth_value":27100000,"lastmonth_rate":4.80},
                    {"product_type":"咪咕阅读","index_name":"付费会员数",                   "period_id":"20260318","index_value":3860000, "target_value":4200000, "progress_value":91.90, "lastmonth_value":3720000, "lastmonth_rate":3.76},
                    {"product_type":"咪咕阅读","index_name":"日均阅读时长(分钟)",           "period_id":"20260318","index_value":38.2,    "target_value":35,      "progress_value":109.14,"lastmonth_value":36.5,    "lastmonth_rate":4.66},
                    {"product_type":"咪咕游戏","index_name":"游戏月活跃用户规模(MAU)",       "period_id":"20260318","index_value":14100000,"target_value":18000000,"progress_value":78.33, "lastmonth_value":13800000,"lastmonth_rate":2.17},
                    {"product_type":"咪咕游戏","index_name":"游戏付费用户数",               "period_id":"20260318","index_value":1820000, "target_value":2500000, "progress_value":72.80, "lastmonth_value":1710000, "lastmonth_rate":6.43},
                    {"product_type":"元宇宙",  "index_name":"AI数智人使用用户",             "period_id":"20260318","index_value":1963914, "target_value":5000000, "progress_value":39.28, "lastmonth_value":1353245, "lastmonth_rate":45.13},
                    {"product_type":"元宇宙",  "index_name":"元宇宙月活跃用户规模",         "period_id":"20260318","index_value":820000,  "target_value":2000000, "progress_value":41.00, "lastmonth_value":610000,  "lastmonth_rate":34.43}
                  ]
                }""";
    }

    private String buildContentPlayMockResponse(String question) {
        if (question.contains("体育") || question.contains("sport")) {
            return """
                    {
                      "dataset": "ds_content_play",
                      "query": "体育内容播放数据",
                      "result": {
                        "category": "体育",
                        "daily_play_count": 38200000,
                        "wow_change": "+18.6%",
                        "share_of_total": "21.3%",
                        "hot_content": [
                          {"title": "2025赛季CBA总决赛第三场", "heat_index": 98.7, "rank": 1},
                          {"title": "中超联赛第5轮精彩集锦", "heat_index": 91.2, "rank": 2},
                          {"title": "WWE RAW 2025最新一期", "heat_index": 83.5, "rank": 3}
                        ]
                      }
                    }""";
        }
        if (question.contains("电视剧") || question.contains("电影") || question.contains("剧集")) {
            return """
                    {
                      "dataset": "ds_content_play",
                      "query": "影视内容播放数据",
                      "result": [
                        {
                          "category": "电视剧",
                          "daily_play_count": 52600000,
                          "wow_change": "+3.1%",
                          "share_of_total": "29.4%",
                          "top1": {"title": "繁花续集", "heat_index": 97.3, "rank": 1}
                        },
                        {
                          "category": "电影",
                          "daily_play_count": 21300000,
                          "wow_change": "-2.4%",
                          "share_of_total": "11.9%",
                          "top1": {"title": "流浪地球3预告独家", "heat_index": 89.6, "rank": 1}
                        }
                      ]
                    }""";
        }
        if (question.contains("排行")
                || question.contains("热门")
                || question.contains("top")
                || question.contains("榜")) {
            return """
                    {
                      "dataset": "ds_content_play",
                      "query": "内容热度总榜Top10",
                      "result": [
                        {"rank": 1,  "title": "2025赛季CBA总决赛第三场",  "category": "体育",  "heat_index": 98.7},
                        {"rank": 2,  "title": "繁花续集",               "category": "电视剧", "heat_index": 97.3},
                        {"rank": 3,  "title": "名侦探柯南剧场版2025",   "category": "动漫",  "heat_index": 94.8},
                        {"rank": 4,  "title": "中超联赛第5轮精彩集锦",  "category": "体育",  "heat_index": 91.2},
                        {"rank": 5,  "title": "快乐大本营2025春季特辑",  "category": "综艺",  "heat_index": 90.5},
                        {"rank": 6,  "title": "航拍中国第五季",         "category": "纪实",  "heat_index": 88.1},
                        {"rank": 7,  "title": "海底小纵队新番",         "category": "少儿",  "heat_index": 86.4},
                        {"rank": 8,  "title": "流浪地球3预告独家",      "category": "电影",  "heat_index": 89.6},
                        {"rank": 9,  "title": "WWE RAW 2025最新一期",  "category": "体育",  "heat_index": 83.5},
                        {"rank": 10, "title": "明星大侦探第九季",       "category": "综艺",  "heat_index": 82.9}
                      ]
                    }""";
        }
        return """
                {
                  "dataset": "ds_content_play",
                  "query": "各类型内容播放量汇总",
                  "result": [
                    {"category": "电视剧", "daily_play_count": 52600000, "share": "29.4%", "wow_change": "+3.1%"},
                    {"category": "体育",   "daily_play_count": 38200000, "share": "21.3%", "wow_change": "+18.6%"},
                    {"category": "综艺",   "daily_play_count": 28700000, "share": "16.0%", "wow_change": "+1.8%"},
                    {"category": "动漫",   "daily_play_count": 22400000, "share": "12.5%", "wow_change": "+6.2%"},
                    {"category": "电影",   "daily_play_count": 21300000, "share": "11.9%", "wow_change": "-2.4%"},
                    {"category": "纪实",   "daily_play_count": 9800000,  "share": "5.5%",  "wow_change": "+4.7%"},
                    {"category": "少儿",   "daily_play_count": 5200000,  "share": "2.9%",  "wow_change": "+0.5%"},
                    {"category": "其他",   "daily_play_count": 1500000,  "share": "0.5%",  "wow_change": "-1.1%"}
                  ]
                }""";
    }
}
