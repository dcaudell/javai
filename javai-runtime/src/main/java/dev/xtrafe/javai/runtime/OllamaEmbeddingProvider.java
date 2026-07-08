package dev.xtrafe.javai.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * An alternate real {@link JavAIEmbeddingProvider}: a thin HTTP client against
 * <a href="https://ollama.com">Ollama</a>'s {@code /api/embed} endpoint. Not the whitepaper's Phase 0
 * default (that's {@link TextEmbeddingsInferenceProvider} against TEI, per §4.5.2) -- this exists because
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
 * {@link TextEmbeddingsInferenceProvider}: Ollama's {@code /api/embed} response is a JSON object with an
 * {@code "embeddings"} key holding {@code [[float, ...]]}, plus a few fields (timing metrics, echoed
 * model name) this client doesn't need -- locating one key and reusing the same bracket-unwrapping is
 * simpler than a general parser.
 */
public final class OllamaEmbeddingProvider implements JavAIEmbeddingProvider {

    private final HttpClient httpClient;
    private final URI embedEndpoint;
    private final String model;

    public OllamaEmbeddingProvider(URI baseUri, String model) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), baseUri, model);
    }

    OllamaEmbeddingProvider(HttpClient httpClient, URI baseUri, String model) {
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

        float[] values = parseEmbeddingsField(response.body());
        return new EmbeddingVector(values, model, values.length, Instant.now());
    }

    /**
     * Extracts the first row of {@code "embeddings": [[float, ...]]} out of Ollama's response object,
     * ignoring every other field in the JSON body.
     */
    static float[] parseEmbeddingsField(String responseBody) {
        String key = "\"embeddings\"";
        int keyIndex = responseBody.indexOf(key);
        if (keyIndex < 0) {
            throw new EmbeddingProviderException("Ollama response missing \"embeddings\" field: " + responseBody);
        }
        int colonIndex = responseBody.indexOf(':', keyIndex + key.length());
        int outerStart = responseBody.indexOf('[', colonIndex);
        int rowStart = responseBody.indexOf('[', outerStart + 1);
        int rowEnd = responseBody.indexOf(']', rowStart);
        if (colonIndex < 0 || outerStart < 0 || rowStart < 0 || rowEnd < 0) {
            throw new EmbeddingProviderException("Unexpected Ollama response shape: " + responseBody);
        }
        String row = responseBody.substring(rowStart + 1, rowEnd).strip();
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
}
