package io.agentscope.dataagent.runtime.model;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpVersion;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Uses the native compatible client and provider-specific request defaults. */
@Component
public class ManagedModelFactory {
    public Model create(ModelServiceRegistry.Profile profile) {
        var timeout = Duration.ofSeconds(profile.timeoutSeconds());
        var transport =
                JdkHttpTransport.builder()
                        .config(
                                HttpTransportConfig.builder()
                                        .httpVersion(HttpVersion.HTTP_1_1)
                                        .connectTimeout(
                                                Duration.ofSeconds(
                                                        Math.min(15, profile.timeoutSeconds())))
                                        .responseTimeout(timeout)
                                        .readTimeout(timeout)
                                        .streamIdleTimeout(timeout)
                                        .build())
                        .build();
        return OpenAIChatModel.builder()
                .httpTransport(transport)
                .apiKey(profile.apiKey())
                .baseUrl(profile.baseUrl())
                .modelName(profile.modelName())
                .stream(profile.stream())
                .generateOptions(connectionOptions(profile))
                .build();
    }

    static GenerateOptions connectionOptions(ModelServiceRegistry.Profile profile) {
        var timeout = Duration.ofSeconds(profile.timeoutSeconds());
        var options =
                GenerateOptions.builder()
                        .apiKey(profile.apiKey())
                        .baseUrl(profile.baseUrl())
                        .modelName(profile.modelName())
                        .stream(profile.stream())
                        .temperature(profile.temperature())
                        .endpointPath(profile.endpointPath())
                        .executionConfig(
                                ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build());
        if (profile.disableThinking()) {
            if (profile.provider() == ModelServiceRegistry.Provider.QWEN) {
                options.additionalBodyParam("enable_thinking", false);
            } else if (profile.provider() == ModelServiceRegistry.Provider.DEEPSEEK) {
                options.additionalBodyParam("thinking", Map.of("type", "disabled"));
            }
        }
        return options.build();
    }
}
