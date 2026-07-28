package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Completes OMI-187's collection coverage. {@code ObjectGraphEmbeddingCharacterizationTest} (javai-model)
 * sweeps {@code JavAIArrayList}/{@code JavAILinkedHashSet}/{@code JavAILinkedHashMap}; the two collection
 * types that live in <em>this</em> module are checked here, and they turn out to sit on opposite sides of
 * the defect.
 *
 * <ul>
 *   <li>{@link JavAIKnowledgeGraph} computes {@code vector()}/{@code summaryVector()} through the same
 *       {@code CollectionVectorSupport} centroid path as every other JavAI collection
 *       ({@code JavAIKnowledgeGraph.java:145-152}), over its own {@code nodes} set. So it inherits the
 *       empty-collection {@code embed("")} behaviour exactly.</li>
 *   <li>{@link VectorIndex} is <b>not</b> {@code JavAIVectorizable} at all -- it exposes only similarity
 *       queries, never a {@code vector()} of its own -- so it has no centroid to compute and cannot reach
 *       the defect. Asserted rather than assumed, since "this type is structurally immune" is exactly the
 *       kind of claim that silently stops being true.</li>
 * </ul>
 *
 * <p>No persistence here either -- same reasoning as the javai-model sweep.
 */
class KnowledgeGraphEmbeddingCostTest {

    private static final AtomicLong UNIQUE = new AtomicLong();

    private RecordingEmbeddingProvider provider;
    private EmbeddingConsistencyMode originalMode;

    @BeforeEach
    void installRecordingProvider() {
        originalMode = JavAIRuntime.consistencyMode();
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        provider = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    @AfterEach
    void restoreMode() {
        JavAIRuntime.configureConsistencyMode(originalMode);
    }

    private static TestGraphNode node(String name, List<String> labels) {
        String text = name + "-" + UNIQUE.incrementAndGet();
        labels.add(text);
        return new TestGraphNode(name, text);
    }

    @Test
    void populatedKnowledgeGraphEmbedsEachNodeExactlyOnce() {
        List<String> labels = new ArrayList<>();
        JavAIKnowledgeGraph<TestGraphNode, TestGraphEdge> graph = new JavAIKnowledgeGraph<>();
        List<TestGraphNode> nodes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            TestGraphNode created = node("kg-node-" + i, labels);
            nodes.add(created);
            graph.addNode(created);
        }
        for (int i = 0; i < nodes.size() - 1; i++) {
            graph.addEdge(nodes.get(i), nodes.get(i + 1), new TestGraphEdge("next"));
        }

        provider.reset();
        for (int i = 0; i < 5; i++) {
            graph.summaryVector();
            graph.vector();
        }

        provider.ledger().assertEmbeddedExactlyOnce(labels);
    }

    /**
     * The empty case, which is where OMI-187 actually bit: a graph with no nodes has no vectorizable
     * content, so it has no vector -- {@link dev.xtrafe.javai.vector.EmbeddingVector#absent()}, costing
     * nothing.
     *
     * <p>This was first written as "at most once," when the intended fix was to memoize {@code embed("")}
     * as a per-model constant. Zero is the stronger and correct assertion: it is the one number a cleverer
     * cache cannot satisfy, and it also rules out the distortion memoizing would have preserved -- real
     * providers substitute a space for an empty input, so the old fallback folded the embedding of a space
     * into every containing object's summary.
     */
    @Test
    void anEmptyKnowledgeGraphCostsNoEmbeddingsAtAll() {
        JavAIKnowledgeGraph<TestGraphNode, TestGraphEdge> empty = new JavAIKnowledgeGraph<>();

        for (int i = 0; i < 10; i++) {
            empty.vector();
        }

        assertEquals(0, provider.ledger().totalCalls(),
                "an empty graph has no content and must not call the provider at all\n\n"
                        + provider.ledger().report());
        assertTrue(empty.vector().isAbsent(), "an empty graph's vector is absent, not fabricated");
    }

    /**
     * {@link VectorIndex} has no {@code vector()} of its own, so nothing it does can reach the
     * empty-centroid fallback: querying an index costs embeddings only for the items in it, and an empty
     * index costs none at all.
     */
    @Test
    void vectorIndexNeverEmbedsOnItsOwnBehalf() {
        List<String> labels = new ArrayList<>();
        VectorIndex<TestVectorNode> index = new JavAIVectorIndex<>();

        VectorIndex<TestVectorNode> emptyIndex = new JavAIVectorIndex<>();
        TestVectorNode probe = new TestVectorNode("probe-" + UNIQUE.incrementAndGet());
        provider.reset();
        emptyIndex.nearestN(probe.vector(), 5);
        assertEquals(0, provider.ledger().countOf(""),
                "an empty VectorIndex has no centroid to compute and must never embed \"\"\n\n"
                        + provider.ledger().report());

        for (int i = 0; i < 6; i++) {
            String text = "vi-item-" + UNIQUE.incrementAndGet();
            labels.add(text);
            index.add(new TestVectorNode(text));
        }

        provider.reset();
        for (int i = 0; i < 5; i++) {
            index.nearestN(probe.vector(), 3);
            index.sortByCosineDistance(probe.vector());
        }

        provider.ledger().assertEmbeddedExactlyOnce(labels);
    }
}
