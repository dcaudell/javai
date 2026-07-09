package dev.xtrafe.javai.completion;

import com.sun.net.httpserver.HttpServer;
import dev.xtrafe.javai.runtime.PromptContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic only -- no OpenAI API key was available at implementation time (see this module's README).
 * Proves request/response mapping against a fake HTTP server standing in for OpenAI's own endpoint, not
 * that a real completion is semantically correct.
 */
class OpenAICortexTest {

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
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void completeSendsPromptAndParsesTextBack() throws IOException {
        String baseUrl = startFakeServer("""
                {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-4.1",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Hello there!"},"finish_reason":"stop"}]}
                """);

        Cortex cortex = OpenAICortex.builder().baseUrl(baseUrl).apiKey("test-key").model("gpt-4.1").build();
        CompletionResult result = cortex.complete(CompletionRequest.builder().prompt("Say hi").build());

        assertEquals("Hello there!", result.text());
        assertEquals("openai", result.providerId());
        assertEquals("gpt-4.1", result.modelId());
        assertTrue(capturedRequestBody.contains("\"Say hi\""),
                "the prompt text must be sent through in the request body");
    }

    @Test
    void promptContextIsAppendedToThePrompt() throws IOException {
        String baseUrl = startFakeServer("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                """);

        Cortex cortex = OpenAICortex.builder().baseUrl(baseUrl).apiKey("test-key").model("gpt-4.1").build();
        cortex.complete(CompletionRequest.builder()
                .prompt("Summarize this:")
                .context(PromptContext.of("the informing material"))
                .build());

        assertTrue(capturedRequestBody.contains("the informing material"),
                "PromptContext's text must be included in the request sent to the provider");
    }

    @Test
    void reasoningEffortProviderOptionIsSentThrough() throws IOException {
        String baseUrl = startFakeServer("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                """);

        Cortex cortex = OpenAICortex.builder().baseUrl(baseUrl).apiKey("test-key").model("gpt-4.1").build();
        cortex.complete(CompletionRequest.builder()
                .prompt("hi")
                .providerOption("reasoning_effort", "high")
                .build());

        assertTrue(capturedRequestBody.contains("\"reasoning_effort\":\"high\""),
                "the reasoning_effort providerOption must be mapped onto OpenAiChatOptions and sent through");
    }

    @Test
    void builderRequiresAModel() {
        try {
            OpenAICortex.builder().apiKey("k").build();
            throw new AssertionError("expected IllegalStateException for a missing model");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("model"));
        }
    }
}
