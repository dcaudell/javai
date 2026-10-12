package dev.xtrafe.javai.completion;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Token usage on every {@link CompletionResult}, as each provider reports it (OMI-641). */
class CompletionUsageTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startFakeServer(String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void anthropicReportsInputAndOutputTokens() throws IOException {
        String baseUrl = startFakeServer("""
                {"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"hi"}],
                 "model":"claude-sonnet-5","stop_reason":"end_turn","usage":{"input_tokens":17,"output_tokens":4}}
                """);
        CompletionResult result = CortexAnthropic.builder().baseUrl(baseUrl).apiKey("k").model("claude-sonnet-5")
                .build().complete(CompletionRequest.builder().prompt("hi").build());
        assertEquals(17, result.inputTokens());
        assertEquals(4, result.outputTokens());
    }

    @Test
    void openAiReportsPromptAndCompletionTokens() throws IOException {
        String baseUrl = startFakeServer("""
                {"id":"c1","object":"chat.completion","created":1,"model":"gpt-4.1",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":23,"completion_tokens":6,"total_tokens":29}}
                """);
        CompletionResult result = CortexOpenAI.builder().baseUrl(baseUrl).apiKey("k").model("gpt-4.1").build()
                .complete(CompletionRequest.builder().prompt("hi").build());
        assertEquals(23, result.inputTokens());
        assertEquals(6, result.outputTokens());
    }

    @Test
    void ollamaReportsItsEvalCounts() throws IOException {
        String baseUrl = startFakeServer("""
                {"model":"qwen3:8b","created_at":"2026-10-12T00:00:00Z","message":{"role":"assistant","content":"hi"},
                 "done":true,"prompt_eval_count":31,"eval_count":9}
                """);
        CompletionResult result = CortexOllama.builder().baseUrl(baseUrl).model("qwen3:8b").build()
                .complete(CompletionRequest.builder().prompt("hi").build());
        assertEquals(31, result.inputTokens());
        assertEquals(9, result.outputTokens());
    }

    @Test
    void aResultBuiltWithoutUsageReportsNone() {
        CompletionResult result = new CompletionResult("x", "p", "m", Instant.now());
        assertNull(result.inputTokens());
        assertNull(result.outputTokens());
    }
}
