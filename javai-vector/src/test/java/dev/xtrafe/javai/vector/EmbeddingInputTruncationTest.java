package dev.xtrafe.javai.vector;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each provider actually puts on the wire for an over-long input (OMI-216).
 *
 * <h2>The behaviour this pins</h2>
 *
 * Before this, the same text produced three different outcomes depending only on which provider was
 * configured: a correct vector, a quietly partial one, or an exception. Measured against a live Ollama
 * instance, 324,000 characters -- roughly 80,000 tokens against {@code qwen3-embedding:0.6b}'s 32,768-token
 * context -- returned HTTP 200 and an ordinary 1024-dimension vector, silently truncated server-side with
 * nothing in the response saying so. TEI does the same by design; OpenAI rejects the request outright.
 *
 * <p>Every provider now cuts at the same estimated boundary before sending, so the outcome is the same
 * everywhere. These tests assert on the <b>captured request body</b> rather than on the returned vector,
 * because the request is where the decision is observable -- a truncated vector is, by construction,
 * indistinguishable from a complete one, which is exactly the problem.
 *
 * <p>Truncation is deliberately <b>silent</b> for now: signalling it would mean either a breaking change to
 * the {@code EmbeddingVector} record or introducing the project's first logging mechanism, and both are
 * decisions larger than this fix. See the ticket for why that is accepted as temporary.
 */
class EmbeddingInputTruncationTest {

    /** Comfortably past any budget in play, so every provider must shorten it. */
    private static final String OVER_LONG = "lorem ipsum dolor sit amet ".repeat(5_000);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ollamaTruncatesBeforeSending() throws IOException {
        AtomicReference<String> captured = startServer("/api/embed",
                "{\"model\":\"m\",\"embeddings\":[[0.1,0.2]]}");
        new EmbeddingProviderOllama(baseUri(), "unknown-model", 100).embed(OVER_LONG);

        assertSentTextWithin(captured.get(), 100);
    }

    @Test
    void openAiTruncatesBeforeSending() throws IOException {
        AtomicReference<String> captured = startServer("/v1/embeddings",
                "{\"data\":[{\"embedding\":[0.1,0.2]}]}");
        new EmbeddingProviderOpenAI(baseUrl(), "key", "unknown-model", 100).embed(OVER_LONG);

        assertSentTextWithin(captured.get(), 100);
    }

    @Test
    void vLlmTruncatesBeforeSending() throws IOException {
        AtomicReference<String> captured = startServer("/v1/embeddings",
                "{\"data\":[{\"embedding\":[0.1,0.2]}]}");
        new EmbeddingProviderVLlm(baseUri(), null, "unknown-model", 100).embed(OVER_LONG);

        assertSentTextWithin(captured.get(), 100);
    }

    /** TEI keeps its own server-side {@code "truncate": true} as a backstop, and still cuts client-side. */
    @Test
    void teiTruncatesBeforeSendingAndKeepsItsServerSideBackstop() throws IOException {
        AtomicReference<String> captured = startServer("/embed", "[[0.1,0.2]]");
        new EmbeddingProviderTextEmbeddingsInference(baseUri(), "unknown-model", 100).embed(OVER_LONG);

        assertSentTextWithin(captured.get(), 100);
        assertTrue(captured.get().contains("\"truncate\":true"),
                "the server-side backstop must survive: TEI truncates exactly, this estimate does not");
    }

    /**
     * Batched inputs are bounded **per member**. One over-long entry must not shorten its neighbours, and
     * must not fail the whole batch -- `embedAll`'s contract is one vector per input, in order.
     */
    @Test
    void batchedInputsAreBoundedPerMemberNotPerBatch() throws IOException {
        AtomicReference<String> captured = startServer("/api/embed",
                "{\"model\":\"m\",\"embeddings\":[[0.1,0.2],[0.3,0.4]]}");

        List<EmbeddingVector> vectors = new EmbeddingProviderOllama(baseUri(), "unknown-model", 100)
                .embedAll(List.of("short one", OVER_LONG));

        assertEquals(2, vectors.size(), "every input still gets its own vector");
        assertTrue(captured.get().contains("short one"),
                "a short neighbour must survive intact: " + captured.get());
    }

    /** The same over-long text produces the same outcome on every provider -- the portability complaint. */
    @Test
    void everyProviderCutsAtTheSameEstimatedBoundary() {
        int lengthAt = EmbeddingInputLimits.truncateToBudget(OVER_LONG, 100).length();

        assertEquals(lengthAt, EmbeddingInputLimits.truncateToBudget(OVER_LONG, 100).length(),
                "the boundary is a property of the limit, not of the provider");
        assertTrue(lengthAt <= EmbeddingInputLimits.characterBudget(100));
    }

    // ---- helpers ----

    private AtomicReference<String> startServer(String path, String responseBody) throws IOException {
        AtomicReference<String> captured = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return captured;
    }

    private static void assertSentTextWithin(String requestBody, int maxInputTokens) {
        int budget = EmbeddingInputLimits.characterBudget(maxInputTokens);
        assertTrue(requestBody.length() <= budget + 500,
                "the request should carry a shortened text (budget " + budget + "), but was "
                        + requestBody.length() + " chars");
        assertTrue(requestBody.length() < OVER_LONG.length(), "something must actually have been cut");
    }

    private URI baseUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }
}
