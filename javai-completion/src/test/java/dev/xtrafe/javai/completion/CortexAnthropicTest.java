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
 * Hermetic only -- no Anthropic API key was available at implementation time (see this module's README).
 * Proves request/response mapping against a fake HTTP server standing in for Anthropic's Messages API.
 */
class CortexAnthropicTest {

    private HttpServer server;
    private String capturedRequestBody;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startFakeServer(String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void completeSendsPromptAndParsesTextBack() throws IOException {
        String baseUrl = startFakeServer("""
                {"id":"msg_1","type":"message","role":"assistant",
                 "content":[{"type":"text","text":"Hello from Claude!"}],
                 "model":"claude-sonnet-5","stop_reason":"end_turn"}
                """);

        Cortex cortex = CortexAnthropic.builder().baseUrl(baseUrl).apiKey("test-key").model("claude-sonnet-5").build();
        CompletionResult result = cortex.complete(CompletionRequest.builder().prompt("Say hi").build());

        assertEquals("Hello from Claude!", result.text());
        assertEquals("anthropic", result.providerId());
        assertEquals("claude-sonnet-5", result.modelId());
        assertTrue(capturedRequestBody.contains("Say hi"));
    }

    @Test
    void everyRequestCarriesAMaxTokensValueSinceAnthropicRequiresOne() throws IOException {
        startFakeServer("""
                {"content":[{"type":"text","text":"ok"}]}
                """);

        Cortex cortex = CortexAnthropic.builder().baseUrl("http://localhost:" + server.getAddress().getPort())
                .apiKey("test-key").model("claude-sonnet-5").build();
        cortex.complete(CompletionRequest.builder().prompt("hi").build()); // no maxTokens set explicitly

        assertTrue(capturedRequestBody.contains("\"max_tokens\":"),
                "Anthropic's API requires max_tokens on every request -- must default, never be omitted");
    }

    @Test
    void thinkingBudgetProviderOptionEnablesExtendedThinking() throws IOException {
        startFakeServer("""
                {"content":[{"type":"text","text":"ok"}]}
                """);

        Cortex cortex = CortexAnthropic.builder().baseUrl("http://localhost:" + server.getAddress().getPort())
                .apiKey("test-key").model("claude-sonnet-5").build();
        cortex.complete(CompletionRequest.builder()
                .prompt("hard problem")
                .providerOption("thinking_budget_tokens", 4000)
                .build());

        assertTrue(capturedRequestBody.contains("\"thinking\""),
                "the thinking_budget_tokens providerOption must enable Anthropic's extended-thinking config");
        assertTrue(capturedRequestBody.contains("4000"));
    }

    @Test
    void builderRequiresAModel() {
        try {
            CortexAnthropic.builder().apiKey("k").build();
            throw new AssertionError("expected IllegalStateException for a missing model");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("model"));
        }
    }

    /** Proves a single {@link Cortex} instance is safe under concurrent callers. */
    @Test
    void concurrentCallsAllSucceed() throws IOException, InterruptedException {
        String baseUrl = startFakeServer("""
                {"content":[{"type":"text","text":"concurrent ok"}]}
                """);

        Cortex cortex = CortexAnthropic.builder().baseUrl(baseUrl).apiKey("test-key").model("claude-sonnet-5").build();
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
