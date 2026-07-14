package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic like {@link FakeEmbeddingProvider} (same text always yields the same vector), but
 * instrumented for {@link EmbeddingConcurrencyTest}: tracks how many {@link #embed} calls are
 * concurrently in flight (current and high-water mark) and total calls made, can inject an artificial
 * per-call delay to widen race windows, and can be told -- at any point, not just construction, since
 * several tests need a clean first computation to land before inducing a failure -- to fail the next N
 * calls, so retry/failure-handling behavior can be exercised without a real flaky network.
 */
final class ConcurrencyTrackingEmbeddingProvider implements JavAIEmbeddingProvider {

    private static final String MODEL_ID = "concurrency-test-model";
    private static final int DIMS = 8;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger totalCalls = new AtomicInteger();
    private final AtomicInteger remainingFailures = new AtomicInteger();
    private final long artificialDelayMillis;

    ConcurrencyTrackingEmbeddingProvider() {
        this(0);
    }

    ConcurrencyTrackingEmbeddingProvider(long artificialDelayMillis) {
        this.artificialDelayMillis = artificialDelayMillis;
    }

    /** The next {@code n} calls to {@link #embed} throw instead of computing; unaffected calls after
     *  that revert to normal deterministic success. */
    void failNextCalls(int n) {
        remainingFailures.set(n);
    }

    @Override
    public EmbeddingVector embed(String text) {
        int current = inFlight.incrementAndGet();
        maxInFlight.updateAndGet(max -> Math.max(max, current));
        totalCalls.incrementAndGet();
        try {
            if (artificialDelayMillis > 0) {
                try {
                    Thread.sleep(artificialDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (remainingFailures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new RuntimeException("simulated transient embedding failure for text: " + text);
            }
            return embedDeterministic(text);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    int maxInFlight() {
        return maxInFlight.get();
    }

    int totalCalls() {
        return totalCalls.get();
    }

    static EmbeddingVector embedDeterministic(String text) {
        int hash = text.hashCode();
        float[] values = new float[DIMS];
        for (int i = 0; i < DIMS; i++) {
            values[i] = ((hash >>> (i * 4)) & 0xF) / 15f;
        }
        return new EmbeddingVector(values, MODEL_ID, DIMS, Instant.now());
    }
}
