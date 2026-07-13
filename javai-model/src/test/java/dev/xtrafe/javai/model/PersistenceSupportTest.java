package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link JavAIRuntime#runWithSubgraphLockedForPersistence}/{@link JavAIRuntime#reachableVectorizables}
 * -- the machinery {@code javai-persistence} relies on to guarantee a flush never writes a stale vector,
 * regardless of the ambient {@link EmbeddingConsistencyMode}, and never runs concurrently with a mutation
 * anywhere in the subgraph being flushed.
 */
class PersistenceSupportTest {

    @BeforeAll
    static void configureFakeProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @AfterEach
    void resetGlobalConfig() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
    }

    @Test
    void reachableVectorizablesFindsTheRootAndEverySummaryReachableNode() {
        TestContainer container = new TestContainer("container label");
        TestNode featured = new TestNode("featured text");
        container.setFeatured(featured);
        TestNode item = new TestNode("item text");
        container.getItems().add(item);

        Set<Object> reachable = JavAIRuntime.reachableVectorizables(container);

        assertTrue(reachable.contains(container));
        assertTrue(reachable.contains(featured));
        assertTrue(reachable.contains(item));
        assertTrue(reachable.contains(container.getItems()), "the collection itself is also JavAIVectorizable");
    }

    @Test
    void forcedAccuracyOverridesEventualConsistencyForTheDurationOfTheBlock() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY);
        TestNode node = new TestNode("first");
        // Force the first computation to land for real before testing the "stale under EVENTUAL_CONSISTENCY"
        // case -- the very first computation always blocks regardless of mode, so this alone proves nothing
        // about forced accuracy yet.
        node.vector();

        node.setText("second, mutated just now");
        // Outside any forced-accuracy block, EVENTUAL_CONSISTENCY may legitimately serve a stale value here
        // (the background recompute may not have landed yet) -- not asserted either way, since it's a race.

        EmbeddingVector[] insideBlock = new EmbeddingVector[1];
        JavAIRuntime.runWithSubgraphLockedForPersistence(node, () -> insideBlock[0] = node.vector());

        EmbeddingVector expected = accurateVectorFor("second, mutated just now");
        assertArrayEquals(expected.values(), insideBlock[0].values(), 1e-6f,
                "inside the persistence block, the vector must be accurate even under EVENTUAL_CONSISTENCY");
    }

    @Test
    void forcedAccuracyDoesNotLeakPastTheBlock() throws InterruptedException {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY);
        TestNode node = new TestNode("first");
        node.vector();

        JavAIRuntime.runWithSubgraphLockedForPersistence(node, () -> {
            node.setText("during the block");
            node.vector();
        });

        // A slow provider after the block: if forced accuracy had leaked past the block, this read would
        // block waiting for the slow call to finish; if it correctly reset to EVENTUAL_CONSISTENCY's own
        // rules, a read of an already-computed, now-dirty slot returns the last known-good value immediately
        // without waiting on the (slow, backgrounded) recompute at all.
        JavAIRuntime.configureEmbeddingProvider(text -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new EmbeddingVector(new float[] {1f}, "test-model", 1, Instant.now());
        });
        node.setText("after the block");
        long start = System.nanoTime();
        node.vector();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 1_000,
                "a read after the block must not block on the slow provider under EVENTUAL_CONSISTENCY, took "
                        + elapsedMillis + "ms");
    }

    /**
     * Proves the gap found and fixed while building the refined consistency model: a persistence flush
     * genuinely freezes the subgraph against mutation, not just against inaccurate reads -- an ordinary
     * setter (even under {@code EVENTUAL_CONSISTENCY}, whose setters never otherwise block on anything)
     * must wait out an in-progress flush, because every setter briefly takes the same per-object lock
     * {@code runWithSubgraphLockedForPersistence} holds for the whole flush.
     */
    @Test
    void settersAreExcludedDuringAPersistenceFlushRegardlessOfMode() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY);
        TestNode node = new TestNode("first");
        node.vector(); // land the first-ever computation before timing anything

        CountDownLatch flushStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> flush = executor.submit(() -> JavAIRuntime.runWithSubgraphLockedForPersistence(node, () -> {
            flushStarted.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        flushStarted.await();
        Thread.sleep(50); // give the flush a head start so the setter below genuinely races an in-progress hold

        long start = System.nanoTime();
        node.setText("mutated during the flush");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        flush.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(elapsedMillis >= 150,
                "an ordinary EVENTUAL_CONSISTENCY setter must wait out an in-progress persistence flush on "
                        + "the same object, took only " + elapsedMillis + "ms");
    }

    /** Matches {@code fieldVector}'s raw-field-value text (not {@code concatenatedTextVector}'s
     *  {@code "fieldName: value\n"} format) -- {@code vector()} centroids a single field's own
     *  {@code fieldVector} unchanged, so this is exactly what the compositional {@code vector()} used
     *  throughout this test computes for a single-@Vectorize-field class like {@link TestNode}. */
    private static EmbeddingVector accurateVectorFor(String text) {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider();
        return provider.embed(text);
    }
}
