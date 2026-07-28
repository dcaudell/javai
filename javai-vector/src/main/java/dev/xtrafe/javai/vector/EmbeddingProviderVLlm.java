package dev.xtrafe.javai.vector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A real {@link JavAIEmbeddingProvider} backed by a self-hosted {@code vLLM} server: vLLM exposes an
 * OpenAI-compatible {@code /v1/embeddings} endpoint when serving a pooling/embedding model, so this shares
 * the identical request/response wire shape {@link EmbeddingProviderOpenAI} hand-rolls -- see that class's
 * own javadoc for the parsing rationale. Unlike a hosted provider, there's no fixed default endpoint:
 * {@code baseUri} is always required, matching {@code javai-completion}'s own {@code CortexVLlm}.
 *
 * <p>Retries a {@code 429} via {@link RetrySupport}/{@link EndpointRateLimiter} (also this package), same
 * as every other provider in this family.
 *
 * <p><b>Not yet verified against a live endpoint</b> -- no self-hosted vLLM instance serving an embedding
 * model was available at implementation time. Covered by hermetic tests only.
 */
public final class EmbeddingProviderVLlm implements JavAIEmbeddingProvider {

    private final HttpClient httpClient;
    private final URI embedEndpoint;
    private final URI modelsEndpoint;
    private final String apiKey;
    private final String model;
    private final Integer maxInputTokensOverride;

    /** Discovered once from /v1/models, then reused. Zero means "asked, and the answer was unusable". */
    private volatile Integer discoveredMaxInputTokens;

    public EmbeddingProviderVLlm(URI baseUri, String model) {
        this(baseUri, null, model);
    }

