package dev.xtrafe.javai.vector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaEmbeddingProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parseEmbeddingsFieldIgnoresSurroundingFields() {
        String body = "{\"model\":\"qwen3-embedding:0.6b\",\"embeddings\":[[0.1,-0.2,0.3]],"
                + "\"total_duration\":14143917,\"load_duration\":1019500}";
        float[] values = OllamaEmbeddingProvider.parseEmbeddingsField(body);
        assertArrayEquals(new float[] {0.1f, -0.2f, 0.3f}, values, 1e-6f);
    }

    @Test
    void parseEmbeddingsFieldRejectsAMissingKey() {
        assertThrows(OllamaEmbeddingProvider.EmbeddingProviderException.class,
                () -> OllamaEmbeddingProvider.parseEmbeddingsField("{\"model\":\"x\"}"));
    }

    @Test
    void embedRoundTripsAgainstARealHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/embed", exchange -> {
            byte[] body = "{\"model\":\"test-model\",\"embeddings\":[[0.5,0.25]]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var provider = new OllamaEmbeddingProvider(
                URI.create("http://localhost:" + server.getAddress().getPort()), "test-model");
        EmbeddingVector result = provider.embed("hello");

        assertArrayEquals(new float[] {0.5f, 0.25f}, result.values(), 1e-6f);
        assertEquals("test-model", result.modelId());
        assertEquals(2, result.dims());
    }

    /**
     * Confirmed empirically against a real Ollama instance: {@code /api/embed} returns
     * {@code "embeddings": []} (zero rows, not a placeholder vector) for a genuinely empty input string --
     * breaking {@code CollectionVectorSupport.computeCentroid()}'s assumption that {@code embed("")}
     * always yields a real, correctly-dimensioned vector for an empty collection. Substituting a single
     * space keeps that contract true against real Ollama too.
     */
    @Test
    void embedSubstitutesASingleSpaceForEmptyInput() throws IOException {
        StringBuilder capturedRequestBody = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/embed", exchange -> {
            capturedRequestBody.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"model\":\"test-model\",\"embeddings\":[[0.1,0.2]]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var provider = new OllamaEmbeddingProvider(
                URI.create("http://localhost:" + server.getAddress().getPort()), "test-model");
        provider.embed("");

        assertEquals("{\"model\":\"test-model\",\"input\":\" \"}", capturedRequestBody.toString(),
                "an empty input string must be substituted with a single space, never sent through as-is");
    }

    @Test
    void embedThrowsOnNonOkStatus() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/embed", exchange -> {
            byte[] body = "internal error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var provider = new OllamaEmbeddingProvider(
                URI.create("http://localhost:" + server.getAddress().getPort()), "test-model");

        assertThrows(OllamaEmbeddingProvider.EmbeddingProviderException.class, () -> provider.embed("hello"));
    }
}
