package dev.xtrafe.javai.vector;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorMathTest {

    @Test
    void normalizeProducesUnitLength() {
        float[] normalized = VectorMath.normalize(new float[] {3f, 4f});
        double length = Math.sqrt(normalized[0] * normalized[0] + normalized[1] * normalized[1]);
        assertEquals(1.0, length, 1e-6);
    }

    @Test
    void normalizeLeavesZeroVectorUntouched() {
        float[] zero = new float[] {0f, 0f, 0f};
        assertEquals(0f, VectorMath.normalize(zero)[0]);
    }

    @Test
    void cosineSimilarityOfIdenticalVectorsIsOne() {
        EmbeddingVector v = vector(1f, 2f, 3f);
        assertEquals(1.0, VectorMath.cosineSimilarity(v, v), 1e-6);
    }

    @Test
    void cosineSimilarityOfOrthogonalVectorsIsZero() {
        assertEquals(0.0, VectorMath.cosineSimilarity(vector(1f, 0f), vector(0f, 1f)), 1e-6);
    }

    @Test
    void cosineSimilarityRejectsMismatchedDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> VectorMath.cosineSimilarity(vector(1f, 2f), vector(1f, 2f, 3f)));
    }

    @Test
    void centroidIsTheMeanVector() {
        EmbeddingVector centroid = VectorMath.centroid(List.of(vector(0f, 0f), vector(2f, 4f)));
        assertEquals(1f, centroid.values()[0], 1e-6);
        assertEquals(2f, centroid.values()[1], 1e-6);
    }

    @Test
    void centroidOfNothingThrows() {
        assertThrows(IllegalStateException.class, () -> VectorMath.centroid(List.of()));
    }

    private static EmbeddingVector vector(float... values) {
        return new EmbeddingVector(values, "test-model", values.length, Instant.now());
    }
}
