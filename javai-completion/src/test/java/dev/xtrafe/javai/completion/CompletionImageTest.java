package dev.xtrafe.javai.completion;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Images on a {@link CompletionRequest} (OMI-639): the value, and what each Cortex puts on the wire. */
class CompletionImageTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final String PNG_BASE64 = Base64.getEncoder().encodeToString(PNG);

    private HttpServer server;
    private volatile String capturedRequestBody;

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
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    // ---- the value

    @Test
    void anImageIsBytesOrAUrlNeverBothNorNeither() {
        assertThrows(IllegalArgumentException.class, () -> new CompletionImage(PNG, "image/png", URI.create("https://x/y.png")));
        assertThrows(IllegalArgumentException.class, () -> new CompletionImage(null, "image/png", null));
    }

    @Test
    void anImageNeedsAnImageMediaType() {
        assertThrows(IllegalArgumentException.class, () -> CompletionImage.of(PNG, null));
        assertThrows(IllegalArgumentException.class, () -> CompletionImage.of(PNG, "text/plain"));
    }

    @Test
    void anImagesBytesAreCopiedInAndOut() {
        byte[] source = PNG.clone();
        CompletionImage image = CompletionImage.of(source, "image/png");
        source[0] = 0;
        assertEquals(PNG[0], image.data()[0], "a caller's later write must not reach the image");
        image.data()[1] = 0;
        assertEquals(PNG[1], image.data()[1], "a reader's write must not reach the image");
    }

    @Test
    void imagesAreEqualByContent() {
        assertEquals(CompletionImage.of(PNG.clone(), "image/png"), CompletionImage.of(PNG.clone(), "image/png"));
        assertEquals(CompletionImage.of(PNG.clone(), "image/png").hashCode(),
                CompletionImage.of(PNG.clone(), "image/png").hashCode());
        assertNotEquals(CompletionImage.of(PNG, "image/png"), CompletionImage.of(PNG, "image/jpeg"));
    }

    // ---- the request

    @Test
    void theBuilderKeepsImagesInOrder() {
        CompletionImage first = CompletionImage.of(PNG, "image/png");
        CompletionImage second = CompletionImage.of(URI.create("https://example.test/b.jpg"), "image/jpeg");
        CompletionRequest request = CompletionRequest.builder().prompt("look").image(first).image(second).build();
        assertEquals(List.of(first, second), request.images());
    }

    @Test
    void imagesAreNotTextSoRenderIgnoresThem() {
        CompletionRequest request = CompletionRequest.builder().prompt("look").image(PNG, "image/png").build();
        assertEquals("look", request.render());
        assertFalse(request.render(1000).contains(PNG_BASE64));
    }

    @Test
    void aRequestWithoutImagesHasAnEmptyListAsBefore() {
        assertTrue(CompletionRequest.builder().prompt("x").build().images().isEmpty());
        assertTrue(new CompletionRequest(List.of("x"), null, null, null, null, null, null).images().isEmpty());
    }

    // ---- on the wire

    @Test
    void anthropicSendsBytesAsABase64ImageBlockBesideTheText() throws IOException {
        String baseUrl = startFakeServer("""
                {"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"a cat"}],
                 "model":"claude-sonnet-5","stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":2}}
                """);
        Cortex cortex = CortexAnthropic.builder().baseUrl(baseUrl).apiKey("k").model("claude-sonnet-5").build();

        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("What is in this image?").image(PNG, "image/png").build());

        assertEquals("a cat", result.text());
        assertTrue(capturedRequestBody.contains("\"type\":\"image\""), capturedRequestBody);
        assertTrue(capturedRequestBody.contains(PNG_BASE64), "the image's bytes, base64: " + capturedRequestBody);
        assertTrue(capturedRequestBody.contains("image/png"), capturedRequestBody);
        assertTrue(capturedRequestBody.contains("What is in this image?"), capturedRequestBody);
    }

    @Test
    void openAiSendsBytesAsAnImageUrlPart() throws IOException {
        String baseUrl = startFakeServer("""
                {"id":"c1","object":"chat.completion","created":1,"model":"gpt-4.1",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"a cat"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                """);
        Cortex cortex = CortexOpenAI.builder().baseUrl(baseUrl).apiKey("k").model("gpt-4.1").build();

        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("What is in this image?").image(PNG, "image/png").build());

        assertEquals("a cat", result.text());
        assertTrue(capturedRequestBody.contains("image_url"), capturedRequestBody);
        assertTrue(capturedRequestBody.contains(PNG_BASE64), capturedRequestBody);
    }

    @Test
    void openAiSendsAUrlAsAUrl() throws IOException {
        String baseUrl = startFakeServer("""
                {"id":"c1","object":"chat.completion","created":1,"model":"gpt-4.1",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"a dog"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                """);
        Cortex cortex = CortexOpenAI.builder().baseUrl(baseUrl).apiKey("k").model("gpt-4.1").build();

        cortex.complete(CompletionRequest.builder().prompt("And this?")
                .image(URI.create("https://example.test/dog.jpg"), "image/jpeg").build());

        assertTrue(capturedRequestBody.contains("https://example.test/dog.jpg"), capturedRequestBody);
    }

    @Test
    void ollamaSendsBytesBase64OnTheUserMessage() throws IOException {
        String baseUrl = startFakeServer("""
                {"model":"qwen2.5vl","created_at":"2026-10-12T00:00:00Z",
                 "message":{"role":"assistant","content":"a cat"},"done":true}
                """);
        Cortex cortex = CortexOllama.builder().baseUrl(baseUrl).model("qwen2.5vl").build();

        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("What is in this image?").image(PNG, "image/png").build());

        assertEquals("a cat", result.text());
        assertTrue(capturedRequestBody.contains("\"images\""), capturedRequestBody);
        assertTrue(capturedRequestBody.contains(PNG_BASE64), capturedRequestBody);
    }

    @Test
    void ollamaRefusesAUrlRatherThanDroppingIt() {
        Cortex cortex = CortexOllama.builder().baseUrl("http://localhost:1").model("qwen2.5vl").build();
        assertThrows(IllegalArgumentException.class, () -> cortex.complete(CompletionRequest.builder()
                .prompt("x").image(URI.create("https://example.test/a.png"), "image/png").build()));
    }

    @Test
    void replicateRefusesImagesRatherThanDroppingThem() {
        Cortex cortex = CortexReplicate.builder().baseUrl("http://localhost:1").apiToken("t").model("o/m").build();
        assertThrows(UnsupportedOperationException.class, () -> cortex.complete(CompletionRequest.builder()
                .prompt("x").image(PNG, "image/png").build()));
    }
}
