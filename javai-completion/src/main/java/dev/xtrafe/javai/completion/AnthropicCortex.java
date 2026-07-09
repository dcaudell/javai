package dev.xtrafe.javai.completion;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.time.Instant;
import java.util.concurrent.Flow;

/**
 * Connector to Anthropic's own Messages API. Wraps Spring AI's {@code AnthropicChatModel} directly.
 *
 * <p>{@code providerOptions} key {@code "thinking_budget_tokens"} (an {@code Integer}) is a real,
 * on-topic example of the "proprietary tuning parameter" requirement: setting it enables Anthropic's
 * extended-thinking mode with that token budget, via {@code AnthropicChatOptions.thinking(ENABLED, budget)}
 * -- a capability with no equivalent on any other provider this module wraps.
 *
 * <p><b>Not yet verified against a live endpoint</b> -- no API key was available at implementation time.
 * Covered by hermetic tests (request/option-mapping against a fake HTTP server) only; see this module's
 * README.
 */
public final class AnthropicCortex implements Cortex {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private final String model;
    private final AnthropicChatModel chatModel;

    private AnthropicCortex(String baseUrl, String apiKey, String model) {
        this.model = model;
        AnthropicApi api = AnthropicApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey == null ? "" : apiKey)
                .build();
        this.chatModel = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(model)
                        .maxTokens(AnthropicChatModel.DEFAULT_MAX_TOKENS)
                        .build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletionResult complete(CompletionRequest request) {
        ChatResponse response = chatModel.call(new Prompt(request.render(), optionsFor(request)));
        return new CompletionResult(response.getResult().getOutput().getText(), "anthropic", model, Instant.now());
    }

    @Override
    public void completeStreaming(CompletionRequest request, Flow.Subscriber<String> subscriber) {
        CortexStreaming.bridge(
                chatModel.stream(new Prompt(request.render(), optionsFor(request))),
                subscriber,
                chatResponse -> chatResponse.getResult() == null ? null : chatResponse.getResult().getOutput().getText());
    }

    @Override
    public String providerId() {
        return "anthropic";
    }

    @Override
    public String modelId() {
        return model;
    }

    private AnthropicChatOptions optionsFor(CompletionRequest request) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(request.maxTokens() != null ? request.maxTokens() : AnthropicChatModel.DEFAULT_MAX_TOKENS);
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.providerOptions().get("thinking_budget_tokens") instanceof Integer budget) {
            builder.thinking(AnthropicApi.ThinkingType.ENABLED, budget);
        }
        return builder.build();
    }

    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey;
        private String model;

        private Builder() {
        }

        /** Override only for testing against a fake server -- real usage never needs this. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public AnthropicCortex build() {
            if (model == null) {
                throw new IllegalStateException("AnthropicCortex requires a model -- e.g. \"claude-sonnet-5\"");
            }
            return new AnthropicCortex(baseUrl, apiKey, model);
        }
    }
}
