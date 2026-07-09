package dev.xtrafe.javai.completion;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic only -- no Groq API key was available at implementation time. Groq is OpenAI-wire-compatible by
 * its own design, so this mostly just proves {@link GroqCortex} really does reach the configured
 * {@code baseUrl} (i.e. is genuinely repointable, not hardcoded to OpenAI's own endpoint) -- the wire-shape
 * proof itself is {@link OpenAICortexTest}'s job, shared underneath via {@link OpenAiCompatibleCortexSupport}.
 */
class GroqCortexTest {

    private HttpServer server;
    private String capturedRequestBody;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void completeReachesTheConfiguredBaseUrlNotGroqsRealEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = """
                    {"choices":[{"index":0,"message":{"role":"assistant","content":"fast answer"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        Cortex cortex = GroqCortex.builder().baseUrl(baseUrl).apiKey("test-key").model("llama-3.3-70b-versatile").build();
        CompletionResult result = cortex.complete(CompletionRequest.builder().prompt("quick question").build());

        assertEquals("fast answer", result.text());
        assertEquals("groq", result.providerId());
        assertEquals("llama-3.3-70b-versatile", result.modelId());
        assertTrue(capturedRequestBody.contains("quick question"));
    }

    @Test
    void builderRequiresAModel() {
        try {
            GroqCortex.builder().apiKey("k").build();
            throw new AssertionError("expected IllegalStateException for a missing model");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("model"));
        }
    }
}
