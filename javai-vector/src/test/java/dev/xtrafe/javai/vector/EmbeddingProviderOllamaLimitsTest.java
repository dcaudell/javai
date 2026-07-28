package dev.xtrafe.javai.vector;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ollama's runtime discovery of its model's maximum input size (OMI-216).
 *
 * <h2>Why discovery is worth the round trip</h2>
 *
 * Measured against a live Ollama instance: a 324,000-character input -- roughly 80,000 tokens against
 * {@code qwen3-embedding:0.6b}'s 32,768-token context -- returned HTTP 200 and an ordinary 1024-dimension
 * vector. Ollama truncated it silently, and nothing in the response said so. Knowing the real limit is what
 * turns that from an invisible wrong answer into something JavAI can act on.
 *
 * <p>The response fixtures below are the <b>real shape</b> returned by {@code /api/show} on that instance,
 * not an invented one. That matters for the parser: the context length is reported under a key named for
 * the model's <em>architecture</em> ({@code "qwen3.context_length"}), not a fixed key, so anything looking
 * up a constant name would find nothing and silently fall back to the table.
 */
class EmbeddingProviderOllamaLimitsTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Abridged from a real {@code /api/show} response for {@code qwen3-embedding:0.6b}. */
    private static final String REAL_SHOW_RESPONSE = """
            {"modelfile":"FROM /root/.ollama/models/blobs/sha256-abc",
             "details":{"family":"qwen3","parameter_size":"595.8M"},
             "model_info":{"general.architecture":"qwen3",
                           "qwen3.attention.key_length":128,
                           "qwen3.context_length":32768,
                           "qwen3.embedding_length":1024,
                           "qwen3.feed_forward_length":3072},
             "capabilities":["completion","embedding"]}""";

    @Test
    void theArchitecturePrefixedContextLengthIsFound() {
        assertEquals(32_768, EmbeddingProviderOllama.parseContextLength(REAL_SHOW_RESPONSE),
                "the key is named for the architecture, so a fixed-name lookup would miss it");
    }

    @Test
    void aResponseWithoutAContextLengthYieldsNothingRatherThanGuessing() {
        assertEquals(0, EmbeddingProviderOllama.parseContextLength("""
                {"model_info":{"general.architecture":"llama"}}"""));
        assertEquals(0, EmbeddingProviderOllama.parseContextLength("{}"));
    }

    /** Discovery must not be confused by a similarly-named key that is not the context length. */
    @Test
    void aSimilarlyNamedKeyIsNotMistakenForTheContextLength() {
        assertEquals(0, EmbeddingProviderOllama.parseContextLength("""
                {"model_info":{"qwen3.embedding_length":1024,"qwen3.feed_forward_length":3072}}"""));
    }

    @Test
    void theDiscoveredLimitIsUsedInsteadOfTheTable() throws IOException {
        startShowServer(REAL_SHOW_RESPONSE, new AtomicInteger());
        EmbeddingProviderOllama provider = new EmbeddingProviderOllama(baseUri(), "a-model-not-in-the-table");

        assertEquals(32_768, provider.maxInputTokens(),
                "the endpoint knows better than the table; the table is the fallback, not the answer");
    }

    /** One round trip, cached -- a limit lookup must not become a per-embedding tax. */
    @Test
    void theLimitIsDiscoveredOnceAndReused() throws IOException {
        AtomicInteger showCalls = new AtomicInteger();
        startShowServer(REAL_SHOW_RESPONSE, showCalls);
        EmbeddingProviderOllama provider = new EmbeddingProviderOllama(baseUri(), "some-model");

        for (int i = 0; i < 5; i++) {
            provider.maxInputTokens();
        }

        assertEquals(1, showCalls.get(), "discovery must be cached per provider instance");
    }

    /**
     * An unreachable or unhelpful endpoint falls back to the table rather than throwing. Not knowing the
     * limit precisely is a reason to be conservative, never a reason to refuse to embed at all.
     */
    @Test
    void anUnavailableEndpointFallsBackToTheTable() throws IOException {
        startShowServer("not json at all", new AtomicInteger());
        EmbeddingProviderOllama provider = new EmbeddingProviderOllama(baseUri(), "qwen3-embedding:0.6b");

        assertEquals(EmbeddingModelLimits.lookup("qwen3-embedding:0.6b"), provider.maxInputTokens());
    }

    @Test
    void anExplicitOverrideBeatsBothDiscoveryAndTheTable() throws IOException {
        AtomicInteger showCalls = new AtomicInteger();
        startShowServer(REAL_SHOW_RESPONSE, showCalls);
        EmbeddingProviderOllama provider = new EmbeddingProviderOllama(baseUri(), "qwen3-embedding:0.6b", 4_096);

        assertEquals(4_096, provider.maxInputTokens());
        assertEquals(0, showCalls.get(), "an explicit answer means there is nothing to ask");
    }

    private void startShowServer(String body, AtomicInteger callCount) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/show", exchange -> {
            callCount.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private URI baseUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }
}
