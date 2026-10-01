package dev.xtrafe.javai.model;

import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concatenated text assembly: what text gets built, from which nodes, exactly once each (OMI-191).
 *
 * <h2>Why this is its own thing rather than a variant of summaryVector()</h2>
 *
 * {@code summaryVector()} is arithmetic over already-computed vectors, and lets a node reachable by two
 * paths stack additively -- adding a vector twice is a meaningful weighting. Concatenated text is the other
 * kind of aggregate: real text assembled into one string and embedded once. The same paragraph appearing
 * twice in that string does not weight it, it just skews the embedding toward whatever it happens to say.
 * So the two propagation rules deliberately differ, and the colouring tests below are where that difference
 * is pinned.
 *
 * <p>These tests assert on the <b>assembled text</b> rather than on the resulting vector wherever they can.
 * The vector is a hash of the text under the fake provider, so asserting on text says precisely what went
 * wrong when it breaks, where a vector comparison would only say "different".
 */
class ConcatenatedTextAssemblyTest {

    private RecordingEmbeddingProvider provider;

    @BeforeEach
    void installProvider() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        provider = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    // ---- opting in ----

    @Test
    void aTypeLevelOptInEmbedsItsOwnFields() {
        Node node = new Node("alpha");

        assertEquals("text: alpha\n", node.concatenatedText());
        assertFalse(node.concatenatedTextVector().isAbsent());
    }

    /**
     * <b>An empty value is no content, in both aggregates or in neither.</b>
     *
     * <p>⚠️ This fired in production as an {@code IllegalStateException} out of
     * {@code RepositoryBackendHibernatePostgres.writeEntityGrainRow}, on an entity whose only
     * {@code @Vectorize} fields were empty strings: <em>"has a concatenated text vector but an absent summary
     * vector … This combination was believed impossible"</em>. It was not impossible, and the two halves of
     * this class disagreed about why.
     *
     * <p>{@code embedText} treats blank text as <b>absent</b> -- a deliberate rule, so "this field lost its
     * content" is representable at rest and a backend can delete the row rather than store the embedding of a
     * space. But {@code concatenatedFieldText} tested only {@code != null}, so an empty title still emitted
     * its label -- {@code "title: \n"} -- which is not blank, and got a real embedding. Present concatenated
     * vector, absent summary vector, and a NOT NULL column that cannot hold the pair.
     *
     * <p>The label is the whole of the text in that case: it says a field exists and nothing about what it
     * holds, which is not content by any reading.
     */
    @Test
    void anEmptyFieldContributesNoTextAndNoVector() {
        Node node = new Node("");

        assertNull(node.concatenatedText(), "a label with nothing after it is not text");
        assertTrue(node.concatenatedTextVector().isAbsent());
        assertTrue(node.summaryVector().isAbsent(),
                "the summary was always absent here -- it is the other half that has to agree");
    }

    /** Whitespace is the same case: {@code embedText} calls it blank, so this must not call it text. */
    @Test
    void aWhitespaceOnlyFieldContributesNothingEither() {
        Node node = new Node("   ");

        assertNull(node.concatenatedText());
        assertTrue(node.concatenatedTextVector().isAbsent());
        assertTrue(node.summaryVector().isAbsent());
    }

    /**
     * ⚠️ The pair that must never occur again, asserted as a pair rather than as two separate facts -- it is
     * their <em>combination</em> the entity-grain table cannot represent, and either one alone is fine.
     */
    @Test
    void neverConcatenatedTextWithoutASummaryVector() {
        for (String value : new String[] {null, "", " ", "\n", "real content"}) {
            Node node = new Node(value);
            if (!node.concatenatedTextVector().isAbsent()) {
                assertFalse(node.summaryVector().isAbsent(),
                        "value " + (value == null ? "null" : "'" + value + "'")
                                + " produced concatenated text with no summary vector -- the shape"
                                + " writeEntityGrainRow refuses");
            }
        }
    }

    /** An empty field beside a full one drops only its own label, and the rest is unchanged. */
    @Test
    void anEmptyFieldIsSkippedRatherThanEmptyingTheWholeAssembly() {
        Node parent = new Node("");
        parent.setChild(new Node("alpha"));

        assertEquals("text: alpha\n", parent.concatenatedText(),
                "the parent contributes nothing of its own, and the child is untouched");
        assertFalse(parent.summaryVector().isAbsent());
    }

