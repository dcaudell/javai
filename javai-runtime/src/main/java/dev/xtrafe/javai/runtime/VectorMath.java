package dev.xtrafe.javai.runtime;

import java.time.Instant;
import java.util.List;

/**
 * The arithmetic {@code vector()}/{@code summaryVector()} and every JavAI collection's
 * {@code centroid()}/{@code sortByCosineDistance()} are built from. Not part of the public
 * {@link JavAIVectorizable} contract -- a plain utility shared by {@link JavAIRuntime} and the
 * concrete collection types.
 */
final class VectorMath {

    private VectorMath() {
    }

    /** L2-normalizes {@code values} in place; leaves an all-zero vector untouched (nothing to scale by). */
    static float[] normalize(float[] values) {
        double sumSquares = 0;
        for (float v : values) {
            sumSquares += (double) v * v;
        }
        if (sumSquares == 0) {
            return values;
        }
        float norm = (float) Math.sqrt(sumSquares);
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] / norm;
        }
        return result;
    }

    /** {@code target += source * weight}, element-wise. Both arrays must be the same length. */
    static void addWeighted(float[] target, float[] source, float weight) {
        for (int i = 0; i < target.length; i++) {
            target[i] += source[i] * weight;
        }
    }

    static double cosineSimilarity(EmbeddingVector a, EmbeddingVector b) {
        if (a.dims() != b.dims()) {
            throw new IllegalArgumentException(
                    "Cannot compare vectors of different dimensionality: " + a.dims() + " vs " + b.dims());
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.dims(); i++) {
            dot += (double) a.values()[i] * b.values()[i];
            normA += (double) a.values()[i] * a.values()[i];
            normB += (double) b.values()[i] * b.values()[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Mean vector of {@code vectors}, treating the whole collection as one point. */
    static EmbeddingVector centroid(List<EmbeddingVector> vectors) {
        if (vectors.isEmpty()) {
            throw new IllegalStateException("Cannot compute a centroid of zero vectors");
        }
        String modelId = vectors.get(0).modelId();
        int dims = vectors.get(0).dims();
        float[] sum = new float[dims];
        for (EmbeddingVector vector : vectors) {
            if (!vector.modelId().equals(modelId)) {
                throw new IllegalArgumentException(
                        "Cannot average vectors from different models: " + modelId + " vs " + vector.modelId());
            }
            addWeighted(sum, vector.values(), 1f);
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= vectors.size();
        }
        return new EmbeddingVector(sum, modelId, dims, Instant.now());
    }
}
