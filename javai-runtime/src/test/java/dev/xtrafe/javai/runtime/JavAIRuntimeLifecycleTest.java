package dev.xtrafe.javai.runtime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link JavAIRuntime}'s lifecycle and back-edge propagation -- the doc/spec/vector-core.md
 * object lifecycle state machine, plus both containment shapes (single reference and collection) that
 * the load-time weaving spike deliberately didn't cover -- against the hand-written {@link TestNode}/
 * {@link TestContainer} stand-ins and a {@link FakeEmbeddingProvider}, with no ByteBuddy/Docker
 * dependency at all.
 */
class JavAIRuntimeLifecycleTest {

    @BeforeAll
    static void configureFakeProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void vectorRecomputesLazilyOnlyOnNextRead() {
        TestNode node = new TestNode("first");

        assertTrue(node.isFieldDirty(), "never-computed object starts dirty");
        EmbeddingVector first = node.vector();
        assertFalse(node.isFieldDirty(), "reading vector() clears FieldDirty");

        EmbeddingVector reread = node.vector();
        assertEquals(first, reread, "a repeat clean read must return the cached vector, not recompute");

        node.setText("second, different text");
        assertTrue(node.isFieldDirty(), "mutating setText marks FieldDirty");

        EmbeddingVector second = node.vector();
        assertFalse(node.isFieldDirty());
        assertNotEquals(first, second, "a changed field must produce a different vector");
    }

    @Test
    void summaryVectorPropagatesThroughASingleReference() {
        TestNode featured = new TestNode("original featured text");
        TestContainer container = new TestContainer("container label");
        container.setFeatured(featured);

        EmbeddingVector before = container.summaryVector();
        assertFalse(container.isSummaryDirty());

        featured.setText("mutated featured text");
        assertTrue(container.isSummaryDirty(),
                "mutating the referenced object must mark the container SummaryDirty via propagateDirty");

        EmbeddingVector after = container.summaryVector();
        assertFalse(container.isSummaryDirty());
        assertNotEquals(before, after, "summaryVector() must reflect the mutated reference");
    }

    @Test
    void summaryVectorPropagatesThroughACollectionElement() {
        TestNode itemA = new TestNode("item a");
        TestNode itemB = new TestNode("item b");
        TestContainer container = new TestContainer("container label");
        container.getItems().add(itemA);
        container.getItems().add(itemB);

        EmbeddingVector before = container.summaryVector();
        assertFalse(container.isSummaryDirty());

        itemB.setText("item b, mutated");
        assertTrue(container.getItems().isSummaryDirty(),
                "mutating a contained element must mark the list itself SummaryDirty");
        assertTrue(container.isSummaryDirty(),
                "...and that must cascade up to the container that holds the list");

        EmbeddingVector after = container.summaryVector();
        assertNotEquals(before, after, "summaryVector() must reflect the mutated collection element");
    }

    @Test
    void duplicateReferencesInACollectionStackAdditively() {
        TestNode shared = new TestNode("shared text");

        TestContainer once = new TestContainer("label");
        once.getItems().add(shared);

        TestContainer twice = new TestContainer("label");
        twice.getItems().add(shared);
        twice.getItems().add(shared);

        assertNotEquals(once.summaryVector(), twice.summaryVector(),
                "a duplicate reference must contribute again, not be deduplicated");
    }

    @Test
    void propagateDirtyTerminatesAndMarksBothSidesOfACycle() {
        CyclicNode a = new CyclicNode("a");
        CyclicNode b = new CyclicNode("b");
        a.setNext(b);
        b.setNext(a);

        // Read once so both start Clean -- otherwise the "already dirty" cycle-safety check would trip
        // on the never-computed default and prove nothing about cycle handling specifically.
        a.summaryVector();
        b.summaryVector();
        assertFalse(a.isSummaryDirty());
        assertFalse(b.isSummaryDirty());

        a.setLabel("a, mutated");

        // If propagateDirty didn't stop at an already-dirty node, this call above would never return.
        assertTrue(a.isSummaryDirty(), "propagation must walk the cycle back around to a itself");
        assertTrue(b.isSummaryDirty(), "propagation must reach b, the direct dependent of a");
    }
}
