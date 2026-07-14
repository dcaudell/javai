package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;

import java.time.Instant;

/**
 * Deterministic, network-free stand-in for a real {@link JavAIEmbeddingProvider}, so this module's tests
 * stay fast and hermetic. Same text always yields the same vector; different text (almost always) yields
 * a different one -- exactly what {@link JavAIRuntime}'s lifecycle/propagation tests need to observe,
 * without a Docker dependency. A separate, equivalent copy lives in {@code javai-vector}'s own test tree
 * (test code isn't shared across module boundaries without a test-jar dependency, not worth it for a class
 * this small); real embeddings against a live provider are exercised by the standalone
 * {@code e2e-client-test} project.
 */
final class FakeEmbeddingProvider implements JavAIEmbeddingProvider {

    private static final String MODEL_ID = "fake-test-model";
    private static final int DIMS = 8;

    @Override
    public EmbeddingVector embed(String text) {
        int hash = text.hashCode();
        float[] values = new float[DIMS];
        for (int i = 0; i < DIMS; i++) {
            values[i] = ((hash >>> (i * 4)) & 0xF) / 15f;
        }
        return new EmbeddingVector(values, MODEL_ID, DIMS, Instant.now());
    }
}
