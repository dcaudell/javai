package dev.xtrafe.javai.completion;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSyntaxException;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Connector to a local (or remote) Ollama instance. Built directly on Spring AI's low-level
 * {@code OllamaApi} rather than the higher-level {@code OllamaChatModel}/{@code OllamaOptions} pair --
 * deliberately, not an oversight: as of Spring AI 1.0.9, {@code OllamaOptions} doesn't yet expose Ollama's
 * {@code think} field (its real, wire-level "enable extended reasoning" toggle for models that support it,
 * e.g. the Qwen3 family), while {@code OllamaApi.ChatRequest.Builder} does. Using the lower-level API here
 * gives full access to that real capability -- still Spring AI's own official client code, just one layer
 * down, and only for this one provider where the higher layer doesn't yet cover what's needed.
 *
 * <p>{@code providerOptions} keys read here: {@code "enable_thinking"} (a {@code Boolean}, mapped to
 * {@code ChatRequest.think}) is the concrete, testable tuning-parameter example this module's real
 * (Testcontainers-backed) test proves actually changes observed behavior, not just gets silently accepted.
 * Any other entry is passed straight through as an Ollama request option (e.g. {@code "num_ctx"},
 * {@code "repeat_penalty"}, {@code "top_k"} -- Ollama's own option names, unmodified).
 *
 * <p><b>Streaming is hand-rolled, not {@code OllamaApi.streamingChat()}</b> -- the second deliberate
 * deviation from "wrap Spring AI, never write provider client code" (the first being {@link
 * ReplicateCortex}). Confirmed empirically against a real container, not assumed: {@code
 * OllamaApi.streamingChat()}'s reactive decode straight into the {@code ChatResponse} record hangs
 * indefinitely (no {@code onNext}, no {@code onError}, no {@code onComplete}) against this project's real
 * Ollama backend, while decoding the identical NDJSON response as plain text lines completes in under a
 * second -- a known class of async-parser limitation with custom deserializers (here, {@code
 * java.time.Instant}) that a fully-buffered synchronous parser doesn't hit. The fix sidesteps the
 * reactive decoder's async tokenizer entirely: read raw NDJSON lines via {@code
 * WebClient.bodyToFlux(String.class)} (proven fast and reliable), then parse each already-complete line
 * synchronously with a plain {@link Gson}, same as parsing any other single, self-contained JSON string.
 * GSON, not Jackson, matching this project's own {@code javai-vector}/{@code javai-model} (which takes no Jackson dependency
 * of its own -- see {@code PromptContext}'s javadoc); {@code ChatResponse}'s snake_case wire fields
 * ({@code done_reason}, {@code total_duration}, ...) need {@link FieldNamingPolicy#LOWER_CASE_WITH_UNDERSCORES},
 * and its {@code createdAt} field needs a custom {@code Instant} adapter, since GSON has no built-in
 * {@code java.time} support either.
 */
public final class OllamaCortex implements Cortex {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final String ENABLE_THINKING_KEY = "enable_thinking";

    private final String model;
    private final OllamaApi ollamaApi;
    private final WebClient webClient;
    private final Gson gson;

    private OllamaCortex(String baseUrl, String model) {
        this.model = model;
        this.ollamaApi = OllamaApi.builder().baseUrl(baseUrl).build();
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerTypeAdapter(Instant.class,
                        (JsonDeserializer<Instant>) (json, type, context) -> Instant.parse(json.getAsString()))
                .create();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletionResult complete(CompletionRequest request) {
        OllamaApi.ChatResponse response = ollamaApi.chat(chatRequest(request, false));
        String text = response.message() == null ? "" : response.message().content();
        return new CompletionResult(text, "ollama", model, Instant.now());
    }

    @Override
    public void completeStreaming(CompletionRequest request, Flow.Subscriber<String> subscriber) {
        CortexStreaming.bridge(
                streamChatResponses(chatRequest(request, true)),
                subscriber,
                chatResponse -> chatResponse.message() == null ? null : chatResponse.message().content());
    }

    private Flux<OllamaApi.ChatResponse> streamChatResponses(OllamaApi.ChatRequest chatRequest) {
        return webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequest)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return gson.fromJson(line, OllamaApi.ChatResponse.class);
                    } catch (JsonSyntaxException e) {
                        throw new CompletionException("Failed to parse Ollama streaming response line", e);
                    }
                });
    }

    @Override
    public String providerId() {
        return "ollama";
    }

    @Override
    public String modelId() {
        return model;
    }

    private OllamaApi.ChatRequest chatRequest(CompletionRequest request, boolean stream) {
        String promptText = request.render();

        Map<String, Object> options = new LinkedHashMap<>(request.providerOptions());
        options.remove(ENABLE_THINKING_KEY);
        if (request.temperature() != null) {
            options.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            options.put("num_predict", request.maxTokens());
        }

        OllamaApi.Message userMessage = OllamaApi.Message.builder(OllamaApi.Message.Role.USER)
                .content(promptText)
                .build();
        OllamaApi.ChatRequest.Builder builder = OllamaApi.ChatRequest.builder(model)
                .messages(List.of(userMessage))
                .stream(stream)
                .options(options);
        if (request.providerOptions().get(ENABLE_THINKING_KEY) instanceof Boolean enableThinking) {
            builder.think(enableThinking);
        }
        return builder.build();
    }

    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String model;

        private Builder() {
        }

        public Builder endpoint(java.net.URI endpoint) {
            this.baseUrl = endpoint.toString();
            return this;
        }

        /** Override only for testing against a fake server -- real usage should prefer {@link #endpoint}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public OllamaCortex build() {
            if (model == null) {
                throw new IllegalStateException("OllamaCortex requires a model -- e.g. \"qwen3:8b\"");
            }
            return new OllamaCortex(baseUrl, model);
        }
    }
}
