package dev.xtrafe.javai.completion;

import com.sun.net.httpserver.HttpServer;
import dev.xtrafe.javai.vector.EmbeddingProviderOllama;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the "several cortices pointed at the same endpoint" claim from doc/spec/completion-fabric.md holds
 * for real, independently-constructed {@link Cortex} instances -- not just for {@code javai-vector}'s own
 * {@code EndpointRateLimiterCrossInstanceTest} (which proves the same thing for two
 * {@code JavAIEmbeddingProvider}s). A second test here also proves the sharing crosses module boundaries:
 * a {@link Cortex} and an unrelated {@code javai-vector} {@code JavAIEmbeddingProvider} coordinate through
 * the exact same {@code EndpointRateLimiter} registry when pointed at the same base URL.
 */
class EndpointRateLimiterCrossInstanceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpServer startOneTimeRateLimitedServer(AtomicInteger requestCount, CountDownLatch firstRequestSent)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            if (requestCount.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "2");
                byte[] body = "rate limited".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
            } else if (exchange.getRequestURI().getPath().equals("/api/embed")) {
                byte[] body = "{\"model\":\"test-model\",\"embeddings\":[[0.1]]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                byte[] body = """
                        {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-4.1",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
            firstRequestSent.countDown();
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return server;
    }

    @Test
    void a429SeenByOneCortexDelaysAnUnrelatedCortexInstanceSharingTheSameEndpoint() throws IOException, InterruptedException {
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch firstRequestSent = new CountDownLatch(1);
        server = startOneTimeRateLimitedServer(requestCount, firstRequestSent);
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        Cortex cortexA = CortexOpenAI.builder().baseUrl(baseUrl).apiKey("test-key").model("gpt-4.1").build();
        Cortex cortexB = CortexOpenAI.builder().baseUrl(baseUrl).apiKey("test-key").model("gpt-4.1").build();

        Thread cortexAThread = new Thread(() -> cortexA.complete(CompletionRequest.builder().prompt("from A").build()));
        cortexAThread.start();
        assertTrue(firstRequestSent.await(5, TimeUnit.SECONDS), "cortex A's first (429'd) request must have been sent");
        Thread.sleep(300);

        Instant beforeB = Instant.now();
        cortexB.complete(CompletionRequest.builder().prompt("from B").build());
        Duration elapsedForB = Duration.between(beforeB, Instant.now());

        cortexAThread.join(5000);

        assertTrue(elapsedForB.toMillis() >= 1000,
                "cortex B must have been delayed by cortex A's 429 via the shared EndpointRateLimiter -- "
                        + "elapsed was only " + elapsedForB.toMillis() + "ms");
    }

    /** Same proof, but across the {@code javai-completion}/{@code javai-vector} module boundary. */
    @Test
    void a429SeenByACortexDelaysAnUnrelatedEmbeddingProviderSharingTheSameEndpoint() throws IOException, InterruptedException {
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch firstRequestSent = new CountDownLatch(1);
        server = startOneTimeRateLimitedServer(requestCount, firstRequestSent);
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        Cortex cortex = CortexOpenAI.builder().baseUrl(baseUrl).apiKey("test-key").model("gpt-4.1").build();
        var embeddingProvider = new EmbeddingProviderOllama(URI.create(baseUrl), "test-model");

        Thread cortexThread = new Thread(() -> cortex.complete(CompletionRequest.builder().prompt("from cortex").build()));
        cortexThread.start();
        assertTrue(firstRequestSent.await(5, TimeUnit.SECONDS), "the cortex's first (429'd) request must have been sent");
        Thread.sleep(300);

        Instant beforeEmbed = Instant.now();
        embeddingProvider.embed("from embedding provider");
        Duration elapsedForEmbed = Duration.between(beforeEmbed, Instant.now());

        cortexThread.join(5000);

        assertTrue(elapsedForEmbed.toMillis() >= 1000,
                "the embedding provider must have been delayed by the cortex's 429 via the shared "
                        + "EndpointRateLimiter, across the javai-vector/javai-completion module boundary -- "
                        + "elapsed was only " + elapsedForEmbed.toMillis() + "ms");
    }
}
