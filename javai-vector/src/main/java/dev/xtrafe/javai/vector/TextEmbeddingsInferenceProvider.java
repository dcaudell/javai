package dev.xtrafe.javai.vector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Phase 0's real {@link JavAIEmbeddingProvider}: a thin HTTP client against Hugging Face's
 * text-embeddings-inference (TEI), run as a sidecar container (whitepaper §4.5.2, {@code docker/}). TEI's
 * REST contract is identical across the CPU/CUDA/Metal images, so this class never needs to know which
 * one is running behind {@code baseUri}.
 *
 * <p>Hand-rolls the request/response JSON rather than pulling in a JSON library: the shape is fixed and
 * trivial in both directions -- {@code {"inputs": "...", "truncate": true}} out,
 * {@code [[float, float, ...]]} back for a single-string request -- so a general-purpose parser would be
 * more machinery than the problem needs.
 */
public final class TextEmbeddingsInferenceProvider implements JavAIEmbeddingProvider {

    private final HttpClient httpClient;
    private final URI embedEndpoint;
    private final String modelId;

    public TextEmbeddingsInferenceProvider(URI baseUri, String modelId) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUri, modelId);
    }

    TextEmbeddingsInferenceProvider(HttpClient httpClient, URI baseUri, String modelId) {
        this.httpClient = httpClient;
        this.embedEndpoint = baseUri.resolve("/embed");
        this.modelId = modelId;
    }

    @Override
    public EmbeddingVector embed(String text) {
        String requestBody = "{\"inputs\":\"" + JsonStrings.escape(text) + "\",\"truncate\":true}";
        HttpRequest request = HttpRequest.newBuilder(embedEndpoint)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

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

        if (response.statusCode() != 200) {
            throw new EmbeddingProviderException("Embedding endpoint " + embedEndpoint + " returned HTTP "
                    + response.statusCode() + ": " + response.body());
        }

        float[] values = parseSingleRow(response.body());
        return new EmbeddingVector(values, modelId, values.length, Instant.now());
    }

    /** TEI's response to a single-string {@code /embed} request: one row, {@code [[float, ...]]}. */
    static float[] parseSingleRow(String responseBody) {
        String trimmed = responseBody.strip();
        String row = unwrapBrackets(trimmed, "response");
        String elements = unwrapBrackets(row, "embedding row");
        if (elements.isBlank()) {
            return new float[0];
        }
        String[] parts = elements.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].strip());
        }
        return values;
    }

    private static String unwrapBrackets(String json, String what) {
        if (!json.startsWith("[") || !json.endsWith("]")) {
            throw new EmbeddingProviderException("Unexpected TEI " + what + " shape: " + json);
        }
        return json.substring(1, json.length() - 1).strip();
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
