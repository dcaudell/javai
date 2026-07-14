package dev.xtrafe.javai.substrate;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;

import java.time.Instant;

/**
 * Deterministic, network-free stand-in so this module's weaving tests don't depend on Docker/TEI --
 * same text always yields the same vector, different text (almost always) yields a different one. Real
 * embeddings against the TEI container are exercised separately by the standalone {@code e2e-client-test}
 * project.
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