    /** Most self-hosted vLLM deployments don't require an API key; only pass one if yours does. */
    public EmbeddingProviderVLlm(URI baseUri, String apiKey, String model) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUri, apiKey, model,
                null);
    }

    /**
     * Pins the model's maximum input size instead of discovering or guessing it, for when correctness
     * matters more than convenience -- the same escape hatch {@code Cortex.Builder.contextWindowTokens(int)}
     * offers on the completion side. Wins over both runtime discovery and the {@link EmbeddingModelLimits}
     * table.
     */
    public EmbeddingProviderVLlm(URI baseUri, String apiKey, String model, int maxInputTokens) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUri, apiKey, model,
                maxInputTokens);
    }

    EmbeddingProviderVLlm(HttpClient httpClient, URI baseUri, String apiKey, String model) {
        this(httpClient, baseUri, apiKey, model, null);
    }

    EmbeddingProviderVLlm(HttpClient httpClient, URI baseUri, String apiKey, String model,
            Integer maxInputTokensOverride) {
        this.httpClient = httpClient;
        this.embedEndpoint = baseUri.resolve("/v1/embeddings");
        this.modelsEndpoint = baseUri.resolve("/v1/models");
        this.apiKey = apiKey;
        this.model = model;
        this.maxInputTokensOverride = maxInputTokensOverride;
    }

    /**
     * Asks vLLM's {@code /v1/models}, once, then falls back to {@link EmbeddingModelLimits}.
     *
     * <p>vLLM usually reports {@code max_model_len} per served model. "Usually" because it depends on how
     * the server was launched, which is why this degrades to the table rather than trusting the endpoint to
     * be there.
     */
    @Override
    public int maxInputTokens() {
        if (maxInputTokensOverride != null) {
            return maxInputTokensOverride;
        }
        Integer discovered = discoveredMaxInputTokens;
        if (discovered == null) {
            discovered = discoverMaxInputTokens();
            discoveredMaxInputTokens = discovered;
        }
        return discovered > 0 ? discovered : EmbeddingModelLimits.lookup(model);
    }

    private int discoverMaxInputTokens() {
        HttpRequest.Builder builder = HttpRequest.newBuilder(modelsEndpoint)
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? parseMaxModelLen(response.body()) : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /** {@code "max_model_len": N} from vLLM's /v1/models, or 0 when absent or unparseable. */
    static int parseMaxModelLen(String responseBody) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"max_model_len\"\\s*:\\s*(\\d+)")
                .matcher(responseBody);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    @Override
    public EmbeddingVector embed(String text) {
        // Same defensive substitution as EmbeddingProviderOllama -- see its javadoc.
        // Truncated client-side on every provider, not only the ones that would otherwise fail (OMI-216).
        // TEI and Ollama already truncate server-side -- and do it exactly, having real tokenizers -- so
        // deferring to them would preserve more text. Uniformity wins anyway: the whole complaint this fixes
        // is that identical text yields a correct vector, a quietly partial one, or an exception depending
        // only on which provider is configured. Cutting at the same estimated boundary everywhere makes the
        // outcome reproducible across providers, which matters more than the last few tokens. TEI keeps its
        // own "truncate": true as a server-side backstop regardless.
        String effectiveText =
                EmbeddingInputLimits.truncateToBudget(text.isEmpty() ? " " : text, maxInputTokens());
        String requestBody = "{\"model\":\"" + JsonStrings.escape(model) + "\",\"input\":\""
                + JsonStrings.escape(effectiveText) + "\"}";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(embedEndpoint)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest request = requestBuilder.build();

        String responseBody;
        try {
            responseBody = RetrySupport.withRetry(embedEndpoint.toString(), () -> send(request));
        } catch (TooManyRequestsException e) {
            throw new EmbeddingProviderException(
                    "Embedding endpoint " + embedEndpoint + " rate-limited too many times", e);
        }

        float[] values = EmbeddingProviderOpenAI.parseEmbeddingField(responseBody);
        return new EmbeddingVector(values, model, values.length, Instant.now());
    }

    /**
     * One request for every text, rather than one request per text (OMI-213) -- vLLM serves the same
     * OpenAI-compatible {@code /v1/embeddings} contract, so this is deliberately the same implementation
     * shape as {@link EmbeddingProviderOpenAI#embedAll}, sharing {@link OpenAiCompatibleEmbeddings} for the
     * response rather than duplicating a second copy of the index-ordering logic.
     *
     * <p>The index ordering matters here for a reason of vLLM's own, not merely inherited from OpenAI: vLLM
     * schedules batched inputs across continuous batches, so response order genuinely need not match request
     * order.
     */
    @Override
    public List<EmbeddingVector> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        // Resolved once for the batch, applied per member -- see EmbeddingProviderOpenAI.embedAll.
        int budget = maxInputTokens();
        List<String> substituted = new ArrayList<>(texts.size());
        for (String text : texts) {
            substituted.add(text.isEmpty() ? " " : text);
        }
        String inputs = JsonStrings.stringArray(EmbeddingInputLimits.truncateEach(substituted, budget));
        String requestBody = "{\"model\":\"" + JsonStrings.escape(model) + "\",\"input\":" + inputs + "}";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(embedEndpoint)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest request = requestBuilder.build();

        String responseBody;
        try {
            responseBody = RetrySupport.withRetry(embedEndpoint.toString(), () -> send(request));
        } catch (TooManyRequestsException e) {
            throw new EmbeddingProviderException(
                    "Embedding endpoint " + embedEndpoint + " rate-limited too many times", e);
        }

        List<float[]> rows = OpenAiCompatibleEmbeddings.parseIndexedRows(responseBody);
        if (rows.size() != texts.size()) {
            throw new EmbeddingProviderException("Embedding endpoint " + embedEndpoint + " returned "
                    + rows.size() + " embeddings for " + texts.size()
                    + " inputs; the batch response must line up with the request: " + responseBody);
        }
        Instant computedAt = Instant.now();
        List<EmbeddingVector> vectors = new ArrayList<>(rows.size());
        for (float[] values : rows) {
            vectors.add(new EmbeddingVector(values, model, values.length, computedAt));
        }
        return vectors;
    }

    private String send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new EmbeddingProviderException("Failed to reach embedding endpoint " + embedEndpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingProviderException(
                    "Interrupted while calling embedding endpoint " + embedEndpoint, e);
        }

        if (response.statusCode() == 429) {
            Duration retryAfter = RetryAfterParser.parse(response.headers().firstValue("Retry-After").orElse(null));
            throw new TooManyRequestsException(
                    "Embedding endpoint " + embedEndpoint + " returned HTTP 429: " + response.body(), retryAfter);
        }
        if (response.statusCode() != 200) {
            throw new EmbeddingProviderException("Embedding endpoint " + embedEndpoint + " returned HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    public static final class EmbeddingProviderException extends RuntimeException {
        EmbeddingProviderException(String message) {
            super(message);
        }

        EmbeddingProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public String modelId() {
        return model;
    }
}
