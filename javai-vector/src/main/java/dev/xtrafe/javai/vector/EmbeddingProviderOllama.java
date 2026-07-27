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
 * An alternate real {@link JavAIEmbeddingProvider}: a thin HTTP client against
 * <a href="https://ollama.com">Ollama</a>'s {@code /api/embed} endpoint. Not the whitepaper's Phase 0
 * default (that's {@link EmbeddingProviderTextEmbeddingsInference} against TEI, per §4.5.2) -- this exists because
 * TEI's Candle backend has a confirmed, unresolved upstream bug running {@code Qwen/Qwen3-Embedding-0.6B}
 * on CPU ("Intel MKL ERROR: Parameter 8 was incorrect on entry to SGEMM", reported on native x86_64/AMD
 * hardware too, not just under emulation -- see
 * <a href="https://github.com/huggingface/text-embeddings-inference/issues/667">issue #667</a> and
 * <a href="https://github.com/huggingface/text-embeddings-inference/issues/636">#636</a>). Ollama runs
 * {@code qwen3-embedding} through a completely different stack (a GGUF build via llama.cpp), unaffected by
 * that bug, and confirmed to run natively on Apple Silicon (no x86_64 emulation at all). Exactly the
 * "swapping the embedding provider entirely... is a configuration key, not a code change" flexibility
 * doc/spec/vector-core.md's {@code JavAIEmbeddingProvider} SPI is designed for.
 *
 * <p>Hand-rolls the response JSON rather than pulling in a JSON library, same rationale as
 * {@link EmbeddingProviderTextEmbeddingsInference}: Ollama's {@code /api/embed} response is a JSON object with an
 * {@code "embeddings"} key holding {@code [[float, ...]]}, plus a few fields (timing metrics, echoed
 * model name) this client doesn't need -- locating one key and reusing the same bracket-unwrapping is
 * simpler than a general parser.
 */
public final class EmbeddingProviderOllama implements JavAIEmbeddingProvider {

    private final HttpClient httpClient;
    private final URI embedEndpoint;
    private final String model;

    public EmbeddingProviderOllama(URI baseUri, String model) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUri, model);
    }

    EmbeddingProviderOllama(HttpClient httpClient, URI baseUri, String model) {
        this.httpClient = httpClient;
        this.embedEndpoint = baseUri.resolve("/api/embed");
        this.model = model;
    }

    @Override
    public EmbeddingVector embed(String text) {
        // Confirmed empirically: Ollama's /api/embed returns "embeddings": [] (zero rows, not a
        // placeholder/zero vector) for a genuinely empty input string -- unlike the fake providers used
        // elsewhere in this codebase's hermetic tests, which happily hash an empty string into a real-
        // shaped vector. CollectionVectorSupport.computeCentroid() relies on embed("") producing a real,
        // correctly-dimensioned vector for an empty collection (so it has *some* dimension to combine
        // arithmetically with a parent's summaryVector()); substituting a single space keeps that
        // contract true against real Ollama too, since Ollama embeds a lone space into a real vector.
        String effectiveText = text.isEmpty() ? " " : text;
        String requestBody =
                "{\"model\":\"" + JsonStrings.escape(model) + "\",\"input\":\"" + JsonStrings.escape(effectiveText) + "\"}";
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

        float[] values = parseEmbeddingsField(responseBody);
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
     * One request for every text, rather than one request per text. Ollama's {@code /api/embed} already
     * accepts {@code "input"} as an array and answers with one row per input, in order -- so the only thing
     * standing between a seeding loop and a single round trip was this provider always sending a scalar.
     *
     * <p>See {@link JavAIEmbeddingProvider#embedAll} for why this matters more than the call count: at the
     * ~10ms per embed measured in OMI-187, a 1,400-tag catalog is ~14s of sequential HTTP versus well under
     * a second batched.
     */
    @Override
    public List<EmbeddingVector> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        StringBuilder inputs = new StringBuilder();
        for (String text : texts) {
            if (!inputs.isEmpty()) {
                inputs.append(',');
            }
            // Same empty-input substitution as embed() -- see its comment for why Ollama needs it.
            inputs.append('"').append(JsonStrings.escape(text.isEmpty() ? " " : text)).append('"');
        }
        String requestBody = "{\"model\":\"" + JsonStrings.escape(model) + "\",\"input\":[" + inputs + "]}";
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

        List<float[]> rows = parseAllEmbeddingRows(responseBody);
        if (rows.size() != texts.size()) {
            throw new EmbeddingProviderException("Ollama returned " + rows.size() + " embeddings for "
                    + texts.size() + " inputs; the batch response must line up with the request: " + responseBody);
        }
        Instant computedAt = Instant.now();
        List<EmbeddingVector> vectors = new ArrayList<>(rows.size());
        for (float[] values : rows) {
            vectors.add(new EmbeddingVector(values, model, values.length, computedAt));
        }
        return vectors;
    }

    /**
     * Extracts the first row of {@code "embeddings": [[float, ...]]} out of Ollama's response object,
     * ignoring every other field in the JSON body.
     */
    static float[] parseEmbeddingsField(String responseBody) {
        List<float[]> rows = parseAllEmbeddingRows(responseBody);
        return rows.isEmpty() ? new float[0] : rows.get(0);
    }

    /** Every row of {@code "embeddings": [[...], [...]]}, in response order -- one per batched input. */
    static List<float[]> parseAllEmbeddingRows(String responseBody) {
        String key = "\"embeddings\"";
        int keyIndex = responseBody.indexOf(key);
        if (keyIndex < 0) {
            throw new EmbeddingProviderException("Ollama response missing \"embeddings\" field: " + responseBody);
        }
        int colonIndex = responseBody.indexOf(':', keyIndex + key.length());
        int outerStart = responseBody.indexOf('[', colonIndex);
        if (colonIndex < 0 || outerStart < 0) {
            throw new EmbeddingProviderException("Unexpected Ollama response shape: " + responseBody);
        }
        int outerEnd = responseBody.indexOf(']', outerStart);
        List<float[]> rows = new ArrayList<>();
        int cursor = outerStart + 1;
        while (true) {
            int rowStart = responseBody.indexOf('[', cursor);
            // Past the closing bracket of the outer array means there are no further rows to read. Recomputed
            // rather than cached, since each row's ']' shifts where the outer one is found.
            int outerClose = responseBody.indexOf(']', cursor);
            if (rowStart < 0 || (outerClose >= 0 && outerClose < rowStart)) {
                break;
            }
            int rowEnd = responseBody.indexOf(']', rowStart);
            if (rowEnd < 0) {
                throw new EmbeddingProviderException("Unexpected Ollama response shape: " + responseBody);
            }
            rows.add(parseRow(responseBody.substring(rowStart + 1, rowEnd)));
            cursor = rowEnd + 1;
        }
        if (rows.isEmpty() && outerEnd < 0) {
            throw new EmbeddingProviderException("Unexpected Ollama response shape: " + responseBody);
        }
        return rows;
    }

    private static float[] parseRow(String row) {
        String trimmed = row.strip();
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
