package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.testsupport.EmbeddingLedger;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-187's permanent guard, at the lowest level it can be stated: <b>reading a vector must cost exactly
 * one {@code embed()} call per {@code @Vectorize} field value, ever</b> -- no matter how many times it is
 * read, and with nothing else embedded at all.
 *
 * <h2>Why these tests exist in this form</h2>
 *
 * OMI-187 was diagnosed twice from source alone, and both diagnoses were wrong -- first "sibling tags are
 * re-embedded, so it's O(N^2)", then "embedding is O(N), the cost is per-save overhead." What settled it
 * was wrapping the provider and counting: 3 calls per tag where 1 was correct. So these tests assert on
 * {@link RecordingEmbeddingProvider}'s ledger rather than on dirty flags or cache slots. JavAI's internal
 * state can agree with a buggy implementation; the provider call count cannot.
 *
 * <p>Every fixture value is globally unique ({@link #unique}), which is what lets a frequency map over
 * embedded text identify (object, field) unambiguously -- see {@link EmbeddingLedger}'s own javadoc.
 *
 * <p>This class covers the pure in-memory path deliberately, with no persistence anywhere in it. OMI-187
 * surfaced during persistence, but nothing in the mechanism is persistence-specific: {@code vector()} is
 * documented as uncached at the aggregate level and leans entirely on each field's own
 * {@code VectorCacheSlot}, so any path that loses those slots re-embeds. Proving the invariant here first
 * separates "the runtime over-embeds" from "persistence makes the runtime over-embed."
 */
class EmbeddingCallInvariantTest {

    /** Distinguishes every fixture value across every test in the JVM, so no two can alias in the ledger. */
    private static final AtomicLong UNIQUE = new AtomicLong();

    private RecordingEmbeddingProvider provider;
    private EmbeddingConsistencyMode originalMode;

    private static String unique(String label) {
        return label + "-" + UNIQUE.incrementAndGet();
    }

    @BeforeEach
    void installRecordingProvider() {
        originalMode = JavAIRuntime.consistencyMode();
        // IMMEDIATE is both the JavAIRuntime default and what omiai-platform was running when OMI-187 was
        // reported. EmbeddingCallInvariantConsistencyModeTest covers the other two.
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        provider = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    @AfterEach
    void restoreMode() {
        JavAIRuntime.configureConsistencyMode(originalMode);
    }

    @Test
    void repeatedVectorReadsEmbedTheFieldExactlyOnce() {
        String text = unique("solitary-node");
        TestNode node = new TestNode(text);

        for (int i = 0; i < 10; i++) {
            node.vector();
        }

        provider.ledger().assertEmbeddedExactlyOnce(text);
    }

    @Test
    void repeatedFieldVectorReadsEmbedTheFieldExactlyOnce() {
        String text = unique("field-read-node");
        TestNode node = new TestNode(text);

        for (int i = 0; i < 10; i++) {
            node.fieldVector("text");
        }

        provider.ledger().assertEmbeddedExactlyOnce(text);
    }

    @Test
    void mutatingAFieldReEmbedsOnlyThatFieldsNewValue() {
        String original = unique("before-mutation");
        String mutated = unique("after-mutation");
        TestNode node = new TestNode(original);
        node.vector();

        node.setText(mutated);
        node.vector();
        node.vector();

        // Both values are legitimate: the field genuinely held each one in turn. What the invariant forbids
        // is either being embedded twice.
        provider.ledger().assertEmbeddedExactlyOnce(original, mutated);
    }

    @Test
    void containerSummaryReadEmbedsEachReachableFieldExactlyOnce() {
        String containerLabel = unique("container-label");
        String featuredText = unique("featured-node");
        List<String> itemTexts = new ArrayList<>();

        TestContainer container = new TestContainer(containerLabel);
        container.setFeatured(new TestNode(featuredText));
        for (int i = 0; i < 5; i++) {
            String itemText = unique("item");
            itemTexts.add(itemText);
            container.getItems().add(new TestNode(itemText));
        }

        for (int i = 0; i < 5; i++) {
            container.summaryVector();
        }

        List<String> expected = new ArrayList<>(itemTexts);
        expected.add(containerLabel);
        expected.add(featuredText);
        provider.ledger().assertEmbeddedExactlyOnce(expected);
    }

    /**
     * The shape OMI-187 actually hit, expressed without persistence: children are added to an owner's
     * {@code @Summary} collection one at a time, and the owner's summary is read after each add -- exactly
     * what a per-child {@code save()} loop does when the owner's vectors are rewritten on every insert.
     *
     * <p>Each add correctly dirties the owner's summary, and recomputing that summary is documented as
     * pure arithmetic over already-cached child vectors. So the owner's own {@code @Vectorize} field must
     * be embedded once for the whole loop, not once per child.
     */
    @Test
    void addingChildrenOneAtATimeNeverReEmbedsTheOwnersOwnField() {
        String ownerLabel = unique("owner-label");
        TestContainer owner = new TestContainer(ownerLabel);
        List<String> expected = new ArrayList<>();
        expected.add(ownerLabel);

        for (int i = 0; i < 12; i++) {
            String childText = unique("child");
            expected.add(childText);
            owner.getItems().add(new TestNode(childText));
            owner.summaryVector();
        }

        provider.ledger().assertEmbeddedExactlyOnce(expected);
        assertEquals(13, provider.ledger().totalCalls(),
                "12 children plus the owner's own label is the entire correct cost of this loop");
    }

    /**
     * An empty collection must cost <b>nothing</b>, not "one embedding, cached."
     *
     * <p>This assertion was originally written as "at most once," on the assumption that the fix would be
     * to memoize {@code embed("")} -- the empty-text vector being a per-model constant. That was the wrong
     * fix. A collection with no content has no vector, and the right answer is
     * {@link dev.xtrafe.javai.vector.EmbeddingVector#absent()}: zero provider calls, no cache to invalidate,
     * and -- the part memoizing would have preserved forever -- no arbitrary direction contributed to any
     * ancestor's summary. Real providers reject a genuinely empty input, so
     * {@code EmbeddingProviderOllama}/{@code OpenAI} substitute a single space; the old fallback therefore
     * embedded <em>a space</em> and folded that into every containing object's {@code summaryVector()}.
     *
     * <p>Zero is the assertion precisely because it is the one number that cannot be satisfied by a
     * cleverer cache.
     */
    @Test
    void anEmptyCollectionCostsNoEmbeddingsAtAll() {
        JavAIArrayList<TestNode> empty = new JavAIArrayList<>();

        for (int i = 0; i < 10; i++) {
            empty.vector();
        }

        assertEquals(0, provider.ledger().totalCalls(),
                "an empty collection has no content and must not call the provider at all\n\n"
                        + provider.ledger().report());
        assertTrue(empty.vector().isAbsent(), "an empty collection's vector is absent, not fabricated");
    }

    /**
     * The same empty collection reached through its owner's {@code summaryVector()} -- the path a
     * {@code TagSet} whose {@code tags} list is empty actually takes. The owner's own field is embedded
     * once; its empty {@code @Summary} collection adds nothing.
     */
    @Test
    void anOwnerWithAnEmptySummaryCollectionCostsOnlyItsOwnField() {
        String ownerLabel = unique("empty-owner");
        TestContainer owner = new TestContainer(ownerLabel);

        for (int i = 0; i < 10; i++) {
            owner.summaryVector();
        }

        provider.ledger().assertEmbeddedExactlyOnce(ownerLabel);
    }

    /**
     * The distortion, stated as a value rather than a call count: an empty {@code @Summary} collection must
     * leave its owner's summary vector exactly where it would be with no collection at all. Before
     * OMI-187 this held only by accident of the fake provider hashing {@code ""} to the zero vector -- against
     * a real model the empty collection contributed {@code decay * normalize(embedding_of_a_space)}, and
     * every ancestor drifted toward a content-free direction in proportion to how many empty collections it
     * happened to contain.
     */
    @Test
    void anEmptySummaryCollectionDoesNotMoveItsOwnersSummaryVector() {
        String sharedLabel = unique("identical-content");

        TestNode withoutCollections = new TestNode(sharedLabel);
        TestContainer withEmptyCollection = new TestContainer(sharedLabel);

        assertArrayEquals(withoutCollections.summaryVector().values(),
                withEmptyCollection.summaryVector().values(), 1e-6f,
                "an empty @Summary collection contributes no content and must contribute no direction");
    }
}
