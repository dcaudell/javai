package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.VectorMath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors {@link JavAIArrayListTest}'s coverage for the map-shaped concrete collection -- adapted to
 * {@code JavAIMap}'s actual surface: ranking/{@code centroid()} operate over *values*, not keys or
 * entries (see {@code JavAILinkedHashMap}'s own javadoc), and it has no {@code nearestN}/
 * {@code filterByMinSimilarity} (those aren't declared on {@code JavAIMap}, only {@code JavAIList}/
 * {@code JavAISet}).
 */
class JavAILinkedHashMapTest {

    @BeforeAll
    static void configureFakeProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void sortByCosineDistanceRanksValuesBySimilarityDescending() {
        TestNode a = new TestNode("alpha");
        TestNode b = new TestNode("beta");
        TestNode c = new TestNode("gamma");
        JavAILinkedHashMap<String, TestNode> map = new JavAILinkedHashMap<>();
        map.put("a", a);
        map.put("b", b);
        map.put("c", c);

        JavAIList<TestNode> ranked = map.sortByCosineDistance(a.vector());

        assertEquals(3, ranked.size());
        assertEquals(a, ranked.get(0), "the exact match for the reference vector should rank first");
    }

    @Test
    void centroidIsTheMeanOfValueVectors() {
        TestNode a = new TestNode("alpha");
        TestNode b = new TestNode("beta");
        JavAILinkedHashMap<String, TestNode> map = new JavAILinkedHashMap<>();
        map.put("a", a);
        map.put("b", b);

        EmbeddingVector expected = VectorMath.centroid(java.util.List.of(a.vector(), b.vector()));
        assertArrayEquals(expected.values(), map.centroid().values());
    }

    @Test
    void puttingAnEntryRegistersTheMapAsItsDependent() {
        TestNode a = new TestNode("alpha");
        JavAILinkedHashMap<String, TestNode> map = new JavAILinkedHashMap<>();
        map.put("a", a);
        map.summaryVector(); // clean after the put itself, to isolate the later mutation's effect below
        assertFalse(map.isSummaryDirty());

        a.setText("mutated");

        assertTrue(map.isSummaryDirty(), "mutating a value must dirty the map that holds it");
    }

    @Test
    void toContextRendersValuesNotKeysDelegatingToEachValuesOwnContextableOverride() {
        record CustomEntry(String label) implements Contextable {
            @Override
            public String toContext(PromptContext prompt) {
                return "custom:" + label;
            }
        }
        JavAILinkedHashMap<String, Contextable> map = new JavAILinkedHashMap<>();
        map.put("key-one", new CustomEntry("one"));
        map.put("key-two", new CustomEntry("two"));

        String rendered = map.toContext(PromptContext.builder().build());

        assertEquals("custom:one\n\ncustom:two", rendered);
        assertFalse(rendered.contains("key-one"), "keys must not appear -- only values are rendered");
    }

    @Test
    void toContextFallsBackToDefaultMarshallRespectingThePromptContextFieldFilter() {
        record PlainValue(@dev.xtrafe.javai.annotations.PromptContext String name, int count) {
        }
        JavAILinkedHashMap<String, Contextable> map = new JavAILinkedHashMap<>();
        map.put("key", new ContextableObject<>(new PlainValue("widgets", 3)));

        String rendered = map.toContext(PromptContext.builder().build());

        assertEquals("{\"name\":\"widgets\"}", rendered);
    }
}
