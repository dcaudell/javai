package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.VectorMath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirrors {@link JavAIArrayListTest}'s coverage for the set-shaped concrete collection. */
class JavAILinkedHashSetTest {

    @BeforeAll
    static void configureFakeProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void nearestNReturnsTopNBySimilarityDescending() {
        TestNode a = new TestNode("alpha");
        TestNode b = new TestNode("beta");
        TestNode c = new TestNode("gamma");
        JavAILinkedHashSet<TestNode> set = new JavAILinkedHashSet<>();
        set.add(a);
        set.add(b);
        set.add(c);

        EmbeddingVector reference = a.vector();
        JavAIList<TestNode> nearest = set.nearestN(reference, 2);

        assertEquals(2, nearest.size());
        assertEquals(a, nearest.get(0), "the exact match for the reference vector should rank first");
    }

    @Test
    void filterByMinSimilarityExcludesLowScoringElements() {
        TestNode a = new TestNode("alpha");
        TestNode b = new TestNode("beta");
        JavAILinkedHashSet<TestNode> set = new JavAILinkedHashSet<>();
        set.add(a);
        set.add(b);

        JavAIList<TestNode> onlyExactMatch = set.filterByMinSimilarity(a.vector(), 0.999);

        assertTrue(onlyExactMatch.contains(a));
    }

    @Test
    void centroidIsTheMeanOfElementVectors() {
        TestNode a = new TestNode("alpha");
        TestNode b = new TestNode("beta");
        JavAILinkedHashSet<TestNode> set = new JavAILinkedHashSet<>();
        set.add(a);
        set.add(b);

        EmbeddingVector expected = VectorMath.centroid(java.util.List.of(a.vector(), b.vector()));
        assertArrayEquals(expected.values(), set.centroid().values());
    }

    @Test
    void addingAnElementRegistersTheSetAsItsDependent() {
        TestNode a = new TestNode("alpha");
        JavAILinkedHashSet<TestNode> set = new JavAILinkedHashSet<>();
        set.add(a);
        set.summaryVector(); // clean after the add itself, to isolate the later mutation's effect below
        assertFalse(set.isSummaryDirty());

        a.setText("mutated");

        assertTrue(set.isSummaryDirty(), "mutating a member must dirty the set that holds it");
    }

    @Test
    void toContextDelegatesToAnElementsOwnContextableOverride() {
        record CustomEntry(String label) implements Contextable {
            @Override
            public String toContext(PromptContext prompt) {
                return "custom:" + label;
            }
        }
        JavAILinkedHashSet<Contextable> set = new JavAILinkedHashSet<>();
        set.add(new CustomEntry("one"));
        set.add(new CustomEntry("two"));

        String rendered = set.toContext(PromptContext.builder().build());

        assertEquals("custom:one\n\ncustom:two", rendered);
    }

    @Test
    void toContextFallsBackToDefaultMarshallRespectingThePromptContextFieldFilter() {
        record PlainValue(@dev.xtrafe.javai.annotations.PromptContext String name, int count) {
        }
        JavAILinkedHashSet<Contextable> set = new JavAILinkedHashSet<>();
        set.add(new ContextableObject<>(new PlainValue("widgets", 3)));

        String rendered = set.toContext(PromptContext.builder().build());

        assertEquals("{\"name\":\"widgets\"}", rendered);
    }
}
