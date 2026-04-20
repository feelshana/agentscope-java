package io.agentscope.examples.chatbi.config;


import io.agentscope.core.rag.integration.dify.DifyKnowledge;
import io.agentscope.core.rag.integration.dify.DifyRAGConfig;
import io.agentscope.core.rag.integration.dify.RetrievalMode;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DifyCustomRAGConfig {

    @Value("${dify.rag.api-base-url:}")
    private String apiBaseUrl;

    @Value("${dify.rag.api-key:}")
    private String apiKey;

    @Value("${dify.rag.dataset-id:}")
    private String datasetId;

    @Value("${dify.rag.retrieval-mode:}")
    private String retrievalMode;

    @Value("${dify.rag.score-threshold:}")
    private double scoreThreshold;

    @Value("${dify.rag.top-k:}")
    private int topK;

    @Value("${dify.rag.weights:}")
    private double weights;

    @Bean
    public DifyKnowledge knowledge() {
        DifyRAGConfig config = DifyRAGConfig.builder()
                .apiBaseUrl(apiBaseUrl)
                .apiKey(apiKey)
                .datasetId(datasetId)
                .retrievalMode(RetrievalMode.fromValue(retrievalMode))
                .scoreThreshold(scoreThreshold)
                .topK(topK)
                .weights(weights)
                .build();
        return DifyKnowledge.builder()
                .config(config).build();
    }

    @Bean
    public RetrieveConfig retrieveConfig() {
        return RetrieveConfig.builder()
                .limit(topK)
                .scoreThreshold(scoreThreshold)
                .build();
    }
}
