package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OMI-187, generalized: does the waste hold at "a small constant per field" no matter what object graph you
 * throw at it, or is it a function of graph <em>shape</em>?
 *
 * <h2>Why this exists</h2>
 *
 * The ticket characterized the defect entirely through one structure -- {@code Tag}/{@code TagSet}, one
 * owner with one {@code @Summary} list -- and concluded "3 embeds per tag, 2 wasted." That is a sound
 * measurement of that structure and a weak basis for a fix: a remedy tuned to one topology can easily be
 * the wrong remedy generally. So this sweeps deep chains, wide fan-out, diamonds (the same node reachable
 * by two paths), cycles, self-reference, collections nested inside collections, and every JavAI collection
 * type, and reports waste per shape.
 *
 * <p><b>No persistence anywhere in this class, deliberately.</b> OMI-187 surfaced during a save loop, which
 * made it easy to read as a persistence bug. Everything here runs on plain in-memory object graphs, so
 * whatever it finds is a property of the runtime's own walk.
 *
 * <h2>Reading the failure output</h2>
 *
 * The characterization test collects every shape before asserting, rather than failing on the first one, so
 * one run yields the whole table instead of one data point at a time.
 */
class ObjectGraphEmbeddingCharacterizationTest {

    private static final AtomicLong UNIQUE = new AtomicLong();
    private static final int READS_PER_SHAPE = 3;

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

    // ---- graph construction ----

    /**
     * Collects the {@code @Vectorize} value of every node it creates -- which is exactly the set of texts
     * that should be embedded -- and the reads the shape wants measured.
     */
    private static final class GraphBuilder {

        private final List<String> labels = new ArrayList<>();
        private final List<Runnable> reads = new ArrayList<>();

        GraphNode node(String name) {
            String label = name + "-" + UNIQUE.incrementAndGet();
            labels.add(label);
            return new GraphNode(name, label);
        }

        /** Declares the entry point a caller would actually touch; the runtime walks outward from here. */
        void readSummaryOf(GraphNode node) {
            reads.add(node::summaryVector);
        }
    }

    /** One measured shape: how many embeds it should cost, and how many it actually cost. */
    private record Measurement(String shape, int expected, int actual, int emptyStringCalls) {

        int wasted() {
            return actual - expected;
        }

        String row() {
            return String.format("  %-34s expected %4d   actual %5d   wasted %5d   of which \"\" %5d",
                    shape, expected, actual, wasted(), emptyStringCalls);
        }
    }

    private Measurement measure(String shape, Consumer<GraphBuilder> build) {
        GraphBuilder builder = new GraphBuilder();
        build.accept(builder);

        // Construction embeds nothing (every vector is lazy), but reset anyway so the measurement covers
        // only the reads and cannot silently absorb work done while wiring the graph up.
        provider.reset();
        for (int i = 0; i < READS_PER_SHAPE; i++) {
            builder.reads.forEach(Runnable::run);
        }
        return new Measurement(shape, builder.labels.size(), provider.ledger().totalCalls(),
                provider.ledger().countOf(""));
    }

    // ---- the shapes ----

