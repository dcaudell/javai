package dev.xtrafe.javai.model.benchmark;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;

import java.time.Instant;

/**
 * Deterministic (same text always yields the same vector) embedding provider with a fixed artificial
 * delay standing in for network latency to a real embedding backend -- exactly what a benchmark needs to
 * produce a meaningful comparison of blocking vs. non-blocking read paths, without an actual network
 * dependency. Self-contained within this benchmark package -- see {@link BenchmarkVectorizableNode}'s own
 * javadoc for why this duplicates rather than reuses javai-model's own correctness-test fixtures.
 */
final class SimulatedNetworkEmbeddingProvider implements JavAIEmbeddingProvider {

    private static final String MODEL_ID = "benchmark-simulated-model";
    private static final int DIMS = 8;

    private final long delayMillis;

    SimulatedNetworkEmbeddingProvider(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    @Override
    public EmbeddingVector embed(String text) {
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        int hash = text.hashCode();
        float[] values = new float[DIMS];
        for (int i = 0; i < DIMS; i++) {
            values[i] = ((hash >>> (i * 4)) & 0xF) / 15f;
        }
        return new EmbeddingVector(values, MODEL_ID, DIMS, Instant.now());
    }
}
