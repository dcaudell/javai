package dev.xtrafe.javai.completion;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic -- proves {@link CortexMistral} reaches the configured {@code baseUrl} and reports itself as
 * {@code "mistral"}. The wire-shape proof is {@link CortexOpenAITest}'s job, shared underneath via
 * {@link CortexOpenAiCompatibleSupport}; {@code CortexMistralLiveTest} covers the real endpoint.
 */
class CortexMistralTest {

    private HttpServer server;
    private String capturedPath;
    private String capturedAuthorization;
    private String capturedRequestBody;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void completeReachesTheConfiguredBaseUrlNotMistralsRealEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            capturedPath = exchange.getRequestURI().getPath();
            capturedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = """
                    {"id":"cmpl-1","object":"chat.completion","created":1,"model":"mistral-small-latest",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"bonjour"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        Cortex cortex = CortexMistral.builder().baseUrl(baseUrl).apiKey("test-key").model("mistral-small-latest").build();
        CompletionResult result = cortex.complete(CompletionRequest.builder().prompt("say hello").build());

        assertEquals("bonjour", result.text());
        assertEquals("mistral", result.providerId());
        assertEquals("mistral-small-latest", result.modelId());
        assertEquals("/chat/completions", capturedPath);
        assertEquals("Bearer test-key", capturedAuthorization);
        assertTrue(capturedRequestBody.contains("say hello"));
    }

    @Test
    void builderRequiresAModel() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CortexMistral.builder().apiKey("k").build());
        assertTrue(e.getMessage().contains("model"));
    }

    @Test
    void contextWindowComesFromTheTableUnlessOverridden() {
        assertEquals(262_144, CortexMistral.builder().model("mistral-small-latest").build().contextWindowTokens());
        assertEquals(1_000, CortexMistral.builder().model("mistral-small-latest").contextWindowTokens(1_000)
                .build().contextWindowTokens());
    }

    /** Proves a single {@link Cortex} instance is safe under concurrent callers. */
    @Test
    void concurrentCallsAllSucceed() throws IOException, InterruptedException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = """
                    {"id":"cmpl-2","object":"chat.completion","created":1,"model":"mistral-small-latest",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"concurrent ok"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        Cortex cortex = CortexMistral.builder().baseUrl(baseUrl).apiKey("test-key").model("mistral-small-latest").build();
        List<Callable<CompletionResult>> calls = Stream.generate(
                        () -> (Callable<CompletionResult>) () -> cortex.complete(CompletionRequest.builder().prompt("hi").build()))
                .limit(20)
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (Future<CompletionResult> future : executor.invokeAll(calls)) {
                try {
                    assertEquals("concurrent ok", future.get().text());
                } catch (Exception e) {
                    throw new AssertionError("a concurrent complete() call failed", e);
                }
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
