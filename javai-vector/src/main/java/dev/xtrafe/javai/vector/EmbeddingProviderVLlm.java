package dev.xtrafe.javai.vector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

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
    private final String apiKey;
    private final String model;

    public EmbeddingProviderVLlm(URI baseUri, String model) {
        this(baseUri, null, model);
    }

    /** Most self-hosted vLLM deployments don't require an API key; only pass one if yours does. */
    public EmbeddingProviderVLlm(URI baseUri, String apiKey, String model) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUri, apiKey, model);
    }

    EmbeddingProviderVLlm(HttpClient httpClient, URI baseUri, String apiKey, String model) {
        this.httpClient = httpClient;
        this.embedEndpoint = baseUri.resolve("/v1/embeddings");
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public EmbeddingVector embed(String text) {
        // Same defensive substitution as EmbeddingProviderOllama -- see its javadoc.
        String effectiveText = text.isEmpty() ? " " : text;
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
}
