package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorizableStringTest {

    @BeforeAll
    static void configureFakeProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @AfterEach
    void resetGlobalConfig() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.THROW);
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureMaxConcurrentEmbeddingCalls(JavAIRuntime.DEFAULT_MAX_CONCURRENT_EMBEDDING_CALLS);
    }

    @Test
    void vectorComputesOnceAndCaches() {
        VectorizableString box = new VectorizableString("hello");
        EmbeddingVector first = box.vector();
        EmbeddingVector second = box.vector();
        assertSame(first, second, "an immutable box must compute exactly once and serve the same cached instance after");
    }

    @Test
    void concatenatedTextVectorAndSummaryVectorDelegateToVector() {
        VectorizableString box = new VectorizableString("hello");
        assertSame(box.vector(), box.concatenatedTextVector());
        assertSame(box.vector(), box.summaryVector());
    }

    @Test
    void nullValueIsBoxedAsEmptyString() {
        VectorizableString box = new VectorizableString(null);
        assertEquals("", box.value());
    }

    @Test
    void fieldVectorThrowsForALeaf() {
        VectorizableString box = new VectorizableString("hello");
        assertThrows(UnsupportedOperationException.class, () -> box.fieldVector("anything"));
    }

    @Test
    void queryReturnsEmptyForALeaf() {
        VectorizableString box = new VectorizableString("hello");
        assertTrue(box.query(box.vector(), Object.class).isEmpty());
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        assertEquals(new VectorizableString("x"), new VectorizableString("x"));
        assertEquals(new VectorizableString("x").hashCode(), new VectorizableString("x").hashCode());
    }

    @Test
    void throwModeRethrowsOnFailure() {
        JavAIRuntime.configureEmbeddingProvider(failingProvider());
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.THROW);
        VectorizableString box = new VectorizableString("hello");
        assertThrows(RuntimeException.class, box::vector);
    }

    @Test
    void returnNullModeSwallowsAndReturnsNull() {
        JavAIRuntime.configureEmbeddingProvider(failingProvider());
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.RETURN_NULL);
        VectorizableString box = new VectorizableString("hello");
        assertNull(box.vector());
    }

    @Test
    void aFailedAttemptStaysRetryableOnTheNextCall() {
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.RETURN_NULL);
        AtomicInteger callCount = new AtomicInteger();
        JavAIRuntime.configureEmbeddingProvider(text -> {
            if (callCount.getAndIncrement() == 0) {
                throw new RuntimeException("transient failure");
            }
            return new EmbeddingVector(new float[] {1f}, "test-model", 1, Instant.now());
        });
        VectorizableString box = new VectorizableString("hello");

        assertNull(box.vector(), "the first call fails");
        EmbeddingVector second = box.vector();
        assertEquals(1f, second.values()[0], 1e-6f, "a failed attempt must not be treated as clean -- the next call retries");
    }

    @Test
    void concurrentFirstReadsAllObserveAConsistentResult() throws InterruptedException {
        // Many threads racing to compute a brand-new box's vector for the first time -- whichever
        // generation's attempt lands, every reader must observe a real, non-null, mutually consistent
        // result (never a torn or partially-applied one).
        VectorizableString box = new VectorizableString("hello");
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        EmbeddingVector[] results = new EmbeddingVector[threads];

        for (int i = 0; i < threads; i++) {
            int index = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                results[index] = box.vector();
            });
        }
        ready.await();
        go.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        for (EmbeddingVector result : results) {
            assertEquals(results[0].values()[0], result.values()[0], 1e-6f);
        }
    }

    private static JavAIEmbeddingProvider failingProvider() {
        return text -> {
            throw new RuntimeException("simulated provider failure");
        };
    }
}
