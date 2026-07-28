package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JavAI collection's {@code summaryVector()}, where it used to disagree with an object's (OMI-218).
 *
 * <h2>Why these two cases</h2>
 *
 * Both paths compute the same formula -- own vector at full weight, plus each child's summary at a decay
 * factor -- but they were separate hand-rolled implementations, and they answered two questions differently.
 * Now that both go through {@code VectorMath.weightedSum}, these pin the answers the collection path
 * changed to.
 */
class CollectionSummaryVectorTest {

    @BeforeEach
    void configureProvider() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    /**
     * A collection whose centroid is absent can still have a summary, because a member's own vector being
     * absent says nothing about its children.
     *
     * <p>The collection path used to short-circuit the whole summary to absent as soon as the centroid was
     * absent, reasoning that a collection with no vectorizable content has "no children to sum".
     * {@code JavAIRuntime.summaryVector} never made the equivalent assumption about an object's own vector,
     * and it was wrong here for the same reason: the member below has no content of its own, and a child
     * that does.
     */
    @Test
    void anAbsentCentroidDoesNotSuppressAMembersOwnSummary() {
        TestContainer contentless = new TestContainer(null);
        contentless.setFeatured(new TestNode("a featured node with real content"));

        JavAIArrayList<TestContainer> list = new JavAIArrayList<>();
        list.add(contentless);

        assertTrue(list.vector().isAbsent(),
                "precondition: no member has content of its own, so the centroid is absent");
        assertFalse(contentless.summaryVector().isAbsent(),
                "precondition: the member's own summary is not absent -- its child has content");
        assertFalse(list.summaryVector().isAbsent(),
                "the collection's summary must reflect its members' summaries, not just their own vectors");
    }

    /** With genuinely nothing anywhere, absent is still the answer. */
    @Test
    void aCollectionWithNoContentAnywhereIsStillAbsent() {
        JavAIArrayList<TestContainer> list = new JavAIArrayList<>();
        list.add(new TestContainer(null));

        assertTrue(list.summaryVector().isAbsent());
    }

    /**
     * A member of a different dimensionality is refused rather than dropped.
     *
     * <p>It used to be skipped by a silent {@code dims() == sum.length} guard, which is the shape of wrong
     * answer that cannot be noticed from outside: the summary comes back looking perfectly well-formed, just
     * computed over fewer members than the collection actually holds.
     */
    @Test
    void aMemberOfADifferentDimensionalityIsRefusedRatherThanSilentlyDropped() {
        JavAIArrayList<JavAIVectorizable> list = new JavAIArrayList<>();
        list.add(new FixedVectorNode(new float[] {1f, 0f}));
        list.add(new FixedVectorNode(new float[] {1f, 0f, 0f}));

        assertThrows(IllegalArgumentException.class, list::summaryVector);
    }

    /** Reports a fixed vector as both its own and its summary, so a test can choose the dimensionality. */
    private static final class FixedVectorNode implements JavAIVectorizable {

        private final EmbeddingVector fixed;

        FixedVectorNode(float[] values) {
            this.fixed = new EmbeddingVector(
                    values, FakeEmbeddingProvider.MODEL_ID, values.length, java.time.Instant.now());
        }

        @Override
        public EmbeddingVector vector() {
            return fixed;
        }

        @Override
        public EmbeddingVector concatenatedTextVector() {
            return EmbeddingVector.absent();
        }

        @Override
        public EmbeddingVector summaryVector() {
            return fixed;
        }

        @Override
        public EmbeddingVector fieldVector(String fieldName) {
            return EmbeddingVector.absent();
        }

        @Override
        public double similarityTo(JavAIVectorizable other) {
            return 0;
        }

        @Override
        public double similarityTo(EmbeddingVector reference) {
            return 0;
        }

        @Override
        public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type) {
            return new JavAIArrayList<>();
        }

        @Override
        public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth) {
            return new JavAIArrayList<>();
        }
    }
}
