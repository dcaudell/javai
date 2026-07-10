package dev.xtrafe.javai.vector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * A best-effort {@link JavAIEmbeddingProvider} backed by Replicate's hosted model API -- the same
 * create-a-prediction-then-poll-until-terminal pattern {@code javai-completion}'s {@code CortexReplicate}
 * already implements (see that class's javadoc for the full rationale), adapted for an embeddings-shaped
 * output rather than a chat-completion-shaped one.
 *
 * <p><b>Unlike every other provider in this family, there is no single, stable, vendor-wide contract to
 * implement.</b> Every model hosted on Replicate defines its own {@code cog predict()} input/output schema;
 * there's no equivalent of OpenAI's or vLLM's fixed {@code /v1/embeddings} shape. This class picks a
 * reasonable, documented default (the {@code owner/name} of a popular embedding model, and the input field
 * name most such models expose) and lets both be overridden -- but if the model you actually run doesn't
 * match those defaults, you'll need to set {@link Builder#inputFieldName(String)} to whatever your chosen
 * model's {@code cog predict()} signature actually names its text argument. The default model referenced
 * here ({@code beautyyuyanli/multilingual-e5-large}, chosen for being the most heavily used embedding model
 * in Replicate's own "Embedding models" collection at the time this class was written) has <b>not</b> been
 * confirmed against its live API -- its exact schema could not be retrieved with confidence during
 * implementation. Treat the default as a starting point to verify yourself, not a guarantee.
 *
 * <p>Output parsing is deliberately tolerant of the two shapes commonly seen across Replicate embedding
 * models: a flat {@code "output": [0.1, 0.2, ...]} array (single embedding), or a nested
 * {@code "output": [[0.1, 0.2, ...]]} array (a one-row batch) -- the first row is used either way. If your
 * model's output doesn't match either shape, {@link #embed(String)} throws {@link EmbeddingProviderException}
 * rather than silently returning something wrong.
 *
 * <p><b>Not yet verified against a live endpoint</b> -- no API token was available at implementation time,
 * and (per the caveats above) the exact model schema itself is unconfirmed. Covered by hermetic tests
 * (request/option-mapping, wait-then-poll behavior, both output shapes) against a fake HTTP server only.
 */
public final class EmbeddingProviderReplicate implements JavAIEmbeddingProvider {

    private static final String DEFAULT_BASE_URL = "https://api.replicate.com";
    private static final String DEFAULT_MODEL = "beautyyuyanli/multilingual-e5-large";
    private static final String DEFAULT_INPUT_FIELD_NAME = "text";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient httpClient;
    private final URI predictionsEndpoint;
    private final String apiToken;
    private final String model;
    private final String inputFieldName;

    private EmbeddingProviderReplicate(HttpClient httpClient, String baseUrl, String apiToken, String model,
            String inputFieldName) {
        this.httpClient = httpClient;
        this.predictionsEndpoint = URI.create(baseUrl).resolve("/v1/models/" + model + "/predictions");
        this.apiToken = apiToken;
        this.model = model;
        this.inputFieldName = inputFieldName;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public EmbeddingVector embed(String text) {
        String effectiveText = text.isEmpty() ? " " : text;
        String responseBody = createPrediction(effectiveText);
        String status = extractStringField(responseBody, "status");
        String pollUrl = extractStringField(responseBody, "get");
        Instant deadline = Instant.now().plus(POLL_TIMEOUT);

        String finalBody = responseBody;
        while (!isTerminal(status)) {
            if (Instant.now().isAfter(deadline)) {
                throw new EmbeddingProviderException(
                        "Replicate prediction did not finish within " + POLL_TIMEOUT);
            }
            sleep(POLL_INTERVAL);
            finalBody = pollPrediction(pollUrl);
            status = extractStringField(finalBody, "status");
        }

        if (!"succeeded".equals(status)) {
            String error = extractStringField(finalBody, "error");
            throw new EmbeddingProviderException("Replicate prediction " + status
                    + (error == null ? "" : ": " + error));
        }

        float[] values = extractOutputEmbedding(finalBody);
        return new EmbeddingVector(values, model, values.length, Instant.now());
    }

    private String createPrediction(String text) {
        String requestBody = "{\"input\":{\"" + JsonStrings.escape(inputFieldName) + "\":\""
                + JsonStrings.escape(text) + "\"}}";
        HttpRequest httpRequest = HttpRequest.newBuilder(predictionsEndpoint)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (apiToken == null ? "" : apiToken))
                .header("Prefer", "wait")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        return send(httpRequest);
    }

    private String pollPrediction(String pollUrl) {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(pollUrl))
                .header("Authorization", "Bearer " + (apiToken == null ? "" : apiToken))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return send(httpRequest);
    }

    private String send(HttpRequest httpRequest) {
        try {
            return RetrySupport.withRetry(predictionsEndpoint.toString(), () -> doSend(httpRequest));
        } catch (TooManyRequestsException e) {
            throw new EmbeddingProviderException(
                    "Replicate rate-limited too many times calling " + httpRequest.uri(), e);
        }
    }

    private String doSend(HttpRequest httpRequest) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new EmbeddingProviderException("Failed to reach Replicate at " + httpRequest.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingProviderException("Interrupted while calling Replicate at " + httpRequest.uri(), e);
        }
        if (response.statusCode() == 429) {
            Duration retryAfter = RetryAfterParser.parse(response.headers().firstValue("Retry-After").orElse(null));
            throw new TooManyRequestsException("Replicate returned HTTP 429: " + response.body(), retryAfter);
        }
        if (response.statusCode() >= 300) {
            throw new EmbeddingProviderException(
                    "Replicate returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static boolean isTerminal(String status) {
        return "succeeded".equals(status) || "failed".equals(status) || "canceled".equals(status);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingProviderException("Interrupted while waiting for Replicate prediction", e);
        }
    }

    /** Locates {@code "fieldName":"value"} (a flat string field), returning {@code null} if absent or JSON
     *  {@code null} -- same shape as {@code CortexReplicate}'s identical helper. */
    private static String extractStringField(String body, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIndex = body.indexOf(key);
        if (keyIndex < 0) {
            return null;
        }
        int colonIndex = body.indexOf(':', keyIndex + key.length());
        int valueStart = colonIndex + 1;
        while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
            valueStart++;
        }
        if (body.startsWith("null", valueStart)) {
            return null;
        }
        if (body.charAt(valueStart) != '"') {
            throw new EmbeddingProviderException("Expected a JSON string for \"" + fieldName + "\" in: " + body);
        }
        int valueEnd = valueStart + 1;
        StringBuilder value = new StringBuilder();
        while (body.charAt(valueEnd) != '"') {
            char c = body.charAt(valueEnd);
            if (c == '\\') {
                valueEnd++;
                value.append(body.charAt(valueEnd));
            } else {
                value.append(c);
            }
            valueEnd++;
        }
        return value.toString();
    }

    /**
     * Extracts {@code output} as a row of floats, tolerating either a flat {@code [0.1, 0.2, ...]} array
     * or a nested {@code [[0.1, 0.2, ...]]} one-row batch -- see this class's own javadoc for why both
     * shapes are accepted. Throws {@link EmbeddingProviderException} for anything else (e.g. a model whose
     * output isn't a numeric array at all).
     */
    static float[] extractOutputEmbedding(String body) {
        String key = "\"output\"";
        int keyIndex = body.indexOf(key);
        if (keyIndex < 0) {
            throw new EmbeddingProviderException("Replicate response missing \"output\" field: " + body);
        }
        int colonIndex = body.indexOf(':', keyIndex + key.length());
        int valueStart = colonIndex + 1;
        while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
            valueStart++;
        }
        if (body.charAt(valueStart) != '[') {
            throw new EmbeddingProviderException("Unexpected \"output\" shape in Replicate response: " + body);
        }
        int rowStart = valueStart;
        int afterOpen = valueStart + 1;
        while (afterOpen < body.length() && Character.isWhitespace(body.charAt(afterOpen))) {
            afterOpen++;
        }
        if (afterOpen < body.length() && body.charAt(afterOpen) == '[') {
            rowStart = afterOpen;
        }
        int rowEnd = body.indexOf(']', rowStart);
        if (rowEnd < 0) {
            throw new EmbeddingProviderException("Unexpected \"output\" shape in Replicate response: " + body);
        }
        String row = body.substring(rowStart + 1, rowEnd).strip();
        if (row.isBlank()) {
            return new float[0];
        }
        String[] parts = row.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Float.parseFloat(parts[i].strip());
            } catch (NumberFormatException e) {
                throw new EmbeddingProviderException(
                        "Replicate \"output\" doesn't look like a numeric embedding: " + body, e);
            }
        }
        return values;
    }

    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiToken;
        private String model = DEFAULT_MODEL;
        private String inputFieldName = DEFAULT_INPUT_FIELD_NAME;
        private HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        private Builder() {
        }

        /** Override only for testing against a fake server -- real usage never needs this. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiToken(String apiToken) {
            this.apiToken = apiToken;
            return this;
        }

        /** {@code owner/model-name}, e.g. {@code "beautyyuyanli/multilingual-e5-large"} (the default). */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** The JSON input field your chosen model's {@code cog predict()} expects for the text to embed --
         *  defaults to {@code "text"}, but Replicate has no vendor-wide standard, so verify against your
         *  model's own schema before relying on the default. */
        public Builder inputFieldName(String inputFieldName) {
            this.inputFieldName = inputFieldName;
            return this;
        }

        Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public EmbeddingProviderReplicate build() {
            return new EmbeddingProviderReplicate(httpClient, baseUrl, apiToken, model, inputFieldName);
        }
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
