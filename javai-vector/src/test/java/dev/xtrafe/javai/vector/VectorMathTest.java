package dev.xtrafe.javai.vector;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Contract change, OMI-187: this used to throw {@code IllegalStateException}, which forced every
     * caller to pre-empt the empty case by fabricating a vector -- {@code CollectionVectorSupport} did so
     * by embedding the empty string, at a live provider round trip per empty collection. "Nothing to
     * average" is now a representable answer rather than an error.
     */
    @Test
    void centroidOfNothingIsAbsent() {
        assertTrue(VectorMath.centroid(List.of()).isAbsent());
    }

    @Test
    void centroidSkipsAbsentMembersRatherThanAveragingThemIn() {
        EmbeddingVector withAbsent = VectorMath.centroid(
                List.of(vector(0f, 0f), EmbeddingVector.absent(), vector(2f, 4f)));

        assertFalse(withAbsent.isAbsent());
        assertEquals(1f, withAbsent.values()[0], 1e-6, "an absent member must not change the mean");
        assertEquals(2f, withAbsent.values()[1], 1e-6, "an absent member must not change the mean");
    }

    @Test
    void centroidOfNothingButAbsentMembersIsAbsent() {
        assertTrue(VectorMath.centroid(List.of(EmbeddingVector.absent(), EmbeddingVector.absent())).isAbsent());
    }

    @Test
    void anAbsentVectorIsNeverAnyonesNearestNeighbour() {
        assertEquals(Double.NEGATIVE_INFINITY,
                VectorMath.cosineSimilarity(EmbeddingVector.absent(), vector(1f, 2f)));
        assertEquals(Double.NEGATIVE_INFINITY,
                VectorMath.cosineSimilarity(vector(1f, 2f), EmbeddingVector.absent()),
                "absent on either side, and never a dimension-mismatch exception");
    }

    private static EmbeddingVector vector(float... values) {
        return new EmbeddingVector(values, "test-model", values.length, Instant.now());
    }
}
