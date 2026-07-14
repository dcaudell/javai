package dev.xtrafe.javai.completion;

import java.util.concurrent.Flow;

/**
 * Connector to a self-hosted vLLM server -- vLLM implements an OpenAI-compatible {@code /v1/chat/completions}
 * endpoint specifically to be a drop-in replacement for OpenAI clients, so this reuses
 * {@link CortexOpenAiCompatibleSupport} rather than a separate client, exactly like {@link CortexGroq}.
 * Unlike OpenAI/Groq, there's no fixed default {@code base-url} -- a vLLM server is always self-hosted, so
 * {@link Builder#baseUrl} is required, not optional.
 *
 * <p><b>Not yet verified against a real running vLLM instance</b> -- vLLM's own Docker images are
 * CUDA-first and don't run under Docker Desktop on Apple Silicon, so there's no way to stand one up for
 * this pass's test environment. Covered by hermetic tests (request/option-mapping against a fake HTTP
 * server) only; see this module's README.
 */
public final class CortexVLlm implements Cortex {

    private final Cortex delegate;

    private CortexVLlm(String baseUrl, String apiKey, String model, Integer contextWindowTokens) {
        this.delegate = new CortexOpenAiCompatibleSupport("vllm", baseUrl, apiKey, model, contextWindowTokens);
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
        private String baseUrl;
        private String apiKey = "";
        private String model;
        private Integer contextWindowTokens;

        private Builder() {
        }

        /** Required -- e.g. {@code "http://localhost:8000/v1"}, wherever {@code vllm serve} is listening. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Most self-hosted vLLM deployments don't require one; only set this if yours does. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Strongly recommended for vLLM: {@link ContextWindows}'s table almost certainly doesn't know a
         *  self-hosted, arbitrary model, so it'll otherwise fall back to a conservative default. */
        public Builder contextWindowTokens(int contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
            return this;
        }

        public CortexVLlm build() {
            if (baseUrl == null) {
                throw new IllegalStateException("CortexVLlm requires baseUrl -- vLLM is always self-hosted, "
                        + "e.g. \"http://localhost:8000/v1\"");
            }
            if (model == null) {
                throw new IllegalStateException("CortexVLlm requires a model -- whatever \"vllm serve\" was started with");
            }
            return new CortexVLlm(baseUrl, apiKey, model, contextWindowTokens);
        }
    }
}