    @Test
    void characterizeWasteAcrossEveryGraphShape() {
        List<Measurement> measurements = new ArrayList<>();

        measurements.add(measure("single node, no children", b -> {
            GraphNode only = b.node("solo");
            b.readSummaryOf(only);
        }));

        measurements.add(measure("deep chain via singular ref (10)", b -> {
            GraphNode root = b.node("chain-root");
            GraphNode current = root;
            for (int i = 0; i < 9; i++) {
                GraphNode next = b.node("chain-" + i);
                current.setSingular(next);
                current = next;
            }
            b.readSummaryOf(root);
        }));

        measurements.add(measure("wide fan-out via JavAIArrayList (50)", b -> {
            GraphNode root = b.node("list-root");
            for (int i = 0; i < 50; i++) {
                root.getList().add(b.node("list-child-" + i));
            }
            b.readSummaryOf(root);
        }));

        measurements.add(measure("wide fan-out via JavAILinkedHashSet (50)", b -> {
            GraphNode root = b.node("set-root");
            for (int i = 0; i < 50; i++) {
                root.getSet().add(b.node("set-child-" + i));
            }
            b.readSummaryOf(root);
        }));

        measurements.add(measure("wide fan-out via JavAILinkedHashMap (50)", b -> {
            GraphNode root = b.node("map-root");
            for (int i = 0; i < 50; i++) {
                root.getMap().put("k" + i, b.node("map-child-" + i));
            }
            b.readSummaryOf(root);
        }));

        measurements.add(measure("all three collections on one node", b -> {
            GraphNode root = b.node("mixed-root");
            for (int i = 0; i < 5; i++) {
                root.getList().add(b.node("mixed-list-" + i));
                root.getSet().add(b.node("mixed-set-" + i));
                root.getMap().put("k" + i, b.node("mixed-map-" + i));
            }
            b.readSummaryOf(root);
        }));

        measurements.add(measure("diamond (shared child, two paths)", b -> {
            GraphNode root = b.node("diamond-root");
            GraphNode left = b.node("diamond-left");
            GraphNode right = b.node("diamond-right");
            GraphNode shared = b.node("diamond-shared");
            root.getList().add(left);
            root.getList().add(right);
            left.setSingular(shared);
            right.setSingular(shared);
            b.readSummaryOf(root);
        }));

        measurements.add(measure("cycle a -> b -> c -> a", b -> {
            GraphNode a = b.node("cycle-a");
            GraphNode c2 = b.node("cycle-b");
            GraphNode c3 = b.node("cycle-c");
            a.setSingular(c2);
            c2.setSingular(c3);
            c3.setSingular(a);
            b.readSummaryOf(a);
        }));

        measurements.add(measure("self reference", b -> {
            GraphNode a = b.node("self");
            a.setSingular(a);
            b.readSummaryOf(a);
        }));

        measurements.add(measure("collection nested in collection", b -> {
            GraphNode root = b.node("nested-root");
            JavAIArrayList<JavAIVectorizable> inner = new JavAIArrayList<>();
            for (int i = 0; i < 5; i++) {
                inner.add(b.node("nested-child-" + i));
            }
            root.getList().add(inner);
            b.readSummaryOf(root);
        }));

        measurements.add(measure("balanced tree, depth 3, branching 3", b -> {
            GraphNode root = b.node("tree-root");
            for (int i = 0; i < 3; i++) {
                GraphNode child = b.node("tree-1-" + i);
                root.getList().add(child);
                for (int j = 0; j < 3; j++) {
                    GraphNode grandchild = b.node("tree-2-" + i + "-" + j);
                    child.getSet().add(grandchild);
                    for (int k = 0; k < 3; k++) {
                        grandchild.getMap().put("k" + k, b.node("tree-3-" + i + "-" + j + "-" + k));
                    }
                }
            }
            b.readSummaryOf(root);
        }));

        measurements.add(measure("chain of 5, every node's collections empty", b -> {
            GraphNode root = b.node("empty-root");
            GraphNode current = root;
            for (int i = 0; i < 4; i++) {
                GraphNode next = b.node("empty-" + i);
                current.setSingular(next);
                current = next;
            }
            b.readSummaryOf(root);
        }));

        int totalWaste = measurements.stream().mapToInt(Measurement::wasted).sum();
        if (totalWaste == 0) {
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("Embedding waste is not zero, and is not a fixed constant per field (OMI-187).\n\n")
                .append("Each shape read ").append(READS_PER_SHAPE)
                .append("x; correct cost is one embed per distinct @Vectorize value, regardless of read count.\n\n");
        for (Measurement measurement : measurements) {
            message.append(measurement.row()).append('\n');
        }
        message.append("\n  TOTAL WASTED: ").append(totalWaste).append('\n');
        throw new AssertionError(message.toString());
    }

    /**
     * Walk direction. The decay-weighted summary formula is only computable from each child's
     * already-cached {@code summaryVector()}, so the recursion is necessarily leaf-first -- a root-first
     * walk could not produce a correct answer at all, which makes this a correctness property rather than a
     * preference.
     *
     * <p>What is worth checking is whether the implementation does <em>redundant</em> work depending on the
     * order a caller happens to touch the graph in. Reading every leaf before the root, versus reading only
     * the root and letting the recursion reach the leaves, must cost the same: identical embeddings, just
     * issued in a different order. If the leaf-first pass is cheaper, the recursion is losing cached work.
     */
    @Test
    void walkOrderDoesNotChangeEmbeddingCost() {
        int rootFirst = costOfBalancedTree(false);
        int leafFirst = costOfBalancedTree(true);

        assertEquals(leafFirst, rootFirst,
                "reading leaves before the root must cost exactly what reading the root alone costs; "
                        + "a difference means the summary recursion recomputes work a direct read already did");
    }

    private int costOfBalancedTree(boolean readLeavesFirst) {
        GraphBuilder builder = new GraphBuilder();
        GraphNode root = builder.node("order-root");
        List<GraphNode> leaves = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            GraphNode child = builder.node("order-child-" + i);
            root.getList().add(child);
            for (int j = 0; j < 4; j++) {
                GraphNode leaf = builder.node("order-leaf-" + i + "-" + j);
                child.getSet().add(leaf);
                leaves.add(leaf);
            }
        }

        provider.reset();
        if (readLeavesFirst) {
            leaves.forEach(GraphNode::vector);
        }
        root.summaryVector();
        return provider.ledger().totalCalls();
    }

