package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JavAIRuntime#precomputeVectors}, OMI-187's bulk-seed path.
 *
 * <p>The ticket asked for two things: stop wasting embedding calls, and provide a path that seeds a large
 * reference set in reasonable time. The first was a correctness-shaped problem and is fixed elsewhere. This
 * is the second, and it is a <em>latency</em> problem that no amount of eliminating waste solves: lazy
 * computation discovers one text at a time, deep inside a read, so 1,400 tags means 1,400 sequential HTTP
 * round trips even when every one of them is necessary. Gathering the texts first is the only way to hand a
 * provider more than one at a time.
 *
 * <p>So these tests assert on <b>provider call count</b>, not embedding count -- the two come apart here,
 * which is the whole point.
 */
class PrecomputeVectorsTest {

    private static final AtomicLong UNIQUE = new AtomicLong();

    private RecordingEmbeddingProvider provider;

    @BeforeEach
    void installRecordingProvider() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        provider = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    private static String unique(String label) {
        return label + "-" + UNIQUE.incrementAndGet();
    }

    /** Counts batches as well as texts, which a plain ledger cannot distinguish. */
    private static final class BatchCountingProvider implements JavAIEmbeddingProvider {

        private final FakeEmbeddingProvider delegate = new FakeEmbeddingProvider();
        private final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public EmbeddingVector embed(String text) {
            batchSizes.add(1);
            return delegate.embed(text);
        }

        @Override
        public List<EmbeddingVector> embedAll(List<String> texts) {
            batchSizes.add(texts.size());
            List<EmbeddingVector> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(delegate.embed(text));
            }
            return vectors;
        }

        @Override
        public String modelId() {
            return FakeEmbeddingProvider.MODEL_ID;
        }
    }

    @Test
    void precomputingWarmsEveryFieldSoLaterReadsCostNothing() {
        List<TestNode> nodes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String text = unique("precompute");
            labels.add(text);
            nodes.add(new TestNode(text));
        }

        JavAIRuntime.precomputeVectors(nodes);
        provider.ledger().assertEmbeddedExactlyOnce(labels);

        // Every subsequent read is served from the warmed slot.
        provider.reset();
        nodes.forEach(TestNode::vector);
        assertEquals(0, provider.ledger().totalCalls(),
                "reads after precompute must be free\n\n" + provider.ledger().report());
    }

    @Test
    void precomputingCollapsesManyRoundTripsIntoFewBatches() {
        BatchCountingProvider counting = new BatchCountingProvider();
        JavAIRuntime.configureEmbeddingProvider(counting);

        List<TestNode> nodes = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            nodes.add(new TestNode(unique("batched")));
        }

        JavAIRuntime.precomputeVectors(nodes, 100);

        assertEquals(3, counting.batchSizes.size(),
                "250 texts at a batch size of 100 is three provider calls, not 250: " + counting.batchSizes);
        assertEquals(250, counting.batchSizes.stream().mapToInt(Integer::intValue).sum(),
                "every text must still be embedded exactly once");
    }

    /**
     * A value shared by several objects is one embedding, not one per holder -- de-duplication is possible
     * precisely because gathering happens before computing, which is another thing the lazy path cannot do.
     */
    @Test
    void aTextSharedByManyObjectsIsEmbeddedOnce() {
        String shared = unique("shared-value");
        List<TestNode> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            nodes.add(new TestNode(shared));
        }

        JavAIRuntime.precomputeVectors(nodes);

        assertEquals(1, provider.ledger().totalCalls(),
                "ten objects holding the same text need one embedding between them\n\n"
                        + provider.ledger().report());
        // ...and all ten are warmed by it, not just the first.
        provider.reset();
        nodes.forEach(TestNode::vector);
        assertEquals(0, provider.ledger().totalCalls(),
                "every holder of the shared text must be warmed, not only the one that requested it");
    }

    @Test
    void precomputingIsSafelyRepeatableAndSkipsAlreadyWarmFields() {
        List<TestNode> nodes = List.of(new TestNode(unique("repeat")), new TestNode(unique("repeat")));

        JavAIRuntime.precomputeVectors(nodes);
        int afterFirst = provider.ledger().totalCalls();
        assertTrue(afterFirst > 0);

        JavAIRuntime.precomputeVectors(nodes);
        assertEquals(afterFirst, provider.ledger().totalCalls(),
                "a second precompute over already-warm objects must cost nothing");
    }

    @Test
    void aMutatedFieldIsRecomputedByTheNextPrecompute() {
        TestNode node = new TestNode(unique("before"));
        JavAIRuntime.precomputeVectors(List.of(node));
        provider.reset();

        String mutated = unique("after");
        node.setText(mutated);
        JavAIRuntime.precomputeVectors(List.of(node));

        provider.ledger().assertEmbeddedExactlyOnce(mutated);
    }
}
