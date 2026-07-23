package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;

import java.time.Instant;

/**
 * Deterministic, network-free stand-in -- same pattern as {@code javai-collections}'/{@code javai-substrate}'s
 * own test fixtures of the same name. javai-persistence's tests still need real Postgres/Neo4j containers
 * (there's no meaningful way to hermetically fake "does pgvector's/Neo4j's similarity search actually rank
 * correctly"), but the *embeddings themselves* don't need to be real for that -- only genuinely different
 * text needs to produce genuinely different, comparable vectors.
 */
final class FakeEmbeddingProvider implements JavAIEmbeddingProvider {

    static final String MODEL_ID = "fake-test-model";
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
