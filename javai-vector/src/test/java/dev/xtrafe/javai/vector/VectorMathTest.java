package dev.xtrafe.javai.vector;

import dev.xtrafe.javai.vector.VectorMath.WeightedVector;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every public method of {@link VectorMath}, including the paths that only exist because
 * {@link EmbeddingVector} can say "there is no vector here" (OMI-218).
 *
 * <h2>Why absence and null get as much attention as the arithmetic</h2>
 *
 * The arithmetic is the easy part and was never in doubt. What made this class worth cleaning up is that it
 * used to expose {@code float[]} alongside {@code EmbeddingVector}, and a bare array cannot express absence,
 * a model, or a dimensionality. Every caller doing weighted sums by hand therefore re-derived those rules,
 * and the three copies disagreed: one threw on a dimension mismatch, one silently skipped it, one crashed
 * with {@link ArrayIndexOutOfBoundsException} on an absent input.
 *
 * <p>So these tests pin the rules themselves — skipped rather than summed, absent rather than fabricated,
 * thrown rather than quietly wrong — because those are what the callers now depend on instead of their own
 * copies.
 */
class VectorMathTest {

    private static final String MODEL = "test-model";

    @Nested
    class Normalize {

        @Test
        void producesUnitLength() {
            EmbeddingVector normalized = VectorMath.normalize(vector(3f, 4f));

            assertEquals(1.0, length(normalized), 1e-6);
            assertEquals(0.6f, normalized.values()[0], 1e-6);
            assertEquals(0.8f, normalized.values()[1], 1e-6);
        }

        @Test
        void preservesModelAndDimensions() {
            EmbeddingVector normalized = VectorMath.normalize(vector(3f, 4f));

            assertEquals(MODEL, normalized.modelId());
            assertEquals(2, normalized.dims());
        }

        /** Scaling a zero vector is undefined; zeroing it again would be a lie about what it is. */
        @Test
        void leavesAnAllZeroVectorUntouched() {
            EmbeddingVector zero = vector(0f, 0f, 0f);

            EmbeddingVector normalized = VectorMath.normalize(zero);

            assertEquals(0f, normalized.values()[0]);
            assertEquals(3, normalized.dims());
        }

        @Test
        void anAbsentVectorNormalizesToAbsent() {
            assertTrue(VectorMath.normalize(EmbeddingVector.absent()).isAbsent());
        }

        @Test
        void aNullVectorNormalizesToAbsentRatherThanThrowing() {
            assertTrue(VectorMath.normalize(null).isAbsent());
        }

        @Test
        void doesNotMutateItsInput() {
            EmbeddingVector original = vector(3f, 4f);

            VectorMath.normalize(original);

            assertEquals(3f, original.values()[0], "normalize must not scale the caller's vector in place");
        }
    }

    @Nested
    class WeightedSum {

        @Test
        void addsEachTermAtItsWeight() {
            EmbeddingVector sum = VectorMath.weightedSum(List.of(
                    new WeightedVector(vector(1f, 0f), 1.0),
                    new WeightedVector(vector(0f, 2f), 0.5)));

            assertEquals(1f, sum.values()[0], 1e-6);
            assertEquals(1f, sum.values()[1], 1e-6);
        }

        /** Deliberately not normalized -- a caller that wants unit length composes the two. */
        @Test
        void doesNotNormalizeItsResult() {
            EmbeddingVector sum = VectorMath.weightedSum(List.of(new WeightedVector(vector(3f, 4f), 1.0)));

            assertEquals(5.0, length(sum), 1e-6, "the raw magnitude must survive");
        }

        @Test
        void aZeroWeightContributesNothing() {
            EmbeddingVector sum = VectorMath.weightedSum(List.of(
                    new WeightedVector(vector(1f, 1f), 1.0),
                    new WeightedVector(vector(9f, 9f), 0.0)));

            assertEquals(1f, sum.values()[0], 1e-6);
        }

