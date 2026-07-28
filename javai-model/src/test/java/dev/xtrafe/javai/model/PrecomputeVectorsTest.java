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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
     * A batched call takes exactly one permit from the concurrency gate (OMI-213).
     *
     * <p>Two things are being pinned. First, that a permit is taken <b>at all</b>: this path previously called
     * the provider without touching the gate, so a bulk seed ran entirely outside the bound {@link
     * JavAIRuntime#configureMaxConcurrentEmbeddingCalls} documents as applying to every provider call in every
     * consistency mode.
     *
     * <p>Second, that it is <b>one</b> permit rather than one per text. The gate bounds concurrent calls into
     * the provider, and a batch is one call on one connection however many texts it carries. Charging per text
     * would make the gate throttle batching itself -- and a batch larger than the gate could never acquire
     * enough permits to proceed at all, which the next test covers.
     */
    @Test
    void aBatchedCallCostsExactlyOnePermitFromTheConcurrencyGate() {
        List<Integer> permitsDuringCall = new ArrayList<>();
        JavAIRuntime.configureMaxConcurrentEmbeddingCalls(4);
        try {
            JavAIRuntime.configureEmbeddingProvider(new JavAIEmbeddingProvider() {
                private final FakeEmbeddingProvider delegate = new FakeEmbeddingProvider();

                @Override
                public EmbeddingVector embed(String text) {
                    return delegate.embed(text);
                }

                @Override
                public List<EmbeddingVector> embedAll(List<String> texts) {
                    permitsDuringCall.add(JavAIRuntime.embeddingCallGate().availablePermits());
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
            });

            List<TestNode> nodes = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                nodes.add(new TestNode(unique("gated")));
            }
            JavAIRuntime.precomputeVectors(nodes, 100);

            assertEquals(List.of(3), permitsDuringCall,
                    "one batched call must hold exactly one of the four permits while it runs");
            assertEquals(4, JavAIRuntime.embeddingCallGate().availablePermits(),
                    "and must give it back afterwards");
        } finally {
            JavAIRuntime.configureMaxConcurrentEmbeddingCalls(
                    JavAIRuntime.DEFAULT_MAX_CONCURRENT_EMBEDDING_CALLS);
        }
    }

    /**
     * A batch far larger than the gate still completes. This is the consequence of the decision above: were a
     * batch charged one permit per text, a 50-text batch against a gate of 2 could never acquire enough
     * permits and would block forever -- the gate would have made bulk seeding impossible rather than bounded.
     */
    @Test
    void aBatchLargerThanTheGateStillCompletes() {
        JavAIRuntime.configureMaxConcurrentEmbeddingCalls(2);
        try {
            BatchCountingProvider counting = new BatchCountingProvider();
            JavAIRuntime.configureEmbeddingProvider(counting);

            List<TestNode> nodes = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                nodes.add(new TestNode(unique("oversized-batch")));
            }

            assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                    () -> JavAIRuntime.precomputeVectors(nodes, 100),
                    "a batch bigger than the gate must not deadlock");
            assertEquals(1, counting.batchSizes.size(), "still one round trip: " + counting.batchSizes);
        } finally {
            JavAIRuntime.configureMaxConcurrentEmbeddingCalls(
                    JavAIRuntime.DEFAULT_MAX_CONCURRENT_EMBEDDING_CALLS);
        }
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
