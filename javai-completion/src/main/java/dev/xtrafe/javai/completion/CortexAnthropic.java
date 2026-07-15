package dev.xtrafe.javai.completion;

import com.anthropic.errors.RateLimitException;
import dev.xtrafe.javai.vector.RetryAfterParser;
import dev.xtrafe.javai.vector.RetrySupport;
import dev.xtrafe.javai.vector.TooManyRequestsException;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Flow;

/**
 * Connector to Anthropic's own Messages API. Wraps Spring AI's {@code AnthropicChatModel} directly.
 *
 * <p>{@code providerOptions} key {@code "thinking_budget_tokens"} (an {@code Integer}) is a real,
 * on-topic example of the "proprietary tuning parameter" requirement: setting it enables Anthropic's
 * extended-thinking mode with that token budget, via {@code AnthropicChatOptions.thinkingEnabled(budget)}
 * -- a capability with no equivalent on any other provider this module wraps.
 *
 * <p><b>429 detection, since Spring AI 2.0</b>: {@code AnthropicChatModel} now wraps Anthropic's own
 * official Java SDK (a {@code com.anthropic.client.AnthropicClient}, constructed internally from
 * {@code apiKey}/{@code baseUrl} set directly on {@link AnthropicChatOptions} -- there's no more
 * {@code AnthropicApi}/{@code WebClient} layer of our own to hang a {@code ResponseErrorHandler} off of).
 * The SDK throws its own typed {@link RateLimitException} on HTTP 429, caught here and converted to
 * {@link TooManyRequestsException} -- the same shape {@link RetrySupport}/{@code CortexStreamingRetry}
 * already coordinate through for every other provider. {@code maxRetries(0)} below turns off the SDK's
 * own internal retry-on-transient-error behavior, so {@code RetrySupport}/{@link
 * dev.xtrafe.javai.vector.EndpointRateLimiter} stay the single source of truth for retry/backoff timing,
 * unchanged from before this migration.
 *
 * <p><b>Not yet verified against a live endpoint</b> -- no API key was available at implementation time.
 * Covered by hermetic tests (request/option-mapping against a fake HTTP server) only; see this module's
 * README.
 */
public final class CortexAnthropic implements Cortex {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private final String model;
    private final String baseUrl;
    private final Integer contextWindowTokensOverride;
    private final AnthropicChatModel chatModel;

    private CortexAnthropic(String baseUrl, String apiKey, String model, Integer contextWindowTokensOverride) {
        this.model = model;
        this.baseUrl = baseUrl;
        this.contextWindowTokensOverride = contextWindowTokensOverride;
        this.chatModel = AnthropicChatModel.builder()
                .options(AnthropicChatOptions.builder()
                        .model(model)
                        .apiKey(apiKey == null ? "" : apiKey)
                        .baseUrl(baseUrl)
                        .maxRetries(0)
                        .maxTokens(AnthropicChatOptions.DEFAULT_MAX_TOKENS)
                        .build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletionResult complete(CompletionRequest request) {
        Prompt prompt = new Prompt(request.render(contextWindowTokens()), optionsFor(request));
        ChatResponse response;
        try {
            response = RetrySupport.withRetry(baseUrl, () -> callChecked(prompt));
        } catch (TooManyRequestsException e) {
            throw new CompletionException("anthropic rate-limited too many times", e);
        }
        return new CompletionResult(response.getResult().getOutput().getText(), "anthropic", model, Instant.now());
    }

    private ChatResponse callChecked(Prompt prompt) {
        try {
            return chatModel.call(prompt);
        } catch (RateLimitException e) {
            throw toTooManyRequests(e);
        }
    }

    @Override
    public void completeStreaming(CompletionRequest request, Flow.Subscriber<String> subscriber) {
        Prompt prompt = new Prompt(request.render(contextWindowTokens()), optionsFor(request));
        CortexStreaming.bridge(
                CortexStreamingRetry.withRetry(baseUrl,
                        chatModel.stream(prompt).onErrorMap(RateLimitException.class, CortexAnthropic::toTooManyRequests)),
                subscriber,
                chatResponse -> chatResponse.getResult() == null ? null : chatResponse.getResult().getOutput().getText());
    }

    private static TooManyRequestsException toTooManyRequests(RateLimitException e) {
        Duration retryAfter = RetryAfterParser.parse(
                e.headers().values("retry-after").stream().findFirst().orElse(null));
        return new TooManyRequestsException("Rate limited: HTTP 429 from Anthropic", retryAfter);
    }

    @Override
    public String providerId() {
        return "anthropic";
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public int contextWindowTokens() {
        return contextWindowTokensOverride != null ? contextWindowTokensOverride : ContextWindows.lookup(model);
    }

    private AnthropicChatOptions optionsFor(CompletionRequest request) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(request.maxTokens() != null ? request.maxTokens() : AnthropicChatOptions.DEFAULT_MAX_TOKENS);
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.providerOptions().get("thinking_budget_tokens") instanceof Integer budget) {
            builder.thinkingEnabled(budget);
        }
        return builder.build();
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

        public CortexAnthropic build() {
            if (model == null) {
                throw new IllegalStateException("CortexAnthropic requires a model -- e.g. \"claude-sonnet-5\"");
            }
            return new CortexAnthropic(baseUrl, apiKey, model, contextWindowTokens);
        }
    }
}