    /**
     * Defect B's mechanism, isolated from any database: vector caches hang off a woven <em>instance</em>
     * field via {@code JavAIRuntime.stateOf}, so a second instance of the same logical entity starts with
     * an empty cache and re-embeds a value the first one already computed.
     *
     * <p>This is asserted as the runtime's <b>documented behaviour</b>, not as a defect awaiting a fix.
     * Two live instances of one logical object are not something the runtime can deduplicate on its own --
     * it has no notion of logical identity in memory, only object identity, and inventing a process-wide
     * text cache would change {@code JavAIEmbeddingProvider.embed}'s {@code @Nondeterministic} contract.
     * What matters in practice is that instance replacement comes from <em>persistence</em>, and there the
     * vectors are on disk already: see {@link JavAIRuntime#hydrateFieldVector}, which serves them straight
     * back into the new instance's slots, and {@code TagSetSeedEmbeddingCostTest}, which pins the resulting
     * cost at exactly one embedding per tag.
     */
    @Test
    void aSecondInstanceOfTheSameLogicalObjectEmbedsAgainUnlessHydrated() {
        String label = "identity-probe-" + UNIQUE.incrementAndGet();

        GraphNode original = new GraphNode("original", label);
        original.vector();
        assertEquals(1, provider.ledger().totalCalls());

        // Same logical entity, same field value, different object -- what merge()/findById() hands back.
        new GraphNode("replacement", label).vector();
        assertEquals(2, provider.ledger().totalCalls(),
                "in-memory, a fresh instance has a fresh cache and genuinely does re-embed");

        // Persistence closes exactly this gap by handing the stored vector back instead of recomputing.
        GraphNode hydrated = new GraphNode("hydrated", label);
        JavAIRuntime.hydrateFieldVector(hydrated, "label", original.vector());
        hydrated.vector();

        assertEquals(2, provider.ledger().totalCalls(),
                "a hydrated slot must serve the stored vector rather than embed again\n\n"
                        + provider.ledger().report());
    }

    /**
     * The guard that makes eager hydration safe: a slot a setter has touched is never overwritten by a
     * stored vector. Hydration is keyed on the slot still sitting at its pristine generation, and every
     * {@code vectorizeFieldMutated} bumps that -- so "load, change a field, save" embeds the new value
     * rather than silently re-serving the old one.
     */
    @Test
    void hydrationIsDeclinedForASlotAMutationHasTouched() {
        String stored = "stored-value-" + UNIQUE.incrementAndGet();
        String mutated = "mutated-value-" + UNIQUE.incrementAndGet();

        GraphNode source = new GraphNode("source", stored);
        EmbeddingVector storedVector = source.vector();
        provider.reset();

        GraphNode loaded = new GraphNode("loaded", stored);
        loaded.setLabel(mutated);                                   // a real, JavAI-visible change
        JavAIRuntime.hydrateFieldVector(loaded, "label", storedVector);

        loaded.vector();
        provider.ledger().assertEmbeddedExactlyOnce(mutated);
    }
}