        @Test
        void aNegativeWeightSubtracts() {
            EmbeddingVector sum = VectorMath.weightedSum(List.of(
                    new WeightedVector(vector(1f, 1f), 1.0),
                    new WeightedVector(vector(1f, 1f), -1.0)));

            assertEquals(0f, sum.values()[0], 1e-6);
        }

        /** The rule the old hand-rolled callers each got wrong in a different way. */
        @Test
        void absentTermsAreSkippedRatherThanSummedIn() {
            EmbeddingVector sum = VectorMath.weightedSum(List.of(
                    new WeightedVector(EmbeddingVector.absent(), 1.0),
                    new WeightedVector(vector(1f, 2f), 1.0),
                    new WeightedVector(EmbeddingVector.absent(), 1.0)));

            assertFalse(sum.isAbsent());
            assertEquals(1f, sum.values()[0], 1e-6);
            assertEquals(2, sum.dims(), "an absent term must not size the result to zero dimensions");
        }

        /** An absent leading term must not size the accumulator -- the old crash-on-absent case. */
        @Test
        void anAbsentFirstTermDoesNotDetermineTheDimensions() {
            EmbeddingVector sum = VectorMath.weightedSum(List.of(
                    new WeightedVector(EmbeddingVector.absent(), 1.0),
                    new WeightedVector(vector(1f, 2f, 3f), 1.0)));

            assertEquals(3, sum.dims());
            assertEquals(MODEL, sum.modelId(), "the first present term establishes the model, not the absent one");
        }

        @Test
        void nullTermsAndNullVectorsAreSkipped() {
            List<WeightedVector> terms = Arrays.asList(
                    null,
                    new WeightedVector(null, 1.0),
                    new WeightedVector(vector(4f), 1.0));

            assertEquals(4f, VectorMath.weightedSum(terms).values()[0], 1e-6);
        }

        @Test
        void nothingPresentYieldsAbsent() {
            assertTrue(VectorMath.weightedSum(List.of()).isAbsent());
            assertTrue(VectorMath.weightedSum(
                    List.of(new WeightedVector(EmbeddingVector.absent(), 1.0))).isAbsent());
            assertTrue(VectorMath.weightedSum(null).isAbsent(), "a null list is 'nothing', not a crash");
        }

        @Test
        void mismatchedDimensionsThrowRatherThanTruncateOrCrash() {
            List<WeightedVector> terms = List.of(
                    new WeightedVector(vector(1f, 2f), 1.0),
                    new WeightedVector(vector(1f, 2f, 3f), 1.0));

            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class, () -> VectorMath.weightedSum(terms));
            assertTrue(failure.getMessage().contains("2") && failure.getMessage().contains("3"),
                    "the message must name both dimensionalities: " + failure.getMessage());
        }

