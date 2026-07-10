package dev.xtrafe.javai.vector;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic, network-free stand-in for a real {@link JavAIEmbeddingProvider}, so this module's tests
 * stay fast and hermetic. Same text always yields the same vector; different text (almost always) yields
 * a different one -- exactly what the lifecycle/propagation tests need to observe, without a Docker
 * dependency. Real embeddings against the TEI container are exercised separately, by the standalone
 * {@code e2e-client-test} project.
 */
final class FakeEmbeddingProvider implements JavAIEmbeddingProvider {

    static final String MODEL_ID = "fake-test-model";
    static final int DIMS = 8;

    private final AtomicInteger callCount = new AtomicInteger();

    @Override
    public EmbeddingVector embed(String text) {
        callCount.incrementAndGet();
        int hash = text.hashCode();
        float[] values = new float[DIMS];
        for (int i = 0; i < DIMS; i++) {
            values[i] = ((hash >>> (i * 4)) & 0xF) / 15f;
        }
        return new EmbeddingVector(values, MODEL_ID, DIMS, Instant.now());
    }

    int callCount() {
        return callCount.get();
    }
}
