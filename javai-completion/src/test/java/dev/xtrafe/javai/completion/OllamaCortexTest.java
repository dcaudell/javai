package dev.xtrafe.javai.completion;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests against a fake HTTP server -- request/option-mapping only. The real, Testcontainers-backed
 * proof that a genuine {@code qwen3:8b} completion (and the {@code enable_thinking} tuning parameter)
 * actually works is {@code OllamaCortexRealContainerTest}, this module's one real-backend test (mirroring
 * this project's established "no meaningful way to fake a real backend" testing philosophy).
 */
class OllamaCortexTest {

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
                {"model":"qwen3:8b","created_at":"2026-01-01T00:00:00Z",
                 "message":{"role":"assistant","content":"Hello from Ollama!"},"done":true}
                """);

        Cortex cortex = OllamaCortex.builder().endpoint(URI.create(baseUrl)).model("qwen3:8b").build();
        CompletionResult result = cortex.complete(CompletionRequest.builder().prompt("Say hi").build());

        assertEquals("Hello from Ollama!", result.text());
        assertEquals("ollama", result.providerId());
        assertEquals("qwen3:8b", result.modelId());
        assertTrue(capturedRequestBody.contains("Say hi"));
    }

    @Test
    void enableThinkingProviderOptionSetsTheThinkField() throws IOException {
        startFakeServer("""
                {"message":{"role":"assistant","content":"ok"},"done":true}
                """);

        Cortex cortex = OllamaCortex.builder().baseUrl("http://localhost:" + server.getAddress().getPort())
                .model("qwen3:8b").build();
        cortex.complete(CompletionRequest.builder()
                .prompt("think hard")
                .providerOption("enable_thinking", true)
                .build());

        assertTrue(capturedRequestBody.contains("\"think\":true"),
                "the enable_thinking providerOption must set Ollama's own \"think\" request field");
    }

    @Test
    void thinkFieldIsOmittedWhenNotRequested() throws IOException {
        startFakeServer("""
                {"message":{"role":"assistant","content":"ok"},"done":true}
                """);

        Cortex cortex = OllamaCortex.builder().baseUrl("http://localhost:" + server.getAddress().getPort())
                .model("qwen3:8b").build();
        cortex.complete(CompletionRequest.builder().prompt("plain question").build());

        assertFalse(capturedRequestBody.contains("\"think\""),
                "think must not be sent at all unless enable_thinking was explicitly requested");
    }

    @Test
    void otherProviderOptionsPassThroughAsOllamaRequestOptions() throws IOException {
        startFakeServer("""
                {"message":{"role":"assistant","content":"ok"},"done":true}
                """);

        Cortex cortex = OllamaCortex.builder().baseUrl("http://localhost:" + server.getAddress().getPort())
                .model("qwen3:8b").build();
        cortex.complete(CompletionRequest.builder()
                .prompt("hi")
                .providerOption("num_ctx", 8192)
                .build());

        assertTrue(capturedRequestBody.contains("\"num_ctx\":8192"),
                "arbitrary Ollama-specific providerOptions must pass through into the request's options map");
    }

    @Test
    void builderRequiresAModel() {
        try {
            OllamaCortex.builder().build();
            throw new AssertionError("expected IllegalStateException for a missing model");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("model"));
        }
    }
}
