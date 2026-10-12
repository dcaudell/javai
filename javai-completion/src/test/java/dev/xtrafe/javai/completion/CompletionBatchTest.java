package dev.xtrafe.javai.completion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Batch completions through Anthropic's and OpenAI's batch APIs (OMI-641), against a fake server. */
class CompletionBatchTest {

    record Verdict(boolean ok) {
    }

    private HttpServer server;
    private String base;
    /** The last body received per "METHOD path". */
    private final Map<String, String> received = new ConcurrentHashMap<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A fake server answering each "METHOD path" from {@code routes}; the function sees the base URL. */
    private void startFakeServer(Map<String, Function<String, String>> routes) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            received.put(key, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Function<String, String> route = routes.get(key);
            byte[] body = (route == null ? "{\"error\":\"no route " + key + "\"}" : route.apply(base))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(route == null ? 404 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    private static CompletionRequest richRequest() {
        return CompletionRequest.builder().prompt("Read %%what%%").promptParam("what", "this page")
                .image(new byte[] {1, 2, 3}, "image/png")
                .image(URI.create("https://example.test/p2.png"), "image/png")
                .temperature(0.2).maxTokens(300).responseType(Verdict.class).build();
    }

    // ---- Anthropic

    @Test
    void anthropicBatchParamsAreTheMessagesBodyTheCortexSends() throws IOException {
        startFakeServer(Map.of("POST /v1/messages", b -> """
                {"id":"m","type":"message","role":"assistant","content":[{"type":"text","text":"{}"}],
                 "model":"claude-x","stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""));
        CortexAnthropic cortex = CortexAnthropic.builder().baseUrl(base).apiKey("k").model("claude-x").build();
        for (CompletionRequest request : List.of(richRequest(),
                CompletionRequest.builder().prompt("plain").providerOption("thinking_budget_tokens", 2000).build())) {
            cortex.complete(request);
            JsonElement sent = JsonParser.parseString(received.get("POST /v1/messages"));
            assertEquals(sent, AnthropicBatches.params(request, "claude-x", cortex.contextWindowTokens()),
                    "a batch item must be sent exactly as complete() sends it");
        }
    }

    @Test
    void anthropicBatchSubmitsPollsAndReadsResultsInSubmittedOrder() throws IOException {
        startFakeServer(Map.of(
                "POST /v1/messages/batches", b -> """
                        {"id":"msgbatch_1","processing_status":"in_progress",
                         "request_counts":{"processing":3,"succeeded":0,"errored":0,"canceled":0,"expired":0}}""",
                "GET /v1/messages/batches/msgbatch_1", b -> """
                        {"id":"msgbatch_1","processing_status":"ended","results_url":"%s/results/msgbatch_1",
                         "request_counts":{"processing":0,"succeeded":2,"errored":1,"canceled":0,"expired":0}}"""
                        .formatted(b),
                "GET /results/msgbatch_1", b -> String.join("\n",
                        "{\"custom_id\":\"2\",\"result\":{\"type\":\"succeeded\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"two\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}}}",
                        "{\"custom_id\":\"0\",\"result\":{\"type\":\"succeeded\",\"message\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"hm\"},{\"type\":\"text\",\"text\":\"zero\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}}}",
                        "{\"custom_id\":\"1\",\"result\":{\"type\":\"errored\",\"error\":{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"too long\"}}}}")));
        Cortex cortex = CortexAnthropic.builder().baseUrl(base).apiKey("k").model("claude-x").build();

        CompletionBatch batch = cortex.submitBatch(List.of(
                CompletionRequest.builder().prompt("a").build(),
                CompletionRequest.builder().prompt("b").build(),
                CompletionRequest.builder().prompt("c").build()));

        assertEquals("msgbatch_1", batch.id());
        assertEquals(CompletionBatch.Status.IN_PROGRESS, batch.status());
        assertThrows(IllegalStateException.class, batch::results, "no results before the batch ends");
        JsonObject submitted = JsonParser.parseString(received.get("POST /v1/messages/batches")).getAsJsonObject();
        assertEquals(3, submitted.getAsJsonArray("requests").size());
        assertEquals("1", submitted.getAsJsonArray("requests").get(1).getAsJsonObject().get("custom_id").getAsString());

        assertEquals(CompletionBatch.Status.ENDED, batch.refresh());
        List<CompletionBatch.Item> items = batch.results();
        assertEquals(3, items.size());
        assertEquals("zero", items.get(0).result().text(), "text blocks only, thinking left out");
        assertEquals(10, items.get(0).result().inputTokens());
        assertFalse(items.get(1).succeeded());
        assertEquals("too long", items.get(1).error());
        assertEquals("two", items.get(2).result().text());
        assertEquals(3, items.get(2).result().outputTokens());
    }

    @Test
    void anAnthropicBatchIsFoundAgainById() throws IOException {
        startFakeServer(Map.of("GET /v1/messages/batches/msgbatch_9", b -> """
                {"id":"msgbatch_9","processing_status":"canceling",
                 "request_counts":{"processing":1,"succeeded":0,"errored":0,"canceled":0,"expired":0}}"""));
        CompletionBatch batch = CortexAnthropic.builder().baseUrl(base).apiKey("k").model("claude-x").build()
                .batch("msgbatch_9");
        assertEquals(CompletionBatch.Status.CANCELING, batch.status());
    }

    // ---- OpenAI

    @Test
    void openAiBatchBodiesAreTheChatCompletionsBodyTheCortexSends() throws IOException {
        startFakeServer(Map.of("POST /chat/completions", b -> """
                {"id":"c","object":"chat.completion","created":1,"model":"gpt-x",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"{}"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""));
        CortexOpenAI cortex = CortexOpenAI.builder().baseUrl(base).apiKey("k").model("gpt-x").build();
        for (CompletionRequest request : List.of(richRequest(),
                CompletionRequest.builder().prompt("plain").providerOption("reasoning_effort", "low").build())) {
            cortex.complete(request);
            JsonElement sent = JsonParser.parseString(received.get("POST /chat/completions"));
            assertEquals(sent, OpenAiBatches.body(request, "gpt-x", cortex.contextWindowTokens()),
                    "a batch line's body must be sent exactly as complete() sends it");
        }
    }

    @Test
    void openAiBatchUploadsJsonlBatchesAndReadsOutputAndErrorFiles() throws IOException {
        startFakeServer(Map.of(
                "POST /files", b -> "{\"id\":\"file-in\",\"object\":\"file\"}",
                "POST /batches", b -> "{\"id\":\"batch_1\",\"status\":\"validating\"}",
                "GET /batches/batch_1", b -> """
                        {"id":"batch_1","status":"completed","output_file_id":"file-out","error_file_id":"file-err",
                         "request_counts":{"total":3,"completed":2,"failed":1}}""",
                "GET /files/file-out/content", b -> String.join("\n",
                        "{\"custom_id\":\"1\",\"response\":{\"status_code\":200,\"body\":{\"choices\":[{\"message\":{\"content\":\"one\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}},\"error\":null}",
                        "{\"custom_id\":\"0\",\"response\":{\"status_code\":200,\"body\":{\"choices\":[{\"message\":{\"content\":\"zero\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}},\"error\":null}"),
                "GET /files/file-err/content", b ->
                        "{\"custom_id\":\"2\",\"response\":null,\"error\":{\"code\":\"bad\",\"message\":\"rejected\"}}"));
        Cortex cortex = CortexOpenAI.builder().baseUrl(base).apiKey("k").model("gpt-x").build();

        CompletionBatch batch = cortex.submitBatch(List.of(
                CompletionRequest.builder().prompt("a").build(),
                CompletionRequest.builder().prompt("b").build(),
                CompletionRequest.builder().prompt("c").build()));

        String upload = received.get("POST /files");
        assertTrue(upload.contains("name=\"purpose\"") && upload.contains("batch"), upload);
        assertTrue(upload.contains("\"url\":\"/v1/chat/completions\""), upload);
        assertTrue(upload.contains("\"custom_id\":\"2\""), upload);
        JsonObject create = JsonParser.parseString(received.get("POST /batches")).getAsJsonObject();
        assertEquals("file-in", create.get("input_file_id").getAsString());
        assertEquals("24h", create.get("completion_window").getAsString());
        assertEquals(CompletionBatch.Status.IN_PROGRESS, batch.status());

        assertEquals(CompletionBatch.Status.ENDED, batch.refresh());
        List<CompletionBatch.Item> items = batch.results();
        assertEquals(List.of("zero", "one"), List.of(items.get(0).result().text(), items.get(1).result().text()));
        assertEquals(5, items.get(0).result().inputTokens());
        assertNull(items.get(2).result());
        assertEquals("rejected", items.get(2).error());
    }

    // ---- everyone else

    @Test
    void aCortexWithNoBatchApiSaysSo() {
        Cortex ollama = CortexOllama.builder().baseUrl("http://localhost:1").model("qwen3:8b").build();
        assertThrows(UnsupportedOperationException.class,
                () -> ollama.submitBatch(List.of(CompletionRequest.builder().prompt("x").build())));
        assertThrows(UnsupportedOperationException.class, () -> ollama.batch("b"));
    }

    @Test
    void anEmptyBatchIsRefused() {
        Cortex cortex = CortexAnthropic.builder().baseUrl("http://localhost:1").apiKey("k").model("claude-x").build();
        assertThrows(IllegalArgumentException.class, () -> cortex.submitBatch(List.of()));
    }
}
