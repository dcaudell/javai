package dev.xtrafe.javai.completion;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tool calling with a bounded loop (OMI-643), against a fake server scripted turn by turn. */
class CompletionToolTest {

    record Roll(String dice) {
    }

    private static final CompletionTool ROLL = new CompletionTool("roll", "Rolls dice written as NdM+K.", Roll.class);

    private HttpServer server;
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Answers the n-th request with {@code replies[n]}, repeating the last one. */
    private String startFakeServer(String... replies) throws IOException {
        AtomicInteger turn = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = replies[Math.min(turn.getAndIncrement(), replies.length - 1)].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static CompletionRequest ask(int maxToolCalls) {
        return CompletionRequest.builder().prompt("Roll a d20 for initiative.").tool(ROLL).maxToolCalls(maxToolCalls)
                .build();
    }

    private static final String ANTHROPIC_TOOL_USE = """
            {"id":"m1","type":"message","role":"assistant","model":"claude-x","stop_reason":"tool_use",
             "content":[{"type":"tool_use","id":"toolu_1","name":"roll","input":{"dice":"1d20"}}],
             "usage":{"input_tokens":20,"output_tokens":8}}""";
    private static final String ANTHROPIC_ANSWER = """
            {"id":"m2","type":"message","role":"assistant","model":"claude-x","stop_reason":"end_turn",
             "content":[{"type":"text","text":"You rolled 17."}],"usage":{"input_tokens":40,"output_tokens":5}}""";

    // ---- Anthropic, through Spring AI's loop

    @Test
    void anthropicRunsTheToolAndAnswersWithItsResult() throws IOException {
        String base = startFakeServer(ANTHROPIC_TOOL_USE, ANTHROPIC_ANSWER);
        Cortex cortex = CortexAnthropic.builder().baseUrl(base).apiKey("k").model("claude-x").build();
        List<Roll> rolled = new ArrayList<>();

        CompletionResult result = cortex.complete(ask(4), call -> {
            rolled.add(call.arguments(Roll.class));
            return "17";
        });

        assertEquals("You rolled 17.", result.text());
        assertEquals(List.of(new Roll("1d20")), rolled);
        assertEquals(1, result.toolCalls().size());
        CompletionToolCall call = result.toolCalls().get(0);
        assertEquals("roll", call.name());
        assertEquals("17", call.result());
        assertNull(call.error());
        assertTrue(bodies.get(0).contains("\"tools\"") && bodies.get(0).contains("Rolls dice"), bodies.get(0));
        assertTrue(bodies.get(1).contains("tool_result") && bodies.get(1).contains("17"),
                "the result goes back to the model: " + bodies.get(1));
    }

    @Test
    void theLimitStopsAModelThatKeepsCallingTools() throws IOException {
        String base = startFakeServer(ANTHROPIC_TOOL_USE); // tool_use, forever
        Cortex cortex = CortexAnthropic.builder().baseUrl(base).apiKey("k").model("claude-x").build();
        AtomicInteger executed = new AtomicInteger();

        CompletionToolLimitException thrown = assertThrows(CompletionToolLimitException.class,
                () -> cortex.complete(ask(2), call -> String.valueOf(executed.incrementAndGet())));

        assertEquals(2, executed.get(), "exactly the limit, never one more");
        assertEquals(2, thrown.calls().size());
    }

    @Test
    void anExecutorsExceptionEndsTheCompletion() throws IOException {
        String base = startFakeServer(ANTHROPIC_TOOL_USE, ANTHROPIC_ANSWER);
        Cortex cortex = CortexAnthropic.builder().baseUrl(base).apiKey("k").model("claude-x").build();

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> cortex.complete(ask(4), call -> {
                    throw new IllegalStateException("no such table");
                }));

        assertTrue(thrown.getMessage().contains("roll") && thrown.getMessage().contains("no such table"),
                thrown.getMessage());
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    // ---- OpenAI, through Spring AI's loop

    @Test
    void openAiRunsTheToolAndAnswersWithItsResult() throws IOException {
        String base = startFakeServer("""
                {"id":"c1","object":"chat.completion","created":1,"model":"gpt-x",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call_1","type":"function","function":{"name":"roll","arguments":"{\\"dice\\":\\"2d6\\"}"}}]}}],
                 "usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}}""", """
                {"id":"c2","object":"chat.completion","created":2,"model":"gpt-x",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Seven."}}],
                 "usage":{"prompt_tokens":40,"completion_tokens":2,"total_tokens":42}}""");
        Cortex cortex = CortexOpenAI.builder().baseUrl(base).apiKey("k").model("gpt-x").build();

        CompletionResult result = cortex.complete(ask(4), call -> {
            assertEquals(new Roll("2d6"), call.arguments(Roll.class));
            return "7";
        });

        assertEquals("Seven.", result.text());
        assertEquals("7", result.toolCalls().get(0).result());
        assertTrue(bodies.get(1).contains("call_1") && bodies.get(1).contains("\"7\""), bodies.get(1));
    }

    // ---- Ollama, through this connector's own loop

    @Test
    void ollamaRunsTheToolAndSumsUsageOverTheTurns() throws IOException {
        String base = startFakeServer("""
                {"model":"qwen3:8b","created_at":"2026-10-12T00:00:00Z","done":true,"prompt_eval_count":20,"eval_count":8,
                 "message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"roll","arguments":{"dice":"1d8"}}}]}}""", """
                {"model":"qwen3:8b","created_at":"2026-10-12T00:00:01Z","done":true,"prompt_eval_count":40,"eval_count":3,
                 "message":{"role":"assistant","content":"A 5."}}""");
        Cortex cortex = CortexOllama.builder().baseUrl(base).model("qwen3:8b").build();

        CompletionResult result = cortex.complete(ask(4), call -> {
            assertEquals(new Roll("1d8"), call.arguments(Roll.class));
            return "5";
        });

        assertEquals("A 5.", result.text());
        assertEquals(60, result.inputTokens());
        assertEquals(11, result.outputTokens());
        assertEquals("5", result.toolCalls().get(0).result());
        assertTrue(bodies.get(0).contains("\"tools\"") && bodies.get(0).contains("Rolls dice"), bodies.get(0));
        assertTrue(bodies.get(1).contains("\"role\":\"tool\"") && bodies.get(1).contains("\"5\""), bodies.get(1));
    }

    @Test
    void ollamasLimitStopsTheLoopToo() throws IOException {
        String base = startFakeServer("""
                {"model":"qwen3:8b","created_at":"2026-10-12T00:00:00Z","done":true,
                 "message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"roll","arguments":{"dice":"1d4"}}}]}}""");
        Cortex cortex = CortexOllama.builder().baseUrl(base).model("qwen3:8b").build();
        AtomicInteger executed = new AtomicInteger();
        assertThrows(CompletionToolLimitException.class,
                () -> cortex.complete(ask(3), call -> String.valueOf(executed.incrementAndGet())));
        assertEquals(3, executed.get());
    }

    // ---- refusals and values

    @Test
    void aRequestOfferingToolsNeedsAnExecutor() {
        Cortex anthropic = CortexAnthropic.builder().baseUrl("http://localhost:1").apiKey("k").model("c").build();
        Cortex ollama = CortexOllama.builder().baseUrl("http://localhost:1").model("q").build();
        assertThrows(IllegalArgumentException.class, () -> anthropic.complete(ask(2)));
        assertThrows(IllegalArgumentException.class, () -> ollama.complete(ask(2)));
    }

    @Test
    void replicateCannotOfferTools() {
        Cortex replicate = CortexReplicate.builder().baseUrl("http://localhost:1").apiToken("t").model("o/m").build();
        assertThrows(UnsupportedOperationException.class, () -> replicate.complete(ask(2), call -> "x"));
    }

    @Test
    void aToolNeedsANameADescriptionAndAnObjectInput() {
        assertThrows(IllegalArgumentException.class, () -> new CompletionTool("has space", "d", Roll.class));
        assertThrows(IllegalArgumentException.class, () -> new CompletionTool("roll", " ", Roll.class));
        assertThrows(IllegalArgumentException.class, () -> new CompletionTool("roll", "d", String.class));
        assertTrue(ROLL.inputSchema().contains("\"dice\""), ROLL.inputSchema());
    }

    @Test
    void twoToolsMayNotShareAName() {
        assertThrows(IllegalStateException.class, () -> CompletionRequest.builder().prompt("x").tool(ROLL)
                .tool(new CompletionTool("roll", "another", Roll.class)).build());
    }

    @Test
    void theDefaultLimitAppliesWhenNoneIsSet() {
        CompletionRequest request = CompletionRequest.builder().prompt("x").tool(ROLL).build();
        assertEquals(CompletionRequest.DEFAULT_MAX_TOOL_CALLS, request.toolCallLimit());
    }

    @Test
    void argumentsOfTheWrongShapeAreRefused() {
        CompletionToolCall call = new CompletionToolCall("roll", "{\"dice\": 20}", null, null, 0);
        assertThrows(CompletionResponseException.class, () -> call.arguments(Roll.class));
    }

    @Test
    void theTokenLimitStopsALoopThatSpendsTooMuch() throws IOException {
        // Each tool_use turn reports 20 + 8 tokens: past 50 after the second, so the third turn never happens.
        String base = startFakeServer(ANTHROPIC_TOOL_USE);
        Cortex cortex = CortexAnthropic.builder().baseUrl(base).apiKey("k").model("claude-x").build();
        AtomicInteger executed = new AtomicInteger();
        CompletionRequest request = CompletionRequest.builder().prompt("Roll.").tool(ROLL).maxToolCalls(10)
                .maxToolLoopTokens(50).build();

        CompletionToolLimitException thrown = assertThrows(CompletionToolLimitException.class,
                () -> cortex.complete(request, call -> String.valueOf(executed.incrementAndGet())));

        assertEquals(1, executed.get(), "one turn under the limit ran its tool; the next was stopped");
        assertTrue(thrown.getMessage().contains("56"), thrown.getMessage());
    }

    @Test
    void anAnswerArrivingOverTheTokenLimitIsStillTheAnswer() throws IOException {
        String base = startFakeServer(ANTHROPIC_TOOL_USE, ANTHROPIC_ANSWER);
        Cortex cortex = CortexAnthropic.builder().baseUrl(base).apiKey("k").model("claude-x").build();
        CompletionResult result = cortex.complete(CompletionRequest.builder().prompt("Roll.").tool(ROLL)
                .maxToolLoopTokens(30).build(), call -> "17");
        assertEquals("You rolled 17.", result.text());
    }

    @Test
    void ollamasTokenLimitStopsItsLoopToo() throws IOException {
        String base = startFakeServer("""
                {"model":"qwen3:8b","created_at":"2026-10-12T00:00:00Z","done":true,"prompt_eval_count":30,"eval_count":10,
                 "message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"roll","arguments":{"dice":"1d4"}}}]}}""");
        Cortex cortex = CortexOllama.builder().baseUrl(base).model("qwen3:8b").build();
        AtomicInteger executed = new AtomicInteger();
        assertThrows(CompletionToolLimitException.class, () -> cortex.complete(CompletionRequest.builder()
                .prompt("Roll.").tool(ROLL).maxToolCalls(10).maxToolLoopTokens(70).build(),
                call -> String.valueOf(executed.incrementAndGet())));
        assertEquals(1, executed.get());
    }
}
