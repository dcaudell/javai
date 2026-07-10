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

/**
 * Hermetic only -- no Replicate API token was available at implementation time, and (per
 * {@link EmbeddingProviderReplicate}'s own javadoc) the exact schema of any specific embedding model hosted
 * on Replicate could not be confirmed either. Proves the create-then-poll-until-terminal contract and both
 * tolerated output shapes against a fake HTTP server.
 */
class EmbeddingProviderReplicateTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void extractOutputEmbeddingAcceptsAFlatArray() {
        String body = "{\"status\":\"succeeded\",\"output\":[0.1,-0.2,0.3]}";
        assertArrayEquals(new float[] {0.1f, -0.2f, 0.3f}, EmbeddingProviderReplicate.extractOutputEmbedding(body), 1e-6f);
    }

    @Test
    void extractOutputEmbeddingAcceptsANestedOneRowBatch() {
        String body = "{\"status\":\"succeeded\",\"output\":[[0.1,-0.2,0.3]]}";
        assertArrayEquals(new float[] {0.1f, -0.2f, 0.3f}, EmbeddingProviderReplicate.extractOutputEmbedding(body), 1e-6f);
    }

    @Test
    void embedReturnsImmediatelyWhenThePredictionAlreadySucceeded() throws IOException {
        StringBuilder capturedRequestBody = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models/test/model/predictions", exchange -> {
            capturedRequestBody.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"id\":\"pred-1\",\"status\":\"succeeded\",\"output\":[0.5,0.25],"
                    + "\"urls\":{\"get\":\"http://unused/should-not-be-polled\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        var provider = EmbeddingProviderReplicate.builder().baseUrl(baseUrl).apiToken("test-token")
                .model("test/model").build();
        EmbeddingVector result = provider.embed("hello");

        assertArrayEquals(new float[] {0.5f, 0.25f}, result.values(), 1e-6f);
        assertEquals("test/model", result.modelId());
        assertTrue(capturedRequestBody.toString().contains("\"text\":\"hello\""),
                "default inputFieldName must be \"text\"");
    }

    @Test
    void embedUsesTheConfiguredInputFieldName() throws IOException {
        StringBuilder capturedRequestBody = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models/test/model/predictions", exchange -> {
            capturedRequestBody.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"id\":\"pred-x\",\"status\":\"succeeded\",\"output\":[0.1]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        var provider = EmbeddingProviderReplicate.builder().baseUrl(baseUrl).apiToken("test-token")
                .model("test/model").inputFieldName("inputs").build();
        provider.embed("hello");

        assertTrue(capturedRequestBody.toString().contains("\"inputs\":\"hello\""));
    }

    @Test
    void embedPollsUntilThePredictionReachesATerminalState() throws IOException {
        AtomicInteger pollCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models/test/model/predictions", exchange -> {
            byte[] body = ("{\"id\":\"pred-2\",\"status\":\"starting\","
                    + "\"urls\":{\"get\":\"http://localhost:" + server.getAddress().getPort()
                    + "/v1/predictions/pred-2\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/predictions/pred-2", exchange -> {
            boolean done = pollCount.incrementAndGet() >= 2;
            String status = done ? "succeeded" : "processing";
            String output = done ? "\"output\":[0.9]," : "";
            byte[] body = ("{\"id\":\"pred-2\",\"status\":\"" + status + "\"," + output
                    + "\"urls\":{\"get\":\"unused\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        var provider = EmbeddingProviderReplicate.builder().baseUrl(baseUrl).apiToken("test-token")
                .model("test/model").build();
        EmbeddingVector result = provider.embed("slow input");

        assertArrayEquals(new float[] {0.9f}, result.values(), 1e-6f);
        assertTrue(pollCount.get() >= 2, "must have polled at least until the terminal status was reached");
    }

    @Test
    void embedThrowsWithTheErrorMessageWhenThePredictionFails() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models/test/model/predictions", exchange -> {
            byte[] body = "{\"id\":\"pred-3\",\"status\":\"failed\",\"error\":\"model overloaded\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        var provider = EmbeddingProviderReplicate.builder().baseUrl(baseUrl).apiToken("test-token")
                .model("test/model").build();
        EmbeddingProviderReplicate.EmbeddingProviderException exception = assertThrows(
                EmbeddingProviderReplicate.EmbeddingProviderException.class, () -> provider.embed("hi"));
        assertTrue(exception.getMessage().contains("model overloaded"));
    }

    @Test
    void embedRetriesAfterA429AndSucceeds() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models/test/model/predictions", exchange -> {
            if (requestCount.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                byte[] body = "rate limited".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
            } else {
                byte[] body = "{\"id\":\"pred-4\",\"status\":\"succeeded\",\"output\":[0.5,0.25]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        var provider = EmbeddingProviderReplicate.builder().baseUrl(baseUrl).apiToken("test-token")
                .model("test/model").build();
        EmbeddingVector result = provider.embed("hello");

        assertArrayEquals(new float[] {0.5f, 0.25f}, result.values(), 1e-6f);
        assertEquals(2, requestCount.get(), "must have retried exactly once after the 429");
    }

    @Test
    void builderDefaultsModelAndInputFieldName() {
        var provider = EmbeddingProviderReplicate.builder().apiToken("t").build();
        assertTrue(provider instanceof JavAIEmbeddingProvider);
    }
}
