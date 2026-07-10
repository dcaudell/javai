package dev.xtrafe.javai.completion;

import dev.xtrafe.javai.vector.RetryAfterParser;
import dev.xtrafe.javai.vector.RetrySupport;
import dev.xtrafe.javai.vector.TooManyRequestsException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Flow;

/**
 * Connector to Replicate's hosted model API -- the one deliberate deviation from "wrap Spring AI, never
 * write a provider client" (see this module's README): Replicate has no Spring AI {@code ChatModel} at
 * all, and its API isn't chat-completions-shaped in the first place. A Replicate call creates a
 * <em>prediction</em> (a job), which is either already finished by the time the create call returns (using
 * the {@code Prefer: wait} header, requested here) or still running, in which case this client polls the
 * prediction's own status URL until it reaches a terminal state. Hand-rolls request/response JSON rather
 * than pulling in a library, same rationale as {@code EmbeddingProviderOllama}: the fields this client
 * actually needs (`status`, `output`, `error`, the polling URL) are a small, fixed set worth locating
 * directly rather than justifying a general parser.
 *
 * <p><b>Streaming is a first-pass simplification.</b> Replicate does support real token-level streaming
 * (a server-sent-events URL returned alongside the prediction), but implementing SSE parsing wasn't
 * justified for this pass given every other Cortex's streaming is unverified against a live endpoint
 * anyway (no API key available). {@link #completeStreaming} here computes the full result via
 * {@link #complete} and emits it as a single chunk -- correct per the {@link Cortex} contract (the
 * subscriber still sees exactly the completion text, once), just coarser-grained than real per-token
 * streaming. Worth revisiting once this connector is verified against a live endpoint.
 *
 * <p><b>Not yet verified against a live endpoint</b> -- no API key was available at implementation time.
 * Covered by hermetic tests (request/option-mapping, wait-then-poll behavior against a fake HTTP server)
 * only; see this module's README.
 */
public final class CortexReplicate implements Cortex {

    private static final String DEFAULT_BASE_URL = "https://api.replicate.com";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient httpClient;
    private final URI predictionsEndpoint;
    private final String apiToken;
    private final String model;
    private final Integer contextWindowTokensOverride;