    /**
     * The opt-out has to be genuinely free, not merely correct: before this ticket, every vectorizable
     * computed one of these on demand at the price of a real model call, and stored it nowhere any query
     * could reach.
     */
    @Test
    void aTypeThatDidNotOptInCostsNothingAtAll() {
        PlainNode node = new PlainNode("alpha");

        // null, not "": no text at all, rather than empty text a provider would happily embed. The same
        // distinction EmbeddingVector.absent() draws for vectors, one level up in the text.
        assertNull(node.concatenatedText());
        assertTrue(node.concatenatedTextVector().isAbsent(),
                "declining must yield an absent vector, not an embedding of nothing");
        assertEquals(0, provider.ledger().totalCalls(),
                "declining must not reach the provider at all\n\n" + provider.ledger().report());
    }

    /** The two opt-ins are independent: absorbing children without contributing your own fields is valid. */
    @Test
    void aFieldOptInAloneAbsorbsChildrenWithoutContributingOwnFields() {
        AbsorbOnlyNode parent = new AbsorbOnlyNode("ignored-own-text");
        parent.setChild(new Node("child"));

        assertEquals("text: child\n", parent.concatenatedText(),
                "the parent's own @Vectorize field must not appear -- its type never opted in");
    }

    /**
     * A participating type with nothing in its fields still has <em>no</em> text, not empty text.
     *
     * <p>This is the distinction the return type is carrying: {@code ""} is a value, and a value is
     * something a provider will embed -- which is exactly the wasted call, and the space-shaped vector,
     * OMI-187 removed from the collection path.
     */
    @Test
    void aParticipantWithNoContentHasNoTextAndNoVector() {
        Node empty = new Node(null);

        assertNull(empty.concatenatedText(), "no content must be null, never \"\"");
        assertTrue(empty.concatenatedTextVector().isAbsent());
        assertEquals(0, provider.ledger().totalCalls(),
                "nothing to say must cost no embedding\n\n" + provider.ledger().report());
    }

    /** Likewise a collection whose members contribute nothing -- it has no text of its own to offer. */
    @Test
    void aCollectionWhoseMembersContributeNothingHasNoText() {
        Node root = new Node(null);
        root.members().add(new Node(null));

        assertNull(root.concatenatedText());
        assertTrue(root.concatenatedTextVector().isAbsent());
    }

    /** A child that never opted in contributes nothing, even when the parent asks for it. */
    @Test
    void aChildThatDidNotOptInContributesNothing() {
        Node parent = new Node("parent");
        parent.setPlainChild(new PlainNode("invisible"));

        assertEquals("text: parent\n", parent.concatenatedText());
    }

    // ---- assembly order ----

    @Test
    void assemblyIsParentFirstThenChildren() {
        Node root = new Node("root");
        Node child = new Node("child");
        child.setChild(new Node("grandchild"));
        root.setChild(child);

        assertEquals("text: root\ntext: child\ntext: grandchild\n", root.concatenatedText());
    }

    @Test
    void collectionMembersAreAggregatedInIterationOrder() {
        Node root = new Node("root");
        root.members().add(new Node("first"));
        root.members().add(new Node("second"));

        assertEquals("text: root\ntext: first\ntext: second\n", root.concatenatedText());
    }

    // ---- colouring: the deliberate divergence from summaryVector() ----

    /**
     * A diamond. {@code shared} is reachable through both {@code left} and {@code right}, and must appear
     * exactly once -- where {@code summaryVector()} would deliberately count it twice.
     */
    @Test
    void aNodeReachableTwiceContributesOnce() {
        Node root = new Node("root");
        Node left = new Node("left");
        Node right = new Node("right");
        Node shared = new Node("shared");
        left.setChild(shared);
        right.setChild(shared);
        root.members().add(left);
        root.members().add(right);

        String text = root.concatenatedText();

        assertEquals(1, occurrences(text, "text: shared"),
                "a diamond's shared node must contribute once, not once per path: " + text);
        assertEquals("text: root\ntext: left\ntext: shared\ntext: right\n", text);
    }

    /** A cycle terminates, and does so through the same colouring rather than a separate guard. */
    @Test
    void aCycleTerminatesAndEachNodeAppearsOnce() {
        Node a = new Node("a");
        Node b = new Node("b");
        a.setChild(b);
        b.setChild(a);

        String text = a.concatenatedText();

        assertEquals("text: a\ntext: b\n", text);
        assertEquals(1, occurrences(text, "text: a"));
    }

