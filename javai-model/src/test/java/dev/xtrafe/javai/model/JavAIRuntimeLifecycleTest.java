package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        // vector() is now compositional -- it combines each @Vectorize field's own cached VectorCacheSlot
        // (see JavAIRuntime.fieldVector) rather than caching one single result of its own, so a repeat read
        // recombines fresh every time (cheap, in-memory) rather than returning a literal cached instance.
        // FieldDirty itself is no longer vector()'s concern at all -- it's now purely summaryVector()'s
        // staleness signal for its own base term (see JavAIRuntime.summaryVector's javadoc) -- so this test
        // asserts on the vector's actual values, not on FieldDirty transitions around vector() calls.
        TestNode node = new TestNode("first");

        EmbeddingVector first = node.vector();
        EmbeddingVector reread = node.vector();
        assertArrayEquals(first.values(), reread.values(), 1e-6f,
                "a repeat read with no mutation in between must recompute to the same values");

        node.setText("second, different text");
        EmbeddingVector second = node.vector();
        assertFalse(java.util.Arrays.equals(first.values(), second.values()),
                "a changed field must produce a different vector");
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
