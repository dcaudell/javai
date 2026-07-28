package dev.xtrafe.javai.vector;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batched {@code embedAll} across the providers that support it (OMI-213).
 *
 * <h2>What is actually at risk here</h2>
 *
 * Batching is a pure latency optimization -- {@code embedAll}'s {@code default} loops and is already correct
 * -- so nothing here is about whether a vector comes back. It is about whether each vector comes back
 * attached to <b>the text it was computed from</b>.
 *
 * <p>That is the one thing an implementation can get wrong invisibly. OpenAI (and vLLM, serving the same
 * contract) return {@code data} entries carrying an explicit {@code index} and do not promise request order.
 * An implementation reading rows positionally would pair every text with the wrong vector: nothing throws,
 * every vector is well-formed and correctly dimensioned, and the only symptom is a semantic index that
 * returns subtly wrong neighbours forever. A slow loop beats a fast wrong answer, so the out-of-order case
 * below is the most important test in this file.
 */
class EmbeddingBatchingTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---- the failure this could plausibly have shipped with ----

    /**
     * OpenAI documents that {@code data} entries are not guaranteed to arrive in request order. Here the
     * server returns them deliberately reversed; the vectors must still line up with the inputs.
     */
    @Test
    void openAiRowsAreOrderedByIndexNotByArrayPosition() throws IOException {
        startServer("/v1/embeddings", """
                {"object":"list","data":[
                  {"object":"embedding","index":2,"embedding":[3.0,3.0]},
                  {"object":"embedding","index":0,"embedding":[1.0,1.0]},
                  {"object":"embedding","index":1,"embedding":[2.0,2.0]}],
                 "model":"test-model"}""");

        List<EmbeddingVector> vectors = new EmbeddingProviderOpenAI(baseUrl(), "k", "test-model")
                .embedAll(List.of("first", "second", "third"));

        assertArrayEquals(new float[] {1.0f, 1.0f}, vectors.get(0).values(), 1e-6f);
        assertArrayEquals(new float[] {2.0f, 2.0f}, vectors.get(1).values(), 1e-6f);
        assertArrayEquals(new float[] {3.0f, 3.0f}, vectors.get(2).values(), 1e-6f,
                "reading rows positionally would silently pair every text with the wrong vector");
    }

    @Test
    void vLlmRowsAreAlsoOrderedByIndex() throws IOException {
        startServer("/v1/embeddings", """
                {"data":[{"index":1,"embedding":[9.0]},{"index":0,"embedding":[8.0]}]}""");

        List<EmbeddingVector> vectors =
                new EmbeddingProviderVLlm(baseUri(), null, "test-model").embedAll(List.of("a", "b"));

        assertArrayEquals(new float[] {8.0f}, vectors.get(0).values(), 1e-6f);
        assertArrayEquals(new float[] {9.0f}, vectors.get(1).values(), 1e-6f);
    }

    /**
     * Indices that are not one each of {@code 0..n-1} mean the rows cannot be placed with confidence. Since
     * placing them wrongly would never be noticed, this refuses rather than guessing.
     */
    @Test
    void aResponseWithUnusableIndicesIsRefusedRatherThanGuessedAt() throws IOException {
        startServer("/v1/embeddings", """
                {"data":[{"index":0,"embedding":[1.0]},{"index":0,"embedding":[2.0]}]}""");

        var provider = new EmbeddingProviderOpenAI(baseUrl(), "k", "test-model");

        assertThrows(EmbeddingProviderOpenAI.EmbeddingProviderException.class,
                () -> provider.embedAll(List.of("a", "b")));
    }

    /** A response short by one row must fail loudly, not return a short or padded list. */
    @Test
    void aRowCountThatDoesNotMatchTheRequestIsRejected() throws IOException {
        startServer("/v1/embeddings", """
                {"data":[{"index":0,"embedding":[1.0]}]}""");

        var provider = new EmbeddingProviderOpenAI(baseUrl(), "k", "test-model");

        assertThrows(EmbeddingProviderOpenAI.EmbeddingProviderException.class,
                () -> provider.embedAll(List.of("a", "b", "c")));
    }

    /** A server reporting an index on only some rows is ambiguous, and treated as such. */
    @Test
    void partiallyIndexedRowsAreRefused() {
        assertThrows(EmbeddingProviderOpenAI.EmbeddingProviderException.class,
                () -> OpenAiCompatibleEmbeddings.parseIndexedRows("""
                        {"data":[{"index":0,"embedding":[1.0]},{"embedding":[2.0]}]}"""));
    }

    /** With no index anywhere, position is all there is -- and is used, rather than failing. */
    @Test
    void unindexedRowsFallBackToPositionalOrder() {
        List<float[]> rows = OpenAiCompatibleEmbeddings.parseIndexedRows("""
                {"data":[{"embedding":[1.0]},{"embedding":[2.0]}]}""");

        assertEquals(2, rows.size());
        assertArrayEquals(new float[] {1.0f}, rows.get(0), 1e-6f);
    }

    // ---- one request, not N ----

    @Test
    void openAiSendsOneRequestCarryingEveryText() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> captured = startCountingServer("/v1/embeddings", """
                {"data":[{"index":0,"embedding":[1.0]},{"index":1,"embedding":[2.0]},
                         {"index":2,"embedding":[3.0]}]}""", requests);

        new EmbeddingProviderOpenAI(baseUrl(), "k", "test-model")
                .embedAll(List.of("alpha", "bravo", "charlie"));

        assertEquals(1, requests.get(), "the entire point is one round trip, not one per text");
        assertTrue(captured.get().contains("\"input\":[\"alpha\",\"bravo\",\"charlie\"]"),
                "inputs must be sent as a JSON array: " + captured.get());
    }

    @Test
    void teiSendsOneRequestAndKeepsItsServerSideTruncateBackstop() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> captured =
                startCountingServer("/embed", "[[1.0,1.0],[2.0,2.0]]", requests);

        List<EmbeddingVector> vectors = new EmbeddingProviderTextEmbeddingsInference(baseUri(), "test-model")
                .embedAll(List.of("alpha", "bravo"));

        assertEquals(1, requests.get());
        assertTrue(captured.get().contains("\"inputs\":[\"alpha\",\"bravo\"]"), captured.get());
        assertTrue(captured.get().contains("\"truncate\":true"), captured.get());
        assertArrayEquals(new float[] {2.0f, 2.0f}, vectors.get(1).values(), 1e-6f);
    }

    /** TEI reports no index -- its response order *is* the correspondence -- so only the count can misalign. */
    @Test
    void teiRejectsARowCountThatDoesNotMatchTheRequest() throws IOException {
        startServer("/embed", "[[1.0]]");

        var provider = new EmbeddingProviderTextEmbeddingsInference(baseUri(), "test-model");

        assertThrows(EmbeddingProviderTextEmbeddingsInference.EmbeddingProviderException.class,
                () -> provider.embedAll(List.of("a", "b")));
    }

    // ---- properties that must hold for every batching provider ----

    @Test
    void anEmptyBatchCostsNoRequestAtAll() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        startCountingServer("/v1/embeddings", "{\"data\":[]}", requests);

        assertEquals(List.of(), new EmbeddingProviderOpenAI(baseUrl(), "k", "m").embedAll(List.of()));
        assertEquals(0, requests.get(), "an empty batch must not reach the network");
    }

    @Test
    void everyVectorCarriesTheProvidersModelId() throws IOException {
        startServer("/v1/embeddings", """
                {"data":[{"index":0,"embedding":[1.0]},{"index":1,"embedding":[2.0]}]}""");

        List<EmbeddingVector> vectors =
                new EmbeddingProviderOpenAI(baseUrl(), "k", "text-embedding-3-small").embedAll(List.of("a", "b"));

        assertTrue(vectors.stream().allMatch(v -> "text-embedding-3-small".equals(v.modelId())));
    }

    /**
     * Batching must not bypass 429 backoff. Every provider's single-text path already retries through
     * {@code RetrySupport}/{@code EndpointRateLimiter}; a batched call that skipped it would be a
     * regression, and the shared limiter is keyed by endpoint precisely so all callers coordinate.
     */
    @Test
    void aBatchedCallStillRetriesAfterA429() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] body;
            if (requests.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                body = "rate limited".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
            } else {
                body = "{\"data\":[{\"index\":0,\"embedding\":[7.0]}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
            }
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        List<EmbeddingVector> vectors =
                new EmbeddingProviderOpenAI(baseUrl(), "k", "test-model").embedAll(List.of("only"));

        assertEquals(2, requests.get(), "must have retried exactly once after the 429");
        assertArrayEquals(new float[] {7.0f}, vectors.get(0).values(), 1e-6f);
    }

    /** An over-long member is bounded on its own; its neighbours must survive intact. */
    @Test
    void oneOverLongTextDoesNotShortenItsNeighbours() throws IOException {
        AtomicReference<String> captured = startCountingServer("/v1/embeddings", """
                {"data":[{"index":0,"embedding":[1.0]},{"index":1,"embedding":[2.0]}]}""",
                new AtomicInteger());

        new EmbeddingProviderOpenAI(baseUrl(), "k", "unknown-model", 100)
                .embedAll(List.of("a short neighbour", "word ".repeat(10_000)));

        assertTrue(captured.get().contains("a short neighbour"),
                "the short input must be sent whole: " + captured.get());
    }

    // ---- helpers ----

    private void startServer(String path, String responseBody) throws IOException {
        startCountingServer(path, responseBody, new AtomicInteger());
    }

    private AtomicReference<String> startCountingServer(String path, String responseBody,
            AtomicInteger requestCount) throws IOException {
        AtomicReference<String> captured = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            requestCount.incrementAndGet();
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return captured;
    }

    private URI baseUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }
}
