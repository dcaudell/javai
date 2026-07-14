package dev.xtrafe.javai.vector;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorCacheSlotTest {

    private static EmbeddingVector vectorOf(float value) {
        return new EmbeddingVector(new float[] {value}, "test-model", 1, Instant.now());
    }

    @Test
    void startsDirtyAndUncomputed() {
        VectorCacheSlot slot = new VectorCacheSlot();
        assertTrue(slot.isDirty());
        assertFalse(slot.everComputed());
        assertNull(slot.cachedValue());
    }

    @Test
    void firstSuccessfulCommitClearsDirty() {
        VectorCacheSlot slot = new VectorCacheSlot();
        long gen = slot.currentGeneration();
        assertTrue(slot.commitSuccess(gen, vectorOf(1f)));
        assertFalse(slot.isDirty());
        assertTrue(slot.everComputed());
        assertEquals(1f, slot.cachedValue().values()[0]);
    }

    @Test
    void mutationAfterCommitReintroducesDirty() {
        VectorCacheSlot slot = new VectorCacheSlot();
        slot.commitSuccess(slot.currentGeneration(), vectorOf(1f));
        assertFalse(slot.isDirty());

        long newGen = slot.bumpGeneration();
        assertTrue(slot.isDirty());
        // last known-good value is still served while dirty
        assertEquals(1f, slot.cachedValue().values()[0]);

        slot.commitSuccess(newGen, vectorOf(2f));
        assertFalse(slot.isDirty());
        assertEquals(2f, slot.cachedValue().values()[0]);
    }

    @Test
    void staleSuccessAfterNewerSuccessIsDiscarded() {
        VectorCacheSlot slot = new VectorCacheSlot();
        long gen1 = slot.currentGeneration(); // 1
        long gen2 = slot.bumpGeneration();    // 2

        // Newer (gen2) completes first.
        assertTrue(slot.commitSuccess(gen2, vectorOf(2f)));
        // Older (gen1) completes late -- must be discarded, not allowed to regress the cache.
        assertFalse(slot.commitSuccess(gen1, vectorOf(1f)));

        assertEquals(2f, slot.cachedValue().values()[0]);
        assertEquals(gen2, slot.committedGeneration());
    }

    @Test
    void staleSuccessAfterNewerFailureIsAlsoDiscarded() {
        // The subtle race this class exists to prevent: a failure for a newer generation must not be
        // silently overwritten by a slower success for an older generation completing afterward.
        VectorCacheSlot slot = new VectorCacheSlot();
        long gen2 = slot.bumpGeneration(); // 2
        long gen3 = slot.bumpGeneration(); // 3

        slot.commitFailure(gen3, true); // NULL_OUT-style failure for the newest generation
        assertNull(slot.cachedValue());
        assertTrue(slot.isDirty());

        // gen2's success, which started earlier, finally completes -- must be rejected.
        boolean landed = slot.commitSuccess(gen2, vectorOf(2f));
        assertFalse(landed, "an older success must not be allowed to land after a newer failure was recorded");
        assertNull(slot.cachedValue(), "the newer failure's null-out must not be overwritten by stale data");
        assertTrue(slot.isDirty());
    }

    @Test
    void successCanLandAsARetryForTheSameGenerationAfterAPriorFailure() {
        // The opportunistic-retry guarantee: a transient failure for generation g must not permanently
        // block generation g from ever committing again -- a later retry (no new mutation, same
        // generation) needs to be able to land once the underlying cause of the failure clears up.
        VectorCacheSlot slot = new VectorCacheSlot();
        long gen = slot.currentGeneration();

        slot.commitFailure(gen, false);
        assertTrue(slot.isDirty());

        boolean landed = slot.commitSuccess(gen, vectorOf(1f));
        assertTrue(landed, "a retry for the same generation as a prior failure must be able to land");
        assertFalse(slot.isDirty());
        assertEquals(1f, slot.cachedValue().values()[0]);
    }

    @Test
    void keepStaleFailureLeavesLastKnownGoodValueInPlace() {
        VectorCacheSlot slot = new VectorCacheSlot();
        long gen1 = slot.currentGeneration();
        slot.commitSuccess(gen1, vectorOf(1f));

        long gen2 = slot.bumpGeneration();
        slot.commitFailure(gen2, false); // KEEP_STALE

        assertTrue(slot.isDirty(), "a failed attempt must never be treated as clean");
        assertEquals(1f, slot.cachedValue().values()[0], "keep-stale must preserve the last real value");
    }

    @Test
    void nullOutFailureClearsTheServedValue() {
        VectorCacheSlot slot = new VectorCacheSlot();
        long gen1 = slot.currentGeneration();
        slot.commitSuccess(gen1, vectorOf(1f));

        long gen2 = slot.bumpGeneration();
        slot.commitFailure(gen2, true); // NULL_OUT

        assertTrue(slot.isDirty());
        assertNull(slot.cachedValue());
    }

    @Test
    void failureNeverAdvancesCommittedGenerationEvenWithoutNullingOut() {
        VectorCacheSlot slot = new VectorCacheSlot();
        long gen = slot.currentGeneration();
        slot.commitFailure(gen, false);
        assertEquals(0, slot.committedGeneration(), "committedGeneration must only ever advance on success");
        assertTrue(slot.isDirty());
    }

    @Test
    void concurrentMutateAndCommitNeverRegressesTheCommittedGeneration() throws InterruptedException {
        // Stress test: many threads race to bump the generation and commit (successes and failures,
        // interleaved) for whatever generation they individually observed. The only invariant asserted is
        // the one this class exists to guarantee: committedGeneration only ever moves forward, and whatever
        // value is cached always corresponds to some generation that was genuinely offered for it.
        VectorCacheSlot slot = new VectorCacheSlot();
        int threads = 32;
        int attemptsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger observedRegressions = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < attemptsPerThread; i++) {
                    long gen = slot.bumpGeneration();
                    long committedBefore = slot.committedGeneration();
                    if (i % 5 == 0) {
                        slot.commitFailure(gen, i % 2 == 0);
                    } else {
                        slot.commitSuccess(gen, vectorOf(gen));
                    }
                    if (slot.committedGeneration() < committedBefore) {
                        observedRegressions.incrementAndGet();
                    }
                }
            });
        }
        ready.await();
        go.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(0, observedRegressions.get(), "committedGeneration must never be observed to regress");
        // Whatever ended up cached, if non-null, must correspond to its own recorded generation's value
        // (vectorOf(gen) encodes gen as the value itself, by construction of this test).
        EmbeddingVector finalValue = slot.cachedValue();
        if (finalValue != null) {
            assertEquals((float) slot.committedGeneration(), finalValue.values()[0], 1e-6f);
        }
    }

    @Test
    void firstClaimForAGenerationIsTheOwner() {
        VectorCacheSlot slot = new VectorCacheSlot();
        VectorCacheSlot.PendingComputation claim = slot.claimPendingComputation(1);
        assertTrue(claim.owner());
        assertEquals(1, claim.generation());
    }

    @Test
    void secondClaimForTheSameGenerationJoinsTheFirstInsteadOfOwningItsOwn() {
        VectorCacheSlot slot = new VectorCacheSlot();
        VectorCacheSlot.PendingComputation first = slot.claimPendingComputation(1);
        VectorCacheSlot.PendingComputation second = slot.claimPendingComputation(1);

        assertTrue(first.owner());
        assertFalse(second.owner(), "a second claim for the same generation must not also become an owner");
        assertSame(first.future(), second.future(), "joiners must share the exact same future as the owner");
    }

    @Test
    void claimForADifferentGenerationAfterClearingInstallsAFreshOwner() {
        VectorCacheSlot slot = new VectorCacheSlot();
        VectorCacheSlot.PendingComputation first = slot.claimPendingComputation(1);
        first.future().complete(vectorOf(1f));
        slot.clearPendingComputation(1);

        VectorCacheSlot.PendingComputation second = slot.claimPendingComputation(2);
        assertTrue(second.owner());
        assertFalse(second.future().isDone());
    }

    @Test
    void clearingAStaleGenerationIsANoOpAgainstANewerClaim() {
        VectorCacheSlot slot = new VectorCacheSlot();
        slot.claimPendingComputation(1);
        VectorCacheSlot.PendingComputation second = slot.claimPendingComputation(2);

        slot.clearPendingComputation(1); // stale -- generation 2 already replaced it

        VectorCacheSlot.PendingComputation stillSecond = slot.claimPendingComputation(2);
        assertFalse(stillSecond.owner(), "clearing a stale generation must not disturb a newer claim");
        assertSame(second.future(), stillSecond.future());
    }
}
