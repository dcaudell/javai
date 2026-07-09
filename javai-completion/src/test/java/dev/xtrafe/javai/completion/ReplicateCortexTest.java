package dev.xtrafe.javai.completion;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic only -- no Replicate API token was available at implementation time (see this module's README).
 * Proves the create-then-poll-until-terminal contract against a fake HTTP server, since Replicate's own API
 * is fundamentally job-submission-shaped, not a single request/response like every other provider here.
 */
class ReplicateCortexTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void completeReturnsImmediatelyWhenThePredictionAlreadySucceeded() throws IOException {
        StringBuilder capturedRequestBody = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models/meta/meta-llama-3-70b-instruct/predictions", exchange -> {
            capturedRequestBody.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {"id":"pred-1","status":"succeeded","output":["Hello",", ","world!"],
                     "urls":{"get":"http://unused/should-not-be-polled"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        Cortex cortex = ReplicateCortex.builder().baseUrl(baseUrl).apiToken("test-token")
                .model("meta/meta-llama-3-70b-instruct").build();
        CompletionResult result = cortex.complete(CompletionRequest.builder().prompt("Say hi").build());

        assertEquals("Hello, world!", result.text(), "array-shaped output must be joined into one string");
        assertEquals("replicate", result.providerId());
        assertTrue(capturedRequestBody.toString().contains("Say hi"));
    }

    @Test
    void completePollsUntilThePredictionReachesATerminalState() throws IOException {
        AtomicInteger pollCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models/test/model/predictions", exchange -> {
            byte[] body = ("{\"id\":\"pred-2\",\"status\":\"starting\","
                    + "\"urls\":{\"get\":\"http://localhost:" + server.getAddress().getPort() + "/v1/predictions/pred-2\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/predictions/pred-2", exchange -> {
            boolean done = pollCount.incrementAndGet() >= 2;
            String status = done ? "succeeded" : "processing";
            String output = done ? "\"output\":[\"done\"]," : "";
            byte[] body = ("{\"id\":\"pred-2\",\"status\":\"" + status + "\"," + output
                    + "\"urls\":{\"get\":\"unused\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        Cortex cortex = ReplicateCortex.builder().baseUrl(baseUrl).apiToken("test-token").model("test/model").build();
        CompletionResult result = cortex.complete(CompletionRequest.builder().prompt("slow question").build());

        assertEquals("done", result.text());
        assertTrue(pollCount.get() >= 2, "must have polled at least until the terminal status was reached");
    }

    @Test
    void completeThrowsWithTheErrorMessageWhenThePredictionFails() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models/test/model/predictions", exchange -> {
            byte[] body = "{\"id\":\"pred-3\",\"status\":\"failed\",\"error\":\"model overloaded\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        Cortex cortex = ReplicateCortex.builder().baseUrl(baseUrl).apiToken("test-token").model("test/model").build();
        CompletionException exception = assertThrows(CompletionException.class,
                () -> cortex.complete(CompletionRequest.builder().prompt("hi").build()));
        assertTrue(exception.getMessage().contains("model overloaded"));
    }

    @Test
    void completeStreamingEmitsTheFullResultAsOneChunk() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/models/test/model/predictions", exchange -> {
            byte[] body = "{\"id\":\"pred-4\",\"status\":\"succeeded\",\"output\":[\"streamed\"]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        Cortex cortex = ReplicateCortex.builder().baseUrl(baseUrl).apiToken("test-token").model("test/model").build();
        StringBuilder received = new StringBuilder();
        cortex.completeStreaming(CompletionRequest.builder().prompt("hi").build(), received::append);

        assertEquals("streamed", received.toString());
    }

    @Test
    void builderRequiresAModel() {
        assertThrows(IllegalStateException.class, () -> ReplicateCortex.builder().apiToken("t").build());
    }
}
