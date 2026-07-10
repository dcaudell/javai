package dev.xtrafe.javai.vector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingProviderVLlmTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
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

        var provider = new EmbeddingProviderVLlm(
                URI.create("http://localhost:" + server.getAddress().getPort()), "test-model");
        EmbeddingVector result = provider.embed("hello");

        assertArrayEquals(new float[] {0.5f, 0.25f}, result.values(), 1e-6f);
        assertEquals("test-model", result.modelId());
        assertEquals(2, result.dims());
    }

    @Test
    void embedOmitsAuthorizationHeaderWhenNoApiKeyIsSet() throws IOException {
        StringBuilder capturedAuthHeader = new StringBuilder("<not captured>");
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            capturedAuthHeader.setLength(0);
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header != null) {
                capturedAuthHeader.append(header);
            }
            byte[] body = "{\"data\":[{\"embedding\":[0.1]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var provider = new EmbeddingProviderVLlm(
                URI.create("http://localhost:" + server.getAddress().getPort()), "test-model");
        provider.embed("hello");

        assertEquals("", capturedAuthHeader.toString());
    }

    @Test
    void embedSendsTheApiKeyAsABearerTokenWhenSet() throws IOException {
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

        var provider = new EmbeddingProviderVLlm(
                URI.create("http://localhost:" + server.getAddress().getPort()), "vllm-token", "test-model");
        provider.embed("hello");

        assertEquals("Bearer vllm-token", capturedAuthHeader.toString());
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

        var provider = new EmbeddingProviderVLlm(
                URI.create("http://localhost:" + server.getAddress().getPort()), "test-model");

        assertThrows(EmbeddingProviderVLlm.EmbeddingProviderException.class, () -> provider.embed("hello"));
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

        var provider = new EmbeddingProviderVLlm(
                URI.create("http://localhost:" + server.getAddress().getPort()), "test-model");
        EmbeddingVector result = provider.embed("hello");

        assertArrayEquals(new float[] {0.5f, 0.25f}, result.values(), 1e-6f);
        assertEquals(2, requestCount.get(), "must have retried exactly once after the 429");
    }
}
