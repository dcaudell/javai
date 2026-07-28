package dev.xtrafe.javai.model;

import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The concatenated-text pass under {@code precomputeVectors}, and under all three consistency modes
 * (OMI-191).
 *
 * <h2>Why these assert on provider call count</h2>
 *
 * Assembly is deliberately pure string work -- no embedding happens while walking a subtree -- which is what
 * makes batching possible at all: every text can be built first, and only then does anything reach the
 * network. That distinction is invisible if you count embeddings, and obvious if you count <em>calls</em>,
 * so these count calls, exactly as {@code PrecomputeVectorsTest} does for the field pass.
 */
class ConcatenatedTextBatchingTest {

    private static final AtomicLong UNIQUE = new AtomicLong();

    @AfterEach
    void restoreDefaultMode() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
    }

    private static String unique(String label) {
        return label + "-" + UNIQUE.incrementAndGet();
    }

    /** Counts batches as well as texts -- the two come apart here, which is the point. */
    private static final class BatchCountingProvider implements JavAIEmbeddingProvider {

        private final FakeEmbeddingProvider delegate = new FakeEmbeddingProvider();
        private final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public EmbeddingVector embed(String text) {
            batchSizes.add(1);
            return delegate.embed(text);
        }

        @Override
        public List<EmbeddingVector> embedAll(List<String> texts) {
            batchSizes.add(texts.size());
            List<EmbeddingVector> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(delegate.embed(text));
            }
            return vectors;
        }

        @Override
        public String modelId() {
            return FakeEmbeddingProvider.MODEL_ID;
        }
    }

    @Test
    void everyParticipatingObjectsTextIsEmbeddedInOneBatch() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        BatchCountingProvider counting = new BatchCountingProvider();
        JavAIRuntime.configureEmbeddingProvider(counting);

        List<Node> roots = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Node root = new Node(unique("root"));
            root.setChild(new Node(unique("child")));
            roots.add(root);
        }

        JavAIRuntime.precomputeVectors(roots, 100);

        // Two batches: one for the field pass, one for the concatenated-text pass. Not 20, and not 40.
        assertEquals(2, counting.batchSizes.size(),
                "one batch per pass, not one call per object: " + counting.batchSizes);
        // And every root is now warm.
        int before = counting.batchSizes.size();
        roots.forEach(Node::concatenatedTextVector);
        assertEquals(before, counting.batchSizes.size(), "reads after precompute must be free");
    }

    @Test
    void objectsAssemblingTheSameTextAreEmbeddedOnce() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        RecordingEmbeddingProvider recording = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(recording);

        String shared = unique("identical");
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            nodes.add(new Node(shared));
        }

        JavAIRuntime.precomputeVectors(nodes);

        // Two, not one: the field pass embeds the raw field value ("identical-N") and the concatenated
        // pass embeds the assembled text ("text: identical-N\n"). Different strings, so different
        // embeddings -- what de-duplication buys is that five objects cost the same as one, not that the
        // two passes collapse into each other.
        assertEquals(2, recording.ledger().totalCalls(),
                "five objects assembling identical text need one embedding per pass between them\n\n"
                        + recording.ledger().report());
    }

    /** A non-participant must not be embedded by this pass at all, nor cost a batch slot. */
    @Test
    void nonParticipatingObjectsAreNotInTheBatch() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        RecordingEmbeddingProvider recording = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(recording);

        String text = unique("plain");
        JavAIRuntime.precomputeVectors(List.of(new PlainNode(text)));

        // Exactly one call: the field pass for `text`. The concatenated pass must add nothing.
        assertEquals(1, recording.ledger().totalCalls(),
                "a non-participant must cost the field pass only\n\n" + recording.ledger().report());
    }

    /**
     * All three modes, which is where a deferred read could plausibly hand back something wrong: a stale
     * read returns immediately and recomputes off-thread, so a parent's background recomputation can race
     * its children's.
     */
    @ParameterizedTest
    @EnumSource(EmbeddingConsistencyMode.class)
    void everyModeEventuallyServesTheCurrentText(EmbeddingConsistencyMode mode) throws Exception {
        JavAIRuntime.configureConsistencyMode(mode);
        RecordingEmbeddingProvider recording = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(recording);

        Node root = new Node("root");
        Node child = new Node("before");
        root.setChild(child);

        // First read: nothing computed yet, so every mode blocks and computes directly.
        assertFalse(root.concatenatedTextVector().isAbsent());
        assertEquals("text: root\ntext: before\n", root.concatenatedText());

        child.setText("after");
        recording.ledger().awaitQuiescence();

        // The text itself is assembled fresh on every call, so it is current under every mode immediately.
        assertEquals("text: root\ntext: after\n", root.concatenatedText());

        // The vector is mode-dependent on the first read after a mutation, but must converge.
        EmbeddingVector expected = recording.embed("text: root\ntext: after\n");
        for (int attempt = 0; attempt < 50; attempt++) {
            if (java.util.Arrays.equals(expected.values(), root.concatenatedTextVector().values())) {
                return;
            }
            recording.ledger().awaitQuiescence();
            Thread.sleep(20);
        }
        // Compared by value, never by EmbeddingVector.equals: it is a record over a float[], so its
        // generated equals compares array *identity* and two vectors of the same content are never equal.
        assertArrayEquals(expected.values(), root.concatenatedTextVector().values(),
                "every mode must converge on the current text's vector\n\n" + recording.ledger().report());
    }

    @ParameterizedTest
    @EnumSource(EmbeddingConsistencyMode.class)
    void precomputeWarmsEveryModeWithoutWaste(EmbeddingConsistencyMode mode) {
        JavAIRuntime.configureConsistencyMode(mode);
        RecordingEmbeddingProvider recording = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(recording);

        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            nodes.add(new Node(unique("warm")));
        }

        JavAIRuntime.precomputeVectors(nodes);
        recording.ledger().awaitQuiescence();

        assertTrue(nodes.stream().noneMatch(n -> n.concatenatedTextVector().isAbsent()),
                "precompute must leave every participating object warm under " + mode);
    }

    // ---- fixtures ----

    @Summary(concatenate = true)
    static final class Node implements JavAIVectorizable, JavAIDirtyTracking {

        @SuppressWarnings("unused")
        private DirtyTrackingSupport $javai$state;

        @Vectorize
        private String text;

        @Summary(concatenate = true)
        private Node child;

        Node(String text) {
            this.text = text;
        }

        void setText(String text) {
            String oldValue = this.text;
            this.text = text;
            JavAIRuntime.vectorizeFieldMutated(this, "text", oldValue, text);
        }

        void setChild(Node child) {
            this.child = child;
            JavAIRuntime.registerDependency(this, child);
        }

        @Override
        public EmbeddingVector vector() {
            return JavAIRuntime.vector(this, "text");
        }

        @Override
        public EmbeddingVector concatenatedTextVector() {
            return JavAIRuntime.concatenatedTextVector(this, "text");
        }

        @Override
        public String concatenatedText() {
            return JavAIRuntime.concatenatedText(this, "text");
        }

        @Override
        public EmbeddingVector summaryVector() {
            return JavAIRuntime.summaryVector(this, "child", "text");
        }

        @Override
        public EmbeddingVector fieldVector(String fieldName) {
            return JavAIRuntime.fieldVector(this, fieldName);
        }

        @Override
        public double similarityTo(JavAIVectorizable other) {
            return JavAIRuntime.similarityToVectorizable(this, "text", other);
        }

        @Override
        public double similarityTo(EmbeddingVector reference) {
            return JavAIRuntime.similarityToReference(this, "text", reference);
        }

        @Override
        public <R> JavAIList<R> query(EmbeddingVector reference, Class<R> type) {
            return JavAIRuntime.query(this, reference, type, Integer.MAX_VALUE);
        }

        @Override
        public <R> JavAIList<R> query(EmbeddingVector reference, Class<R> type, int maxDepth) {
            return JavAIRuntime.query(this, reference, type, maxDepth);
        }

        @Override
        public void markFieldDirty() {
            JavAIRuntime.markFieldDirty(this);
        }

        @Override
        public boolean isFieldDirty() {
            return JavAIRuntime.isFieldDirty(this);
        }

        @Override
        public void clearFieldDirty() {
            JavAIRuntime.clearFieldDirty(this);
        }

        @Override
        public void markSummaryDirty() {
            JavAIRuntime.markSummaryDirty(this);
        }

        @Override
        public boolean isSummaryDirty() {
            return JavAIRuntime.isSummaryDirty(this);
        }

        @Override
        public void clearSummaryDirty() {
            JavAIRuntime.clearSummaryDirty(this);
        }

        @Override
        public void addDependent(Object dependent) {
            JavAIRuntime.addDependent(this, dependent);
        }

        @Override
        public Iterable<Object> dependents() {
            return JavAIRuntime.dependents(this);
        }
    }

    /** Never opts in -- the control for "the concatenated pass skips non-participants". */
    static final class PlainNode implements JavAIVectorizable, JavAIDirtyTracking {

        @SuppressWarnings("unused")
        private DirtyTrackingSupport $javai$state;

        @Vectorize
        private String text;

        PlainNode(String text) {
            this.text = text;
        }

        @Override
        public EmbeddingVector vector() {
            return JavAIRuntime.vector(this, "text");
        }

        @Override
        public EmbeddingVector concatenatedTextVector() {
            return JavAIRuntime.concatenatedTextVector(this, "text");
        }

        @Override
        public EmbeddingVector summaryVector() {
            return JavAIRuntime.summaryVector(this, "", "text");
        }

        @Override
        public EmbeddingVector fieldVector(String fieldName) {
            return JavAIRuntime.fieldVector(this, fieldName);
        }

        @Override
        public double similarityTo(JavAIVectorizable other) {
            return JavAIRuntime.similarityToVectorizable(this, "text", other);
        }

        @Override
        public double similarityTo(EmbeddingVector reference) {
            return JavAIRuntime.similarityToReference(this, "text", reference);
        }

        @Override
        public <R> JavAIList<R> query(EmbeddingVector reference, Class<R> type) {
            return JavAIRuntime.query(this, reference, type, Integer.MAX_VALUE);
        }

        @Override
        public <R> JavAIList<R> query(EmbeddingVector reference, Class<R> type, int maxDepth) {
            return JavAIRuntime.query(this, reference, type, maxDepth);
        }

        @Override
        public void markFieldDirty() {
            JavAIRuntime.markFieldDirty(this);
        }

        @Override
        public boolean isFieldDirty() {
            return JavAIRuntime.isFieldDirty(this);
        }

        @Override
        public void clearFieldDirty() {
            JavAIRuntime.clearFieldDirty(this);
        }

        @Override
        public void markSummaryDirty() {
            JavAIRuntime.markSummaryDirty(this);
        }

        @Override
        public boolean isSummaryDirty() {
            return JavAIRuntime.isSummaryDirty(this);
        }

        @Override
        public void clearSummaryDirty() {
            JavAIRuntime.clearSummaryDirty(this);
        }

        @Override
        public void addDependent(Object dependent) {
            JavAIRuntime.addDependent(this, dependent);
        }

        @Override
        public Iterable<Object> dependents() {
            return JavAIRuntime.dependents(this);
        }
    }
}
