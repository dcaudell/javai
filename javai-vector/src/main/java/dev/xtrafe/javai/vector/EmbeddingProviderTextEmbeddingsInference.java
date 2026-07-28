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
public final class EmbeddingProviderTextEmbeddingsInference implements JavAIEmbeddingProvider {

    private final HttpClient httpClient;
    private final URI embedEndpoint;
    private final URI infoEndpoint;
    private final String modelId;
    private final Integer maxInputTokensOverride;

    /** Discovered once from /info, then reused. Zero means "asked, and the answer was unusable". */
    private volatile Integer discoveredMaxInputTokens;

    public EmbeddingProviderTextEmbeddingsInference(URI baseUri, String modelId) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUri, modelId, null);
    }

    /**
     * Pins the model's maximum input size instead of discovering or guessing it, for when correctness
     * matters more than convenience -- the same escape hatch {@code Cortex.Builder.contextWindowTokens(int)}
     * offers on the completion side. Wins over both runtime discovery and the {@link EmbeddingModelLimits}
     * table.
     */
    public EmbeddingProviderTextEmbeddingsInference(URI baseUri, String modelId, int maxInputTokens) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUri, modelId,
                maxInputTokens);
    }

    EmbeddingProviderTextEmbeddingsInference(HttpClient httpClient, URI baseUri, String modelId) {
        this(httpClient, baseUri, modelId, null);
    }

    EmbeddingProviderTextEmbeddingsInference(HttpClient httpClient, URI baseUri, String modelId,
            Integer maxInputTokensOverride) {
        this.httpClient = httpClient;
        this.embedEndpoint = baseUri.resolve("/embed");
        this.infoEndpoint = baseUri.resolve("/info");
        this.modelId = modelId;
        this.maxInputTokensOverride = maxInputTokensOverride;
    }

    /**
     * Asks TEI's {@code /info}, once, then falls back to {@link EmbeddingModelLimits}.
     *
     * <p>TEI reports {@code max_input_length} directly, which makes it the one bundled provider whose limit
     * is both discoverable and exact. A failed lookup falls through to the table rather than throwing: not
     * knowing the limit precisely is a reason to be conservative, never a reason to refuse to embed.
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
        return discovered > 0 ? discovered : EmbeddingModelLimits.lookup(modelId);
    }

    private int discoverMaxInputTokens() {
        HttpRequest request = HttpRequest.newBuilder(infoEndpoint)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? parseMaxInputLength(response.body()) : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /** {@code "max_input_length": N} from TEI's /info, or 0 when absent or unparseable. */
    static int parseMaxInputLength(String responseBody) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"max_input_length\"\\s*:\\s*(\\d+)")
                .matcher(responseBody);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    @Override
    public EmbeddingVector embed(String text) {
        // Truncated client-side like every other provider (OMI-216), so identical text produces the same
        // outcome whichever provider is configured -- see EmbeddingProviderOllama.embed for the full
        // reasoning. TEI's own "truncate": true stays as a
        // server-side backstop: it truncates exactly, having a real tokenizer, so it catches anything this
        // conservative estimate lets through.
        String effectiveText = EmbeddingInputLimits.truncateToBudget(text, maxInputTokens());
        String requestBody = "{\"inputs\":\"" + JsonStrings.escape(effectiveText) + "\",\"truncate\":true}";
        HttpRequest request = HttpRequest.newBuilder(embedEndpoint)
                .header("Content-Type", "application/json")
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

        float[] values = parseSingleRow(responseBody);
        return new EmbeddingVector(values, modelId, values.length, Instant.now());
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

    @Override
    public String modelId() {
        return modelId;
    }
}
