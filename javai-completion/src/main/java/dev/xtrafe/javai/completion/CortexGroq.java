package dev.xtrafe.javai.completion;

import java.util.concurrent.Flow;

/**
 * Connector to Groq's hosted inference API -- OpenAI-wire-compatible by Groq's own design (a request
 * shaped exactly like an OpenAI chat-completions call, just against Groq's own {@code base-url} and
 * models), so this reuses {@link CortexOpenAiCompatibleSupport} rather than a separate client. Spring AI
 * itself documents this same "point the OpenAI client at Groq" integration path.
 *
 * <p><b>Not yet verified against a live endpoint</b> -- no API key was available at implementation time.
 * Covered by hermetic tests (request/option-mapping against a fake HTTP server) only; see this module's
 * README.
 */
public final class CortexGroq implements Cortex {

    private static final String DEFAULT_BASE_URL = "https://api.groq.com/openai/v1";

    private final Cortex delegate;

    private CortexGroq(String baseUrl, String apiKey, String model, Integer contextWindowTokens) {
        this.delegate = new CortexOpenAiCompatibleSupport("groq", baseUrl, apiKey, model, contextWindowTokens);
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

        public CortexGroq build() {
            if (model == null) {
                throw new IllegalStateException("CortexGroq requires a model -- e.g. \"llama-3.3-70b-versatile\"");
            }
            return new CortexGroq(baseUrl, apiKey, model, contextWindowTokens);
        }
    }
}
