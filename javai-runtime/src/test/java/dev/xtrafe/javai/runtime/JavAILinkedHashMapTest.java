package dev.xtrafe.javai.runtime;

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
}
