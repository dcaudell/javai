package dev.xtrafe.javai.model;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code @SearchVisibility(PRIVATE)} gates two distinct things in {@code JavAIRuntime.query()},
 * per doc/spec/vector-core.md's "search-semantic visibility -- independent of Java access modifiers":
 * a field-level annotation blocks traversal through that field entirely, while a type-level annotation
 * only blocks a candidate from being returned as a match, leaving its own descendants reachable.
 */
class SearchVisibilityTest {

    @BeforeAll
    static void configureFakeProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void fieldLevelPrivateBlocksTraversalEntirely() {
        TestNode hidden = new TestNode("hidden");
        TestNode visible = new TestNode("visible");
        FieldVisibilityContainer container = new FieldVisibilityContainer();
        container.setHiddenChild(hidden);
        container.setVisibleChild(visible);

        JavAIList<TestNode> found = container.query(visible.vector(), TestNode.class);

        assertTrue(found.contains(visible));
        assertFalse(found.contains(hidden), "a @SearchVisibility(PRIVATE) field must not be traversed at all");
    }

    @Test
    void typeLevelPrivateBlocksMatchingButNotTraversal() {
        TestNode grandchild = new TestNode("grandchild");
        InvisibleTypeNode invisibleMiddle = new InvisibleTypeNode();
        invisibleMiddle.setChild(grandchild);
        GenericHolder holder = new GenericHolder();
        holder.setItem(invisibleMiddle);

        JavAIList<TestNode> foundGrandchild = holder.query(grandchild.vector(), TestNode.class);
        assertTrue(foundGrandchild.contains(grandchild),
                "traversal must continue through a type-level-@SearchVisibility(PRIVATE) node");

        JavAIList<InvisibleTypeNode> foundMiddle = holder.query(grandchild.vector(), InvisibleTypeNode.class);
        assertTrue(foundMiddle.isEmpty(), "a @SearchVisibility(PRIVATE) type must never itself be a match");
    }
}
