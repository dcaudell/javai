package dev.xtrafe.javai.vector;

import java.time.Instant;
import java.util.List;

/**
 * The arithmetic {@code vector()}/{@code summaryVector()} and every JavAI collection's
 * {@code centroid()}/{@code sortByCosineDistance()} are built from. Not part of the public
 * {@link JavAIVectorizable} contract -- a plain utility shared by {@link JavAIRuntime} and the
 * concrete collection types.
 *
 * <p>Public because {@code javai-collections} (e.g. {@code KnowledgeGraph}'s {@code similarityTo()})
 * reuses {@link #cosineSimilarity} directly rather than re-deriving it.
 */
public final class VectorMath {

    private VectorMath() {
    }

    /** L2-normalizes {@code values} in place; leaves an all-zero vector untouched (nothing to scale by). */
    public static float[] normalize(float[] values) {
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
    public static void addWeighted(float[] target, float[] source, float weight) {
        for (int i = 0; i < target.length; i++) {
            target[i] += source[i] * weight;
        }
    }

    /**
     * Cosine similarity, or {@link Double#NEGATIVE_INFINITY} when either side {@link EmbeddingVector#isAbsent()
     * is absent} -- content-free by definition, so it has no direction to compare and must never rank as
     * anyone's nearest neighbour. Matches how {@code CollectionVectorSupport.similarityOf} already treats a
     * non-vectorizable element, so ranking code needs no special case of its own.
     */
    public static double cosineSimilarity(EmbeddingVector a, EmbeddingVector b) {
        if (a.isAbsent() || b.isAbsent()) {
            return Double.NEGATIVE_INFINITY;
        }
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

    /**
     * Mean vector of {@code vectors}, treating the whole collection as one point.
     *
     * <p>{@link EmbeddingVector#isAbsent() Absent} inputs are skipped rather than averaged in -- a member
     * with no content must not drag the mean toward an arbitrary direction. A collection of nothing but
     * absent vectors (or of none at all) is itself absent, which is why this no longer throws on empty
     * input: "nothing to average" is now a representable answer instead of an error the caller had to
     * pre-empt by fabricating a vector (OMI-187).
     */
    public static EmbeddingVector centroid(List<EmbeddingVector> vectors) {
        List<EmbeddingVector> present = vectors.stream().filter(vector -> !vector.isAbsent()).toList();
        if (present.isEmpty()) {
            return EmbeddingVector.absent();
        }
        String modelId = present.get(0).modelId();
        int dims = present.get(0).dims();
        float[] sum = new float[dims];
        for (EmbeddingVector vector : present) {
            if (!vector.modelId().equals(modelId)) {
                throw new IllegalArgumentException(
                        "Cannot average vectors from different models: " + modelId + " vs " + vector.modelId());
            }
            addWeighted(sum, vector.values(), 1f);
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= present.size();
        }
        return new EmbeddingVector(sum, modelId, dims, Instant.now());
    }
}