    private CortexReplicate(HttpClient httpClient, String baseUrl, String apiToken, String model,
            Integer contextWindowTokensOverride) {
        this.httpClient = httpClient;
        // Replicate's "call an official model by owner/name directly" route -- no version hash needed,
        // matching how this project prefers the simplest correct call shape over a more ceremonial one.
        this.predictionsEndpoint = URI.create(baseUrl).resolve("/v1/models/" + model + "/predictions");
        this.apiToken = apiToken;
        this.model = model;
        this.contextWindowTokensOverride = contextWindowTokensOverride;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletionResult complete(CompletionRequest request) {
        String promptText = request.render(contextWindowTokens());

        String responseBody = createPrediction(promptText, request);
        String status = extractStringField(responseBody, "status");
        String pollUrl = extractStringField(responseBody, "get");
        Instant deadline = Instant.now().plus(POLL_TIMEOUT);

        String finalBody = responseBody;
        while (!isTerminal(status)) {
            if (Instant.now().isAfter(deadline)) {
                throw new CompletionException("Replicate prediction did not finish within " + POLL_TIMEOUT);
            }
            sleep(POLL_INTERVAL);
            finalBody = pollPrediction(pollUrl);
            status = extractStringField(finalBody, "status");
        }

        if (!"succeeded".equals(status)) {
            String error = extractStringField(finalBody, "error");
            throw new CompletionException("Replicate prediction " + status
                    + (error == null ? "" : ": " + error));
        }
        return new CompletionResult(extractOutputText(finalBody), "replicate", model, Instant.now());
    }

    @Override
    public void completeStreaming(CompletionRequest request, Flow.Subscriber<String> subscriber) {
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
            }
        });
        try {
            CompletionResult result = complete(request);
            subscriber.onNext(result.text());
            subscriber.onComplete();
        } catch (RuntimeException e) {
            subscriber.onError(e);
        }
    }

    @Override
    public String providerId() {
        return "replicate";
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public int contextWindowTokens() {
        return contextWindowTokensOverride != null ? contextWindowTokensOverride : ContextWindows.lookup(model);
    }

    private String createPrediction(String promptText, CompletionRequest request) {
        StringBuilder input = new StringBuilder();
        input.append("{\"prompt\":\"").append(JsonStrings.escape(promptText)).append('"');
        if (request.maxTokens() != null) {
            input.append(",\"max_new_tokens\":").append(request.maxTokens());
        }
        if (request.temperature() != null) {
            input.append(",\"temperature\":").append(request.temperature());
        }
        for (var entry : request.providerOptions().entrySet()) {
            input.append(',').append('"').append(JsonStrings.escape(entry.getKey())).append("\":");
            appendJsonValue(input, entry.getValue());
        }
        input.append('}');
        String requestBody = "{\"input\":" + input + "}";

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
            throw new CompletionException("Replicate rate-limited too many times calling " + httpRequest.uri(), e);
        }
    }

    private String doSend(HttpRequest httpRequest) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new CompletionException("Failed to reach Replicate at " + httpRequest.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException("Interrupted while calling Replicate at " + httpRequest.uri(), e);
        }
        if (response.statusCode() == 429) {
            Duration retryAfter = RetryAfterParser.parse(response.headers().firstValue("Retry-After").orElse(null));
            throw new TooManyRequestsException(
                    "Replicate returned HTTP 429: " + response.body(), retryAfter);
        }
        if (response.statusCode() >= 300) {
            throw new CompletionException("Replicate returned HTTP " + response.statusCode() + ": " + response.body());
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
            throw new CompletionException("Interrupted while waiting for Replicate prediction", e);
        }
    }

    private static void appendJsonValue(StringBuilder out, Object value) {
        if (value instanceof String s) {
            out.append('"').append(JsonStrings.escape(s)).append('"');
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else {
            out.append('"').append(JsonStrings.escape(String.valueOf(value))).append('"');
        }
    }

    /** Locates {@code "fieldName":"value"} (a flat string field) -- used for {@code status}/{@code error},
     *  and for {@code get} inside the nested {@code "urls":{"get":"...","cancel":"..."}} object (the key
     *  name alone is enough to find unambiguously in Replicate's own fixed response shape). Returns
     *  {@code null} if the field is absent or JSON {@code null}. */
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
            throw new CompletionException("Expected a JSON string for \"" + fieldName + "\" in: " + body);
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

    /** Replicate's {@code output} field is either a JSON array of string chunks (joined here into one
     *  result -- the common shape for text models) or a single JSON string; both are handled. */
    private static String extractOutputText(String body) {
        String key = "\"output\"";
        int keyIndex = body.indexOf(key);
        if (keyIndex < 0) {
            throw new CompletionException("Replicate response missing \"output\" field: " + body);
        }
        int colonIndex = body.indexOf(':', keyIndex + key.length());
        int valueStart = colonIndex + 1;
        while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
            valueStart++;
        }
        if (body.charAt(valueStart) == '"') {
            return extractStringField(body, "output");
        }
        if (body.charAt(valueStart) != '[') {
            throw new CompletionException("Unexpected \"output\" shape in Replicate response: " + body);
        }
        int arrayEnd = body.indexOf(']', valueStart);
        String arrayBody = body.substring(valueStart + 1, arrayEnd).strip();
        if (arrayBody.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        int i = 0;
        while (i < arrayBody.length()) {
            if (arrayBody.charAt(i) == '"') {
                int end = i + 1;
                StringBuilder chunk = new StringBuilder();
                while (arrayBody.charAt(end) != '"') {
                    char c = arrayBody.charAt(end);
                    if (c == '\\') {
                        end++;
                        chunk.append(arrayBody.charAt(end));
                    } else {
                        chunk.append(c);
                    }
                    end++;
                }
                joined.append(chunk);
                i = end + 1;
            } else {
                i++;
            }
        }
        return joined.toString();
    }

    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiToken;
        private String model;
        private Integer contextWindowTokens;
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

        /** {@code owner/model-name}, e.g. {@code "meta/meta-llama-3-70b-instruct"}. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Overrides {@link ContextWindows}'s best-effort lookup for this model -- Replicate hosts an
         *  enormous, ever-changing model catalog this table can't hope to track, so this is worth setting
         *  explicitly whenever it's known. */
        public Builder contextWindowTokens(int contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
            return this;
        }

        Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public CortexReplicate build() {
            if (model == null) {
                throw new IllegalStateException(
                        "CortexReplicate requires a model -- e.g. \"meta/meta-llama-3-70b-instruct\"");
            }
            return new CortexReplicate(httpClient, baseUrl, apiToken, model, contextWindowTokens);
        }
    }
}
