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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic only -- vLLM's own Docker images are CUDA-first and don't run under Docker Desktop on Apple
 * Silicon, so there's no way to verify this against a real running vLLM instance this pass (see this
 * module's README). Proves {@link CortexVLlm} reaches its configured {@code baseUrl} (always required,
 * unlike OpenAI/Groq which have sensible hosted defaults) via the same OpenAI-compatible wire shape
 * {@link CortexOpenAITest} already proves in detail.
 */
class CortexVLlmTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void completeReachesTheConfiguredSelfHostedEndpoint() throws IOException {
        StringBuilder capturedRequestBody = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            capturedRequestBody.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"self-hosted answer"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";

        Cortex cortex = CortexVLlm.builder().baseUrl(baseUrl).model("meta-llama/Llama-3.2-3B-Instruct").build();
        CompletionResult result = cortex.complete(CompletionRequest.builder().prompt("hello vllm").build());

        assertEquals("self-hosted answer", result.text());
        assertEquals("vllm", result.providerId());
        assertTrue(capturedRequestBody.toString().contains("hello vllm"));
    }

    @Test
    void builderRequiresBaseUrl() {
        try {
            CortexVLlm.builder().model("some-model").build();
            throw new AssertionError("expected IllegalStateException for a missing baseUrl");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("baseUrl"));
        }
    }

    @Test
    void builderRequiresAModel() {
        try {
            CortexVLlm.builder().baseUrl("http://localhost:8000/v1").build();
            throw new AssertionError("expected IllegalStateException for a missing model");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("model"));
        }
    }

    /** Proves a single {@link Cortex} instance is safe under concurrent callers. */
    @Test
    void concurrentCallsAllSucceed() throws IOException, InterruptedException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"concurrent ok"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";

        Cortex cortex = CortexVLlm.builder().baseUrl(baseUrl).model("meta-llama/Llama-3.2-3B-Instruct").build();
        List<Callable<CompletionResult>> calls = java.util.stream.Stream.generate(
                        () -> (Callable<CompletionResult>) () -> cortex.complete(CompletionRequest.builder().prompt("hi").build()))
                .limit(20)
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<CompletionResult>> futures = executor.invokeAll(calls);
            for (Future<CompletionResult> future : futures) {
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
