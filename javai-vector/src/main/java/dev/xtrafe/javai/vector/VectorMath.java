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
 *
 * <h2>Every public method speaks {@link EmbeddingVector}, never {@code float[]} (OMI-218)</h2>
 *
 * This class used to expose {@code normalize(float[])} and {@code addWeighted(float[], float[], float)}
 * alongside its vector-level methods, and every caller of those two was doing the same thing by hand:
 * accumulate a weighted sum into a bare array, then wrap it back up. Three separate sites, three separate
 * copies of the model-id check, the dimension check, and the absent-input handling -- and they did not agree
 * with each other. One threw on a dimension mismatch, one silently skipped it, one crashed with
 * {@link ArrayIndexOutOfBoundsException} on an absent input because a bare {@code float[]} cannot express
 * "there is no vector here".
 *
 * <p>That is the actual argument for the narrower surface: {@link EmbeddingVector} carries the model, the
 * dimensionality, and the {@link EmbeddingVector#isAbsent() absence} that make those checks possible, and a
 * {@code float[]} carries none of them. Moving the arithmetic behind this boundary means the rules are
 * stated once, here, and every caller gets the same answer.
 *
 * <h2>Absent and null</h2>
 *
 * Every method accepts both without throwing, and treats them the same way -- as "no vector here" rather
 * than as an error or as a zero vector:
 *
 * <ul>
 *   <li>An absent or null <em>input</em> to an aggregate is skipped, never averaged or summed in. A member
 *       with no content must not drag a result toward an arbitrary direction.</li>
 *   <li>An aggregate with no present inputs at all is itself {@link EmbeddingVector#absent() absent}, which
 *       is a representable answer rather than an error a caller must pre-empt by fabricating something.</li>
 *   <li>{@link #cosineSimilarity} of an absent or null operand is {@link Double#NEGATIVE_INFINITY}: a
 *       content-free vector has no direction to compare and must never rank as anyone's nearest
 *       neighbour.</li>
 * </ul>
 *
 * <p>Dimension and model compatibility are checked wherever the operation genuinely requires it, and
 * violations throw {@link IllegalArgumentException} rather than producing a quietly wrong number.
 */
public final class VectorMath {

    private VectorMath() {
    }

    /**
     * One term of a {@link #weightedSum}: a vector and the weight it contributes at.
     *
     * @param vector the vector to contribute; absent or {@code null} contributes nothing
     * @param weight its multiplier -- e.g. a summary vector's decay factor
     */
    public record WeightedVector(EmbeddingVector vector, double weight) {
    }

    /**
     * L2-normalizes {@code vector} to unit length.
     *
     * <p>An absent or null vector normalizes to absent -- there is no direction to scale. An all-zero vector
     * is returned unchanged, since scaling it is undefined and zeroing it again would be a lie about what it
     * is.
     */
    public static EmbeddingVector normalize(EmbeddingVector vector) {
        if (vector == null || vector.isAbsent()) {
            return EmbeddingVector.absent();
        }
        float[] normalized = normalize(vector.values());
        return new EmbeddingVector(normalized, vector.modelId(), normalized.length, vector.computedAt());
    }

    /**
     * The weighted sum of {@code terms} -- each vector multiplied by its weight, added element-wise.
     *
     * <p>This is the shape {@code summaryVector()} is built from (an object's own vector at full weight,
     * plus each {@code @Summary} child's at a decay factor) and the shape a tag-summary vector is built from
     * (each tag's summary at its association's affinity). Deliberately <em>not</em> normalized: callers that
     * want a unit vector compose {@code normalize(weightedSum(...))}, and one that wants the raw magnitude
     * keeps it.
     *
     * <p>Absent and null terms are skipped, so the first <em>present</em> term establishes the model and
     * dimensionality the rest must match. No present terms at all yields an absent vector.
     *
     * @throws IllegalArgumentException if two present terms disagree on dimensionality or model
     */
    public static EmbeddingVector weightedSum(List<WeightedVector> terms) {
        if (terms == null) {
            return EmbeddingVector.absent();
        }
        float[] sum = null;
        String modelId = null;
        for (WeightedVector term : terms) {
            if (term == null || term.vector() == null || term.vector().isAbsent()) {
                continue;
            }
            EmbeddingVector vector = term.vector();
            if (sum == null) {
                sum = new float[vector.dims()];
                modelId = vector.modelId();
            } else {
                requireCompatible(modelId, sum.length, vector);
            }
            addWeighted(sum, vector.values(), (float) term.weight());
        }
        if (sum == null) {
            return EmbeddingVector.absent();
        }
        return new EmbeddingVector(sum, modelId, sum.length, Instant.now());
    }

    /**
     * Cosine similarity, or {@link Double#NEGATIVE_INFINITY} when either side is absent or {@code null} --
     * content-free by definition, so it has no direction to compare and must never rank as anyone's nearest
     * neighbour. Matches how {@code CollectionVectorSupport.similarityOf} already treats a non-vectorizable
     * element, so ranking code needs no special case of its own.
     *
     * <p>Note the absence check comes <em>before</em> the dimension check, deliberately: an absent vector has
     * zero dimensions, and reporting that as a mismatch would turn "this has no content" into an exception on
     * a path whose whole job is to rank things.
     *
     * @throws IllegalArgumentException if both vectors are present but disagree on dimensionality
     */
    public static double cosineSimilarity(EmbeddingVector a, EmbeddingVector b) {
        if (a == null || b == null || a.isAbsent() || b.isAbsent()) {
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
     * <p>Absent and null members are skipped rather than averaged in -- a member with no content must not
     * drag the mean toward an arbitrary direction, and must not count against the divisor either. A
     * collection of nothing but absent members (or of none at all) is itself absent, which is why this does
     * not throw on empty input: "nothing to average" is a representable answer instead of an error the
     * caller had to pre-empt by fabricating a vector (OMI-187).
     *
     * @throws IllegalArgumentException if two present members disagree on dimensionality or model
     */
    public static EmbeddingVector centroid(List<EmbeddingVector> vectors) {
        if (vectors == null) {
            return EmbeddingVector.absent();
        }
        float[] sum = null;
        String modelId = null;
        int present = 0;
        for (EmbeddingVector vector : vectors) {
            if (vector == null || vector.isAbsent()) {
                continue;
            }
            if (sum == null) {
                sum = new float[vector.dims()];
                modelId = vector.modelId();
            } else {
                requireCompatible(modelId, sum.length, vector);
            }
            addWeighted(sum, vector.values(), 1f);
            present++;
        }
        if (sum == null) {
            return EmbeddingVector.absent();
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= present;
        }
        return new EmbeddingVector(sum, modelId, sum.length, Instant.now());
    }

    /**
     * Rejects a vector that cannot be combined with a running aggregate.
     *
     * <p>Dimensionality is checked as well as model id, which the centroid path previously did not do: same
     * model with different dimensions would either throw {@link ArrayIndexOutOfBoundsException} from deep
     * inside the loop or silently truncate, depending on which way the mismatch fell. Both are worse than a
     * named failure.
     */
    private static void requireCompatible(String modelId, int dims, EmbeddingVector vector) {
        if (vector.dims() != dims) {
            throw new IllegalArgumentException("Cannot combine vectors of different dimensionality: "
                    + dims + " vs " + vector.dims() + " -- are they from the same model?");
        }
        if (!java.util.Objects.equals(modelId, vector.modelId())) {
            throw new IllegalArgumentException(
                    "Cannot combine vectors from different models: " + modelId + " vs " + vector.modelId());
        }
    }

    /** L2-normalizes {@code values}; leaves an all-zero vector untouched (nothing to scale by). */
    private static float[] normalize(float[] values) {
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

    /** {@code target += source * weight}, element-wise. Callers must have checked the lengths agree. */
    private static void addWeighted(float[] target, float[] source, float weight) {
        for (int i = 0; i < target.length; i++) {
            target[i] += source[i] * weight;
        }
    }
}
