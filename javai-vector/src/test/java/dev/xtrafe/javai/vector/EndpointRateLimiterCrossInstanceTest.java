package dev.xtrafe.javai.vector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the cross-instance claim in doc/spec/completion-fabric.md directly (not just that a single
 * provider retries its own 429s): two separate {@link EmbeddingProviderOllama} instances -- distinct
 * objects, sharing nothing but the same base URL -- coordinate through the same {@link EndpointRateLimiter}
 * looked up by that URL, so a 429 seen by one instance backs off the other too.
 */
class EndpointRateLimiterCrossInstanceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void a429SeenByOneInstanceDelaysAnUnrelatedInstanceSharingTheSameEndpoint() throws IOException, InterruptedException {
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch instanceARequestSent = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/embed", exchange -> {
            if (requestCount.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "2");
                byte[] body = "rate limited".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
            } else {
                byte[] body = "{\"model\":\"test-model\",\"embeddings\":[[0.1]]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
            instanceARequestSent.countDown();
        });
        server.start();
        URI baseUri = URI.create("http://localhost:" + server.getAddress().getPort());

        var instanceA = new EmbeddingProviderOllama(baseUri, "test-model");
        var instanceB = new EmbeddingProviderOllama(baseUri, "test-model");

        // Instance A absorbs the one-and-only 429 in a background thread, retrying (and blocking) on its
        // own until the 2-second Retry-After window passes.
        Thread instanceAThread = new Thread(() -> instanceA.embed("from A"));
        instanceAThread.start();
        assertTrue(instanceARequestSent.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "instance A's first request (the one that gets 429'd) must have been sent");
        // Give instance A's own HTTP client a moment to receive the 429 and record it on the shared
        // limiter before B calls -- the server closing the exchange and the client processing the
        // response aren't the same instant.
        Thread.sleep(300);

        // Instance B calls the *same* endpoint next -- the server would happily return 200 immediately
        // (only the very first request was ever 429'd), so if state weren't shared, B's own call would
        // return almost instantly regardless of what just happened to A.
        Instant beforeB = Instant.now();
        instanceB.embed("from B");
        Duration elapsedForB = Duration.between(beforeB, Instant.now());

        instanceAThread.join(5000);

        assertTrue(elapsedForB.toMillis() >= 1000,
                "instance B must have been delayed by instance A's 429 via the shared EndpointRateLimiter -- "
                        + "elapsed was only " + elapsedForB.toMillis() + "ms, suggesting the two instances did not "
                        + "share rate-limit state");
    }
}
