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

    /** Discovered once from /info, then reused. A zero in any field means "asked, and that answer was
     *  unusable" -- see {@link #info()}. */
    private volatile Info discoveredInfo;

    /**
     * What TEI's {@code /info} says about its own limits. All three come back in one response, so they are
     * fetched and cached together rather than costing a round trip each (OMI-266).
     *
     * @param maxInputLength     {@code max_input_length} -- one input's token ceiling
     * @param maxClientBatchSize {@code max_client_batch_size} -- inputs per request, TEI default 32
     * @param maxBatchTokens     {@code max_batch_tokens} -- total tokens per request, TEI default 16384
     */
    private record Info(int maxInputLength, int maxClientBatchSize, int maxBatchTokens) {

        static final Info UNKNOWN = new Info(0, 0, 0);
    }

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
        int discovered = info().maxInputLength();
        return discovered > 0 ? discovered : EmbeddingModelLimits.lookup(modelId);
    }

    /**
     * TEI's own {@code max_client_batch_size} (OMI-266) -- the one bundled provider that can be asked.
     *
     * <p>Worth knowing this is not a formality: TEI's default is <b>32</b>, below the 100 this library
     * chunked at before batch limits existed, so a default TEI deployment refused a full batch. Falling back
     * to {@link EmbeddingBatchLimits#DEFAULT_MAX_BATCH_SIZE} when {@code /info} cannot be read keeps that
     * case no worse than it was, rather than guessing TEI's default for a server that may have been
     * configured away from it.
     */
    @Override
    public int maxBatchSize() {
        int discovered = info().maxClientBatchSize();
        return discovered > 0 ? discovered : EmbeddingBatchLimits.DEFAULT_MAX_BATCH_SIZE;
    }

    /** TEI's own {@code max_batch_tokens} (OMI-266); TEI's default is 16,384. */
    @Override
    public int maxBatchTokens() {
        int discovered = info().maxBatchTokens();
        return discovered > 0 ? discovered : EmbeddingBatchLimits.DEFAULT_MAX_BATCH_TOKENS;
    }

    /**
     * Asks TEI's {@code /info}, once, caching all three limits together.
     *
     * <p>TEI reports them directly, which makes it the one bundled provider whose limits are both
     * discoverable and exact. A failed lookup falls through to each caller's own fallback rather than
     * throwing: not knowing a limit precisely is a reason to be conservative, never a reason to refuse to
     * embed.
     */
    private Info info() {
        Info cached = discoveredInfo;
        if (cached == null) {
            cached = discoverInfo();
            discoveredInfo = cached;
        }
        return cached;
    }

    private Info discoverInfo() {
        HttpRequest request = HttpRequest.newBuilder(infoEndpoint)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? parseInfo(response.body()) : Info.UNKNOWN;
        } catch (IOException | RuntimeException e) {
            return Info.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Info.UNKNOWN;
        }
    }

    /** The three limit fields from TEI's /info; any field absent or unparseable comes back as 0. */
    private static Info parseInfo(String responseBody) {
        return new Info(parseIntField(responseBody, "max_input_length"),
                parseIntField(responseBody, "max_client_batch_size"),
                parseIntField(responseBody, "max_batch_tokens"));
    }

    private static int parseIntField(String responseBody, String fieldName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + fieldName + "\"\\s*:\\s*(\\d+)")
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

    /**
     * One request for every text, rather than one request per text (OMI-213). TEI's {@code /embed} already
     * accepts {@code "inputs"} as an array and answers {@code [[...], [...]]} -- one row per input, in
     * request order.
     *
     * <p>Unlike the OpenAI-compatible providers there is no {@code index} field to order by, and none is
     * needed: TEI's response is a bare array whose position <em>is</em> the correspondence. The row count is
     * still checked against the request, since that is the only remaining way this could silently
     * misalign.
     */
    @Override
    public List<EmbeddingVector> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        // Resolved once for the batch, applied per member: one over-long text must not shorten its
        // neighbours. TEI's own "truncate": true stays on as the exact server-side backstop.
        int budget = maxInputTokens();
        String inputs = JsonStrings.stringArray(EmbeddingInputLimits.truncateEach(texts, budget));
        String requestBody = "{\"inputs\":" + inputs + ",\"truncate\":true}";
        HttpRequest request = HttpRequest.newBuilder(embedEndpoint)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        String responseBody;
        try {
            responseBody = RetrySupport.withRetry(embedEndpoint.toString(), () -> send(request));
        } catch (TooManyRequestsException e) {
            throw new EmbeddingProviderException(
                    "Embedding endpoint " + embedEndpoint + " rate-limited too many times", e);
        }

        List<float[]> rows = parseAllRows(responseBody);
        if (rows.size() != texts.size()) {
            throw new EmbeddingProviderException("TEI returned " + rows.size() + " embeddings for "
                    + texts.size() + " inputs; the batch response must line up with the request: "
                    + responseBody);
        }
        Instant computedAt = Instant.now();
        List<EmbeddingVector> vectors = new ArrayList<>(rows.size());
        for (float[] values : rows) {
            vectors.add(new EmbeddingVector(values, modelId, values.length, computedAt));
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

    /** TEI's response to a single-string {@code /embed} request: one row, {@code [[float, ...]]}. */
    static float[] parseSingleRow(String responseBody) {
        List<float[]> rows = parseAllRows(responseBody);
        if (rows.isEmpty()) {
            throw new EmbeddingProviderException("Unexpected TEI response shape: " + responseBody);
        }
        return rows.get(0);
    }

    /**
     * Every row of TEI's {@code [[...], [...]]} response, in order -- one per batched input.
     *
     * <p>Position is the whole correspondence here; TEI reports no per-row index, and needs none, because it
     * answers in request order.
     */
    static List<float[]> parseAllRows(String responseBody) {
        String inner = unwrapBrackets(responseBody.strip(), "response");
        List<float[]> rows = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int rowStart = inner.indexOf('[', cursor);
            if (rowStart < 0) {
                return rows;
            }
            int rowEnd = inner.indexOf(']', rowStart);
            if (rowEnd < 0) {
                throw new EmbeddingProviderException("Unexpected TEI embedding row shape: " + responseBody);
            }
            rows.add(parseFloats(inner.substring(rowStart + 1, rowEnd)));
            cursor = rowEnd + 1;
        }
    }

    private static float[] parseFloats(String elements) {
        String trimmed = elements.strip();
        if (trimmed.isBlank()) {
            return new float[0];
        }
        String[] parts = trimmed.split(",");
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
