package dev.xtrafe.javai.completion;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.time.Instant;
import java.util.concurrent.Flow;

/**
 * Shared implementation behind {@link OpenAICortex}, {@link GroqCortex}, and {@link VLlmCortex} -- all
 * three speak the same OpenAI-compatible chat-completions wire format (native for OpenAI itself, and by
 * explicit design for Groq/vLLM, both of which expose an OpenAI-compatible endpoint specifically so
 * existing OpenAI clients work against them unmodified with just a different {@code base-url}). One real
 * implementation, three distinct public classes -- the user asked for three separately-named connector
 * types, and that vocabulary should show up in code, even though underneath all three just configure a
 * repointed {@code OpenAiChatModel}.
 */
final class OpenAiCompatibleCortexSupport implements Cortex {

    private final String providerId;
    private final String model;
    private final OpenAiChatModel chatModel;

    OpenAiCompatibleCortexSupport(String providerId, String baseUrl, String apiKey, String model) {
        this.providerId = providerId;
        this.model = model;
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey == null ? "" : apiKey)
                .build();
        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();
    }

    @Override
    public CompletionResult complete(CompletionRequest request) {
        ChatResponse response = chatModel.call(new Prompt(request.render(), optionsFor(request)));
        return new CompletionResult(response.getResult().getOutput().getText(), providerId, model, Instant.now());
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
        return providerId;
    }

    @Override
    public String modelId() {
        return model;
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
