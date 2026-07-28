package dev.xtrafe.javai.vector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * A real {@link JavAIEmbeddingProvider} backed by OpenAI's hosted {@code /v1/embeddings} endpoint -- a
 * thin HTTP client hand-rolling request/response JSON, same rationale and shape as
 * {@link EmbeddingProviderOllama}/{@link EmbeddingProviderTextEmbeddingsInference}: the response's fixed,
 * well-known shape ({@code {"data":[{"embedding":[...]}], ...}}) isn't worth a general-purpose parser.
 *
 * <p>Retries a {@code 429} via {@link RetrySupport}/{@link EndpointRateLimiter} (also this package),
 * coordinating backoff with any other provider -- {@code Cortex} included, across the
 * {@code javai-vector}/{@code javai-completion} module boundary -- pointed at the same endpoint.
 *
 * <p><b>Not yet verified against a live endpoint</b> -- no OpenAI API key was available at implementation
 * time, mirroring {@code javai-completion}'s own {@code CortexOpenAI}. Covered by hermetic tests (request/
 * response mapping against a fake HTTP server) only.
 */
public final class EmbeddingProviderOpenAI implements JavAIEmbeddingProvider {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final HttpClient httpClient;
    private final URI embedEndpoint;
    private final String apiKey;
    private final String model;
    private final Integer maxInputTokensOverride;

    public EmbeddingProviderOpenAI(String apiKey, String model) {
        this(DEFAULT_BASE_URL, apiKey, model);
    }

    /**
     * Pins the model's maximum input size against OpenAI's own endpoint.
     *
     * <p>Exists so pinning the limit doesn't force a caller to also spell out a base URL they don't want to
     * change -- and, more importantly, so that writing it out doesn't quietly bind the API key to
     * {@code baseUrl} via the three-argument {@code (baseUrl, apiKey, model)} overload, which is what the
     * obvious shorthand would otherwise resolve to.
     */
    public EmbeddingProviderOpenAI(String apiKey, String model, int maxInputTokens) {
        this(DEFAULT_BASE_URL, apiKey, model, maxInputTokens);
    }

    /** Override only for testing against a fake server, or to point at an OpenAI-compatible proxy. */
    public EmbeddingProviderOpenAI(String baseUrl, String apiKey, String model) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUrl, apiKey, model,
                null);
    }

    /**
     * Pins the model's maximum input size, overriding {@link EmbeddingModelLimits}'s best-effort table.
     *
     * <p>Matters more here than on the self-hosted providers: OpenAI exposes no API reporting a model's
     * embedding input limit, so the table is the only other source. Reach for this whenever correctness
     * matters more than the table's convenience -- the same escape hatch
     * {@code Cortex.Builder.contextWindowTokens(int)} offers on the completion side.
     */
    public EmbeddingProviderOpenAI(String baseUrl, String apiKey, String model, int maxInputTokens) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUrl, apiKey, model,
                maxInputTokens);
    }

    EmbeddingProviderOpenAI(HttpClient httpClient, String baseUrl, String apiKey, String model) {
        this(httpClient, baseUrl, apiKey, model, null);
    }

    EmbeddingProviderOpenAI(HttpClient httpClient, String baseUrl, String apiKey, String model,
            Integer maxInputTokensOverride) {
        this.httpClient = httpClient;
        this.embedEndpoint = URI.create(baseUrl).resolve("/v1/embeddings");
        this.apiKey = apiKey;
        this.model = model;
        this.maxInputTokensOverride = maxInputTokensOverride;
    }

    /** The override when given, else {@link EmbeddingModelLimits}'s table -- OpenAI publishes no endpoint
     *  reporting an embedding model's input limit, so there is nothing to discover. */
    @Override
    public int maxInputTokens() {
        return maxInputTokensOverride != null ? maxInputTokensOverride : EmbeddingModelLimits.lookup(model);
    }

    @Override
    public EmbeddingVector embed(String text) {
        // Same defensive substitution as EmbeddingProviderOllama -- see its javadoc for why
        // CollectionVectorSupport.computeCentroid() needs embed("") to still yield a real, dimensioned
        // vector rather than relying on OpenAI's own (unconfirmed) handling of an empty input string.
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
        HttpRequest request = HttpRequest.newBuilder(embedEndpoint)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        String responseBody;
        try {
            responseBody = RetrySupport.withRetry(embedEndpoint.toString(), () -> send(request));
        } catch (TooManyRequestsException e) {
            throw new EmbeddingProviderException(
                    "Embedding endpoint " + embedEndpoint + " rate-limited too many times", e);
        }

        float[] values = parseEmbeddingField(responseBody);
        return new EmbeddingVector(values, model, values.length, Instant.now());
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

    /**
     * Extracts the first {@code "embedding":[...]} array nested inside {@code "data":[{...}]} -- OpenAI's
     * response shape (and, sharing the identical wire contract, {@link EmbeddingProviderVLlm}'s own
     * OpenAI-compatible response too). Ignores every other field ({@code object}, {@code index},
     * {@code model}, {@code usage}).
     */
    static float[] parseEmbeddingField(String responseBody) {
        String key = "\"embedding\"";
        int keyIndex = responseBody.indexOf(key);
        if (keyIndex < 0) {
            throw new EmbeddingProviderException("Response missing \"embedding\" field: " + responseBody);
        }
        int colonIndex = responseBody.indexOf(':', keyIndex + key.length());
        int arrayStart = responseBody.indexOf('[', colonIndex);
        int arrayEnd = responseBody.indexOf(']', arrayStart);
        if (colonIndex < 0 || arrayStart < 0 || arrayEnd < 0) {
            throw new EmbeddingProviderException("Unexpected response shape: " + responseBody);
        }
        String row = responseBody.substring(arrayStart + 1, arrayEnd).strip();
        if (row.isBlank()) {
            return new float[0];
        }
        String[] parts = row.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].strip());
        }
        return values;
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