        @Test
        void mismatchedModelsThrow() {
            List<WeightedVector> terms = List.of(
                    new WeightedVector(vector(1f, 2f), 1.0),
                    new WeightedVector(
                            new EmbeddingVector(new float[] {1f, 2f}, "other-model", 2, Instant.now()), 1.0));

            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class, () -> VectorMath.weightedSum(terms));
            assertTrue(failure.getMessage().contains("other-model"), failure.getMessage());
        }

        @Test
        void doesNotMutateItsInputs() {
            EmbeddingVector first = vector(1f, 1f);

            VectorMath.weightedSum(List.of(
                    new WeightedVector(first, 1.0), new WeightedVector(vector(5f, 5f), 1.0)));

            assertEquals(1f, first.values()[0], "the first term must not become the accumulator");
        }
    }

    @Nested
    class CosineSimilarity {

        @Test
        void identicalVectorsAreOne() {
            EmbeddingVector v = vector(1f, 2f, 3f);
            assertEquals(1.0, VectorMath.cosineSimilarity(v, v), 1e-6);
        }

        @Test
        void orthogonalVectorsAreZero() {
            assertEquals(0.0, VectorMath.cosineSimilarity(vector(1f, 0f), vector(0f, 1f)), 1e-6);
        }

        @Test
        void oppositeVectorsAreMinusOne() {
            assertEquals(-1.0, VectorMath.cosineSimilarity(vector(1f, 0f), vector(-1f, 0f)), 1e-6);
        }

        @Test
        void magnitudeDoesNotMatter() {
            assertEquals(VectorMath.cosineSimilarity(vector(1f, 1f), vector(1f, 0f)),
                    VectorMath.cosineSimilarity(vector(100f, 100f), vector(1f, 0f)), 1e-6);
        }

        /** A zero vector has no direction, but it is not *absent* -- it has content, all of it zero. */
        @Test
        void aZeroVectorIsZeroSimilarNotNegativeInfinity() {
            assertEquals(0.0, VectorMath.cosineSimilarity(vector(0f, 0f), vector(1f, 1f)));
        }

        @Test
        void anAbsentVectorIsNeverAnyonesNearestNeighbour() {
            assertEquals(Double.NEGATIVE_INFINITY,
                    VectorMath.cosineSimilarity(EmbeddingVector.absent(), vector(1f, 2f)));
            assertEquals(Double.NEGATIVE_INFINITY,
                    VectorMath.cosineSimilarity(vector(1f, 2f), EmbeddingVector.absent()),
                    "absent on either side, and never a dimension-mismatch exception");
            assertEquals(Double.NEGATIVE_INFINITY,
                    VectorMath.cosineSimilarity(EmbeddingVector.absent(), EmbeddingVector.absent()));
        }

        @Test
        void aNullVectorRanksLastRatherThanThrowing() {
            assertEquals(Double.NEGATIVE_INFINITY, VectorMath.cosineSimilarity(null, vector(1f, 2f)));
            assertEquals(Double.NEGATIVE_INFINITY, VectorMath.cosineSimilarity(vector(1f, 2f), null));
            assertEquals(Double.NEGATIVE_INFINITY, VectorMath.cosineSimilarity(null, null));
        }

        @Test
        void mismatchedDimensionsThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> VectorMath.cosineSimilarity(vector(1f, 2f), vector(1f, 2f, 3f)));
        }

        /** Two models can share a dimensionality and still be incomparable, but that is not this method's
         *  call to make: ranking code compares within one model by construction, and refusing here would
         *  break every mixed-model index read that is already dimension-safe. Pinned so the omission is a
         *  decision rather than an oversight. */
        @Test
        void differentModelsOfTheSameSizeAreCompared() {
            EmbeddingVector other = new EmbeddingVector(new float[] {1f, 0f}, "other-model", 2, Instant.now());

            assertEquals(1.0, VectorMath.cosineSimilarity(vector(1f, 0f), other), 1e-6);
        }
    }

    @Nested
    class Centroid {

        @Test
        void isTheMeanVector() {
            EmbeddingVector centroid = VectorMath.centroid(List.of(vector(0f, 0f), vector(2f, 4f)));

            assertEquals(1f, centroid.values()[0], 1e-6);
            assertEquals(2f, centroid.values()[1], 1e-6);
        }

        @Test
        void preservesModelAndDimensions() {
            EmbeddingVector centroid = VectorMath.centroid(List.of(vector(1f, 2f), vector(3f, 4f)));

            assertEquals(MODEL, centroid.modelId());
            assertEquals(2, centroid.dims());
        }

        /**
         * Contract change, OMI-187: this used to throw {@code IllegalStateException}, which forced every
         * caller to pre-empt the empty case by fabricating a vector -- {@code CollectionVectorSupport} did so
         * by embedding the empty string, at a live provider round trip per empty collection. "Nothing to
         * average" is now a representable answer rather than an error.
         */
        @Test
        void ofNothingIsAbsent() {
            assertTrue(VectorMath.centroid(List.of()).isAbsent());
            assertTrue(VectorMath.centroid(null).isAbsent(), "a null list is 'nothing', not a crash");
        }

        @Test
        void skipsAbsentMembersRatherThanAveragingThemIn() {
            EmbeddingVector withAbsent = VectorMath.centroid(
                    List.of(vector(0f, 0f), EmbeddingVector.absent(), vector(2f, 4f)));

            assertFalse(withAbsent.isAbsent());
            assertEquals(1f, withAbsent.values()[0], 1e-6, "an absent member must not change the mean");
            assertEquals(2f, withAbsent.values()[1], 1e-6, "an absent member must not change the mean");
        }

        /** Skipped means skipped: an absent member must not count against the divisor either. */
        @Test
        void anAbsentMemberDoesNotCountTowardTheDivisor() {
            EmbeddingVector centroid = VectorMath.centroid(
                    List.of(vector(2f), EmbeddingVector.absent(), vector(4f)));

            assertEquals(3f, centroid.values()[0], 1e-6, "mean of 2 and 4 is 3, not 2");
        }

        @Test
        void skipsNullMembers() {
            EmbeddingVector centroid = VectorMath.centroid(Arrays.asList(vector(2f), null, vector(4f)));

            assertEquals(3f, centroid.values()[0], 1e-6);
        }

        @Test
        void ofNothingButAbsentMembersIsAbsent() {
            assertTrue(VectorMath.centroid(
                    List.of(EmbeddingVector.absent(), EmbeddingVector.absent())).isAbsent());
        }

        @Test
        void mismatchedModelsThrow() {
            List<EmbeddingVector> vectors = List.of(vector(1f, 2f),
                    new EmbeddingVector(new float[] {1f, 2f}, "other-model", 2, Instant.now()));

            assertThrows(IllegalArgumentException.class, () -> VectorMath.centroid(vectors));
        }

        /**
         * Same model, different dimensionality. Previously unchecked here: depending on which way the
         * mismatch fell this either threw {@link ArrayIndexOutOfBoundsException} from inside the add loop or
         * silently truncated the longer vector, averaging a value nobody asked for.
         */
        @Test
        void mismatchedDimensionsThrowRatherThanTruncateOrCrash() {
            List<EmbeddingVector> longerSecond = List.of(vector(1f, 2f), vector(1f, 2f, 3f));
            List<EmbeddingVector> shorterSecond = List.of(vector(1f, 2f, 3f), vector(1f, 2f));

            assertThrows(IllegalArgumentException.class, () -> VectorMath.centroid(longerSecond));
            assertThrows(IllegalArgumentException.class, () -> VectorMath.centroid(shorterSecond),
                    "the shorter-second case used to be an ArrayIndexOutOfBoundsException");
        }

        @Test
        void doesNotMutateItsInputs() {
            EmbeddingVector first = vector(2f, 2f);

            VectorMath.centroid(List.of(first, vector(4f, 4f)));

            assertEquals(2f, first.values()[0], "the first member must not become the accumulator");
        }

        @Test
        void aSingleMemberIsItsOwnCentroid() {
            EmbeddingVector centroid = VectorMath.centroid(List.of(vector(1f, 2f, 3f)));

            assertEquals(1f, centroid.values()[0], 1e-6);
            assertEquals(3f, centroid.values()[2], 1e-6);
        }
    }

    /** The public surface is EmbeddingVector-only -- no {@code float[]} in or out (OMI-218). */
    @Test
    void noPublicMethodExposesRawArrays() {
        for (var method : VectorMath.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            assertFalse(method.getReturnType().isArray(),
                    method.getName() + " returns a raw array; VectorMath's public surface is EmbeddingVector");
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter.isArray(),
                        method.getName() + " takes a raw array; VectorMath's public surface is EmbeddingVector");
            }
        }
    }

    private static EmbeddingVector vector(float... values) {
        return new EmbeddingVector(values, MODEL, values.length, Instant.now());
    }

    private static double length(EmbeddingVector vector) {
        double sumSquares = 0;
        for (float v : vector.values()) {
            sumSquares += (double) v * v;
        }
        return Math.sqrt(sumSquares);
    }
}