    /** Self-reference is the degenerate cycle, and must not recurse or duplicate. */
    @Test
    void aSelfReferencingNodeAppearsOnce() {
        Node node = new Node("solo");
        node.setChild(node);

        assertEquals("text: solo\n", node.concatenatedText());
    }

    /**
     * Colouring lasts for one assembly, not forever. A second call must produce the same text -- otherwise
     * the first read would poison every later one.
     */
    @Test
    void colouringDoesNotLeakBetweenAssemblies() {
        Node root = new Node("root");
        root.setChild(new Node("child"));

        String first = root.concatenatedText();
        String second = root.concatenatedText();

        assertEquals(first, second);
        assertEquals("text: root\ntext: child\n", second);
    }

    // ---- staleness ----

    /** A descendant's mutation must invalidate an ancestor's text -- the flag question OMI-187 got wrong. */
    @Test
    void mutatingADescendantRestalesTheAncestorsText() {
        Node root = new Node("root");
        Node child = new Node("before");
        root.setChild(child);
        assertEquals("text: root\ntext: before\n", root.concatenatedText());
        EmbeddingVector stale = root.concatenatedTextVector();

        child.setText("after");

        assertEquals("text: root\ntext: after\n", root.concatenatedText());
        // By value, never via EmbeddingVector.equals: it is a record over a float[], so its generated
        // equals compares array identity -- two distinct instances are never equal and the assertion would
        // pass whether or not anything was recomputed.
        assertFalse(java.util.Arrays.equals(stale.values(), root.concatenatedTextVector().values()),
                "the ancestor's vector must reflect the descendant's new text");
    }

    @Test
    void addingACollectionMemberRestalesTheOwnersText() {
        Node root = new Node("root");
        root.members().add(new Node("first"));
        assertEquals("text: root\ntext: first\n", root.concatenatedText());

        root.members().add(new Node("second"));

        assertEquals("text: root\ntext: first\ntext: second\n", root.concatenatedText());
    }

    // ---- cost ----

    /** One embedding for the whole assembled text, not one per contributing node. */
    @Test
    void anAssemblyCostsExactlyOneEmbedding() {
        Node root = new Node("root");
        root.setChild(new Node("child"));
        root.members().add(new Node("member"));

        root.concatenatedTextVector();

        assertEquals(1, provider.ledger().totalCalls(),
                "the point is one embedding of one assembled text\n\n" + provider.ledger().report());
    }

    @Test
    void aSecondReadIsServedFromCache() {
        Node root = new Node("root");
        root.concatenatedTextVector();
        int afterFirst = provider.ledger().totalCalls();

        root.concatenatedTextVector();

        assertEquals(afterFirst, provider.ledger().totalCalls(), "an unchanged graph must not re-embed");
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    // ---- fixtures: hand-written stand-ins for what the weaver generates, as elsewhere in this module ----

    /** Opts in on both axes: contributes its own fields, and absorbs its child and collection. */
    @Summary(concatenate = true)
    static final class Node implements JavAIVectorizable, JavAIDirtyTracking {

        @SuppressWarnings("unused")
        private DirtyTrackingSupport $javai$state;

        @Vectorize
        private String text;

        @Summary(concatenate = true)
        private Node child;

        /** A non-participating child, to prove the opt-in is per-type and not inherited from the parent. */
        @Summary(concatenate = true)
        private PlainNode plainChild;

        @Summary(concatenate = true)
        private final JavAIArrayList<Node> members = new JavAIArrayList<>();

        Node(String text) {
            this.text = text;
        }

        JavAIArrayList<Node> members() {
            return members;
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

        void setPlainChild(PlainNode plainChild) {
            this.plainChild = plainChild;
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
            return JavAIRuntime.summaryVector(this, "child,members", "text");
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

    /** Absorbs its child, but never contributes its own fields -- no type-level opt-in. */
    static final class AbsorbOnlyNode implements JavAIVectorizable, JavAIDirtyTracking {

        @SuppressWarnings("unused")
        private DirtyTrackingSupport $javai$state;

        @Vectorize
        private String text;

        @Summary(concatenate = true)
        private Node child;

        AbsorbOnlyNode(String text) {
            this.text = text;
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

    /** Never opts in -- the control for "declining is free". */
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
        public String concatenatedText() {
            return JavAIRuntime.concatenatedText(this, "text");
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
