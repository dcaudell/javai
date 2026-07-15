package dev.xtrafe.javai.completion;

import com.openai.errors.RateLimitException;
import dev.xtrafe.javai.vector.RetryAfterParser;
import dev.xtrafe.javai.vector.RetrySupport;
import dev.xtrafe.javai.vector.TooManyRequestsException;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Flow;

/**
 * Shared implementation behind {@link CortexOpenAI}, {@link CortexGroq}, and {@link CortexVLlm} -- all
 * three speak the same OpenAI-compatible chat-completions wire format (native for OpenAI itself, and by
 * explicit design for Groq/vLLM, both of which expose an OpenAI-compatible endpoint specifically so
 * existing OpenAI clients work against them unmodified with just a different {@code base-url}). One real
 * implementation, three distinct public classes -- the user asked for three separately-named connector
 * types, and that vocabulary should show up in code, even though underneath all three just configure a
 * repointed {@code OpenAiChatModel}.
 *
 * <p><b>429 detection, since Spring AI 2.0</b>: {@code OpenAiChatModel} now wraps OpenAI's own official
 * Java SDK (a {@code com.openai.client.OpenAIClient}, constructed internally from {@code apiKey}/
 * {@code baseUrl} set directly on {@link OpenAiChatOptions} -- there's no more {@code OpenAiApi}/
 * {@code WebClient} layer of our own to hang a {@code ResponseErrorHandler} off of). The SDK throws its own
 * typed {@link RateLimitException} on HTTP 429, caught here and converted to {@link
 * TooManyRequestsException} -- the same shape {@link RetrySupport}/{@code CortexStreamingRetry} already
 * coordinate through for every other provider. {@code maxRetries(0)} below turns off the SDK's own internal
 * retry-on-transient-error behavior, so {@code RetrySupport}/{@link dev.xtrafe.javai.vector.EndpointRateLimiter}
 * stay the single source of truth for retry/backoff timing, unchanged from before this migration.
 */
final class CortexOpenAiCompatibleSupport implements Cortex {

    private final String providerId;
    private final String model;
    private final String baseUrl;
    private final Integer contextWindowTokensOverride;
    private final OpenAiChatModel chatModel;

    CortexOpenAiCompatibleSupport(String providerId, String baseUrl, String apiKey, String model,
            Integer contextWindowTokensOverride) {
        this.providerId = providerId;
        this.model = model;
        this.baseUrl = baseUrl;
        this.contextWindowTokensOverride = contextWindowTokensOverride;
        this.chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .apiKey(apiKey == null ? "" : apiKey)
                        .baseUrl(baseUrl)
                        .maxRetries(0)
                        .build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();
    }

    @Override
    public CompletionResult complete(CompletionRequest request) {
        Prompt prompt = new Prompt(request.render(contextWindowTokens()), optionsFor(request));
        ChatResponse response;
        try {
            response = RetrySupport.withRetry(baseUrl, () -> callChecked(prompt));
        } catch (TooManyRequestsException e) {
            throw new CompletionException(providerId + " rate-limited too many times", e);
        }
        return new CompletionResult(response.getResult().getOutput().getText(), providerId, model, Instant.now());
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
                        chatModel.stream(prompt).onErrorMap(RateLimitException.class,
                                CortexOpenAiCompatibleSupport::toTooManyRequests)),
                subscriber,
                chatResponse -> chatResponse.getResult() == null ? null : chatResponse.getResult().getOutput().getText());
    }

    private static TooManyRequestsException toTooManyRequests(RateLimitException e) {
        Duration retryAfter = RetryAfterParser.parse(
                e.headers().values("retry-after").stream().findFirst().orElse(null));
        return new TooManyRequestsException("Rate limited: HTTP 429", retryAfter);
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public int contextWindowTokens() {
        return contextWindowTokensOverride != null ? contextWindowTokensOverride : ContextWindows.lookup(model);
    }

    /** {@code reasoning_effort} (a real OpenAI-family tuning parameter -- "low"/"medium"/"high") is the one
     *  {@code providerOptions} key read here; everything else maps onto {@link CompletionRequest}'s own
     *  typed fields (max tokens, temperature) that {@code OpenAiChatOptions} already exposes directly. */
    private OpenAiChatOptions optionsFor(CompletionRequest request) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model);
        if (request.maxTokens() != null) {
            builder.maxTokens(request.maxTokens());
        }
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.providerOptions().get("reasoning_effort") instanceof String effort) {
            builder.reasoningEffort(effort);
        }
        return builder.build();
    }
}
