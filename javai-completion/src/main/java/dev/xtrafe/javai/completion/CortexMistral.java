package dev.xtrafe.javai.completion;

import java.util.concurrent.Flow;

/**
 * Connector to Mistral's hosted chat-completions API -- OpenAI-wire-compatible (same request and response
 * shape at Mistral's own {@code base-url}), so this reuses {@link CortexOpenAiCompatibleSupport} rather than
 * a separate client, exactly like {@link CortexGroq}.
 *
 * <p>Verified against the live endpoint by {@code CortexMistralLiveTest} (tagged {@code requires-model}, so
 * excluded from CI) as well as hermetically by {@code CortexMistralTest}.
 */
public final class CortexMistral implements Cortex {

    private static final String DEFAULT_BASE_URL = "https://api.mistral.ai/v1";

    private final Cortex delegate;

    private CortexMistral(String baseUrl, String apiKey, String model, Integer contextWindowTokens) {
        this.delegate = new CortexOpenAiCompatibleSupport("mistral", baseUrl, apiKey, model, contextWindowTokens);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletionResult complete(CompletionRequest request) {
        return delegate.complete(request);
    }

    @Override
    public void completeStreaming(CompletionRequest request, Flow.Subscriber<String> subscriber) {
        delegate.completeStreaming(request, subscriber);
    }

    @Override
    public String providerId() {
        return delegate.providerId();
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public int contextWindowTokens() {
        return delegate.contextWindowTokens();
    }

    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey;
        private String model;
        private Integer contextWindowTokens;

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

        /** Overrides {@link ContextWindows}'s best-effort lookup for this model. */
        public Builder contextWindowTokens(int contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
            return this;
        }

        public CortexMistral build() {
            if (model == null) {
                throw new IllegalStateException("CortexMistral requires a model -- e.g. \"mistral-small-latest\"");
            }
            return new CortexMistral(baseUrl, apiKey, model, contextWindowTokens);
        }
    }
}
