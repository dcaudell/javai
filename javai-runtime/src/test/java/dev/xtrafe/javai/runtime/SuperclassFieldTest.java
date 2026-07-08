package dev.xtrafe.javai.runtime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link JavAIRuntime}'s read-side reflection ({@code query()}, dependency wiring,
 * {@code summaryVector()}'s field reads) works across a class hierarchy, not just fields declared on the
 * exact runtime type -- the gap flagged and fixed in this session. Uses {@link InheritanceBaseNode} (has
 * the {@code child} field) and {@link InheritanceLeafNode} (extends it, adds {@code label}).
 */
class SuperclassFieldTest {

    @BeforeAll
    static void configureFakeProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void summaryVectorPropagatesThroughAFieldDeclaredOnTheSuperclass() {
        TestNode target = new TestNode("inherited target");
        InheritanceLeafNode leaf = new InheritanceLeafNode();
        leaf.setLabel("leaf label");
        leaf.setChild(target);

        EmbeddingVector before = leaf.summaryVector();
        assertFalse(leaf.isSummaryDirty());

        target.setText("mutated inherited target");
        assertTrue(leaf.isSummaryDirty(),
                "mutating a child reachable only via a superclass-declared field must still propagate");

        EmbeddingVector after = leaf.summaryVector();
        assertNotEquals(before, after);
    }

    @Test
    void queryFindsInstancesReachableThroughASuperclassDeclaredField() {
        TestNode target = new TestNode("queryable target");
        InheritanceLeafNode leaf = new InheritanceLeafNode();
        leaf.setLabel("leaf label");
        leaf.setChild(target);

        JavAIList<TestNode> found = leaf.query(target.vector(), TestNode.class);

        assertTrue(found.contains(target));
    }
}
