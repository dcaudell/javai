package dev.xtrafe.javai.runtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingVectorTest {

    @Test
    void storesValuesAndModelId() {
        var vector = new EmbeddingVector(new float[] {0.1f, 0.2f}, "qwen3-embedding-0.6b", 2, Instant.now());
        assertEquals("qwen3-embedding-0.6b", vector.modelId());
        assertEquals(2, vector.dims());
    }

    @Test
    void rejectsMismatchedDims() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingVector(new float[] {0.1f}, "qwen3-embedding-0.6b", 2, Instant.now()));
    }

    @Test
    void requiresModelId() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmbeddingVector(new float[] {0.1f}, " ", 1, Instant.now()));
    }
}
