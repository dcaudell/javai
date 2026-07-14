package dev.xtrafe.javai.completion;

import java.util.concurrent.Flow;

/**
 * Connector to OpenAI's own chat-completions API. Wraps Spring AI's {@code OpenAiChatModel} directly
 * (native wire format, no repointing needed) via {@link CortexOpenAiCompatibleSupport}.
 *
 * <p><b>Not yet verified against a live endpoint</b> -- no API key was available at implementation time.
 * Covered by hermetic tests (request/option-mapping against a fake HTTP server) only; see this module's
 * README.
 */
public final class CortexOpenAI implements Cortex {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final Cortex delegate;

    private CortexOpenAI(String baseUrl, String apiKey, String model, Integer contextWindowTokens) {
        this.delegate = new CortexOpenAiCompatibleSupport("openai", baseUrl, apiKey, model, contextWindowTokens);
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

        /** Overrides {@link ContextWindows}'s best-effort lookup for this model -- prefer this whenever
         *  correctness matters more than the table's convenience. */
        public Builder contextWindowTokens(int contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
            return this;
        }

        public CortexOpenAI build() {
            if (model == null) {
                throw new IllegalStateException("CortexOpenAI requires a model -- e.g. \"gpt-4.1\"");
            }
            return new CortexOpenAI(baseUrl, apiKey, model, contextWindowTokens);
        }
    }
}
