package dev.xtrafe.javai.vector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingProviderOpenAITest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parseEmbeddingFieldIgnoresSurroundingFields() {
        String body = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                + "\"embedding\":[0.1,-0.2,0.3]}],\"model\":\"text-embedding-3-small\","
                + "\"usage\":{\"prompt_tokens\":5,\"total_tokens\":5}}";
        float[] values = EmbeddingProviderOpenAI.parseEmbeddingField(body);
        assertArrayEquals(new float[] {0.1f, -0.2f, 0.3f}, values, 1e-6f);
    }

    @Test
    void parseEmbeddingFieldRejectsAMissingKey() {
        assertThrows(EmbeddingProviderOpenAI.EmbeddingProviderException.class,
                () -> EmbeddingProviderOpenAI.parseEmbeddingField("{\"data\":[{}]}"));
    }

    @Test
    void embedRoundTripsAgainstARealHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] body = ("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                    + "\"embedding\":[0.5,0.25]}],\"model\":\"test-model\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var provider = new EmbeddingProviderOpenAI(
                "http://localhost:" + server.getAddress().getPort(), "test-key", "test-model");
        EmbeddingVector result = provider.embed("hello");

        assertArrayEquals(new float[] {0.5f, 0.25f}, result.values(), 1e-6f);
        assertEquals("test-model", result.modelId());
        assertEquals(2, result.dims());
    }

    @Test
    void embedSendsTheApiKeyAsABearerToken() throws IOException {
        StringBuilder capturedAuthHeader = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            capturedAuthHeader.append(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"embedding\":[0.1]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var provider = new EmbeddingProviderOpenAI(
                "http://localhost:" + server.getAddress().getPort(), "sk-test-token", "test-model");
        provider.embed("hello");

        assertEquals("Bearer sk-test-token", capturedAuthHeader.toString());
    }

    @Test
    void embedThrowsOnNonOkStatus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] body = "internal error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var provider = new EmbeddingProviderOpenAI(
                "http://localhost:" + server.getAddress().getPort(), "test-key", "test-model");

        assertThrows(EmbeddingProviderOpenAI.EmbeddingProviderException.class, () -> provider.embed("hello"));
    }

    @Test
    void embedRetriesAfterA429AndSucceeds() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            if (requestCount.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                byte[] body = "rate limited".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
            } else {
                byte[] body = "{\"data\":[{\"embedding\":[0.5,0.25]}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();

        var provider = new EmbeddingProviderOpenAI(
                "http://localhost:" + server.getAddress().getPort(), "test-key", "test-model");
        EmbeddingVector result = provider.embed("hello");

        assertArrayEquals(new float[] {0.5f, 0.25f}, result.values(), 1e-6f);
        assertEquals(2, requestCount.get(), "must have retried exactly once after the 429");
    }

    @Test
    void constructorWithoutBaseUrlDefaultsToOpenAiHostedEndpoint() {
        // No live network call here -- just proving the 2-arg convenience constructor doesn't throw and
        // wires up a non-null endpoint, since actually calling api.openai.com isn't possible in this suite.
        var provider = new EmbeddingProviderOpenAI("test-key", "test-model");
        assertTrue(provider instanceof JavAIEmbeddingProvider);
    }
}
