package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the two {@link EmbeddingConsistencyMode}s' actual concurrency contracts under real races -- not
 * just their sequential lifecycle (already covered by {@link JavAIRuntimeLifecycleTest}):
 *
 * <ul>
 *   <li>{@link EmbeddingConsistencyMode#IMMEDIATE_CONSISTENCY}: a vector request is never observably
 *       inaccurate -- every value any reader ever sees corresponds to some real, actually-assigned field
 *       value, never a torn or fabricated one, regardless of how many writers and readers race.</li>
 *   <li>{@link EmbeddingConsistencyMode#EVENTUAL_CONSISTENCY}: the same soundness property holds (a stale
 *       read is allowed, a wrong one is not), and the system converges to the final mutation's vector once
 *       activity settles, without requiring a further read-triggering mutation.</li>
 *   <li>The shared {@link JavAIRuntime#configureMaxConcurrentEmbeddingCalls} gate bounds concurrent
 *       {@code embed()} calls under rapid mutation, regardless of mode.</li>
 *   <li>{@link EmbeddingFailureMode#THROW}/{@link EmbeddingFailureMode#RETURN_NULL}'s paired
 *       blocking/background failure behavior, and the opportunistic retry a failed background attempt
 *       leaves behind (see {@link JavAIRuntime#fieldVector}'s own javadoc).</li>
 * </ul>
 */
class EmbeddingConcurrencyTest {

    @AfterEach
    void resetGlobalConfig() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.THROW);
        JavAIRuntime.configureMaxConcurrentEmbeddingCalls(JavAIRuntime.DEFAULT_MAX_CONCURRENT_EMBEDDING_CALLS);
    }

    // ---- soundness under races, both modes -----------------------------------------------------

    @Test
    void immediateConsistencyNeverServesAVectorThatDoesNotMatchAnAssignedValue() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);

        raceMutationsAgainstReadsAndAssertSoundness("seed");
    }

    @Test
    void eventualConsistencyNeverServesAVectorThatDoesNotMatchAnAssignedValue() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);

        raceMutationsAgainstReadsAndAssertSoundness("seed");
    }

    @Test
    void coalescedConsistencyNeverServesAVectorThatDoesNotMatchAnAssignedValue() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.COALESCED_CONSISTENCY);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);

        raceMutationsAgainstReadsAndAssertSoundness("seed");
    }

    /**
     * Runs one writer thread mutating {@code text} through many distinct values and several reader
     * threads hammering {@code fieldVector("text")} concurrently, then asserts every vector any reader
     * observed is the deterministic embedding of *some* value the field genuinely held -- proving the
     * cache never regresses to (or momentarily serves) a value that was never actually assigned, under
     * whichever {@link EmbeddingConsistencyMode} is currently configured. Finishes by asserting the field
     * converges to the last assigned value once activity stops.
     */
    private void raceMutationsAgainstReadsAndAssertSoundness(String seed) throws Exception {
        TestNode node = new TestNode(seed);
        int mutations = 200;
        List<String> assignedValues = new CopyOnWriteArrayList<>();
        assignedValues.add(seed);

        Set<List<Float>> observed = new HashSet<>();
        Object observedLock = new Object();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<?> writer = executor.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < mutations; i++) {
                    String value = "value-" + i;
                    assignedValues.add(value);
                    node.setText(value);
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });

        List<Future<?>> readers = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            readers.add(executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < mutations; i++) {
                        List<Float> values = valuesOf(node.fieldVector("text"));
                        synchronized (observedLock) {
                            observed.add(values);
                        }
                    }
                } catch (Throwable e) {
                    failure.set(e);
                }
            }));
        }

        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        for (Future<?> f : readers) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        if (failure.get() != null) {
            fail("a reader/writer thread threw unexpectedly", failure.get());
        }

        Set<List<Float>> expected = new HashSet<>();
        for (String value : assignedValues) {
            expected.add(valuesOf(ConcurrencyTrackingEmbeddingProvider.embedDeterministic(value)));
        }
        for (List<Float> observedVector : observed) {
            assertTrue(expected.contains(observedVector),
                    "observed a vector that doesn't match any value ever assigned to the field");
        }

        String lastAssigned = assignedValues.get(assignedValues.size() - 1);
        awaitCondition(
                () -> valuesOf(node.fieldVector("text")).equals(
                        valuesOf(ConcurrencyTrackingEmbeddingProvider.embedDeterministic(lastAssigned))),
                Duration.ofSeconds(5),
                "field must converge to the last assigned value once activity settles");
    }

    // ---- no-op reassignment ----------------------------------------------------------------------

    @Test
    void reassigningAFieldToItsCurrentValueNeverTriggersRevectorizationUnderAnyMode() {
        for (EmbeddingConsistencyMode mode : EmbeddingConsistencyMode.values()) {
            JavAIRuntime.configureConsistencyMode(mode);
            ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider();
            JavAIRuntime.configureEmbeddingProvider(provider);

            TestNode node = new TestNode("seed");
            node.setText("first value"); // a real change -- lands a real computation, cleans the slot
            node.fieldVector("text"); // ensure it's landed (a no-op wait under IMMEDIATE_CONSISTENCY/first-ever)
            int callsAfterRealChange = provider.totalCalls();

            node.setText("first value"); // reassigning to the SAME value -- must be a complete no-op
            EmbeddingVector stillCached = node.fieldVector("text");

            assertEquals(callsAfterRealChange, provider.totalCalls(),
                    mode + ": a no-op reassignment must never trigger a new embed() call");
            assertArrayEquals(ConcurrencyTrackingEmbeddingProvider.embedDeterministic("first value").values(),
                    stillCached.values(), 1e-6f);
        }
    }

    // ---- semaphore backpressure -----------------------------------------------------------------

    @Test
    void maxConcurrentEmbeddingCallsGateBoundsConcurrencyUnderRapidMutation() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY);
        int maxConcurrent = 3;
        JavAIRuntime.configureMaxConcurrentEmbeddingCalls(maxConcurrent);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider(100);
        JavAIRuntime.configureEmbeddingProvider(provider);

        TestNode node = new TestNode("seed");
        node.fieldVector("text"); // first computation always blocks -- lands the initial real value

        int mutations = 15;
        for (int i = 0; i < mutations; i++) {
            node.setText("value-" + i); // each eagerly dispatches its own background embed() call
        }

        awaitCondition(() -> provider.totalCalls() >= mutations + 1, Duration.ofSeconds(10),
                "all eagerly-dispatched background calls must eventually run");

        assertTrue(provider.maxInFlight() <= maxConcurrent,
                "observed " + provider.maxInFlight() + " concurrent embed() calls, exceeding the configured "
                        + "gate of " + maxConcurrent);
        assertTrue(provider.maxInFlight() >= 2,
                "the gate should still allow genuine concurrency (not fully serialize calls) below its limit");
    }

    // ---- failure handling: THROW ----------------------------------------------------------------

    @Test
    void throwModeBlockingFailureRethrowsAndLeavesTheSlotDirtyForANormalRetry() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.THROW);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider();
        provider.failNextCalls(1);
        JavAIRuntime.configureEmbeddingProvider(provider);

        TestNode node = new TestNode("first");
        assertThrows(RuntimeException.class, () -> node.fieldVector("text"));

        // A failed attempt must never mark the slot clean -- the very next read recomputes fresh (this
        // time succeeding) rather than returning a stale/garbage value or staying permanently broken.
        EmbeddingVector recovered = node.fieldVector("text");
        assertArrayEquals(ConcurrencyTrackingEmbeddingProvider.embedDeterministic("first").values(),
                recovered.values(), 1e-6f);
    }

    @Test
    void throwModeBackgroundFailureKeepsLastKnownGoodValueAndRetriesOpportunisticallyOnNextRead()
            throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY);
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.THROW);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);

        TestNode node = new TestNode("first");
        EmbeddingVector firstGood = node.fieldVector("text"); // blocks; the first-ever computation succeeds

        provider.failNextCalls(1);
        node.setText("second"); // eager background dispatch, induced to fail exactly once
        awaitCondition(() -> provider.totalCalls() >= 2, Duration.ofSeconds(5),
                "the induced background failure must actually run");
        Thread.sleep(50); // let the failed attempt's commitFailure land before reading

        EmbeddingVector stillLastGood = node.fieldVector("text");
        assertArrayEquals(firstGood.values(), stillLastGood.values(), 1e-6f,
                "THROW mode must keep serving the last known-good value after a background failure");

        // No further mutation happens -- convergence here can only come from fieldVector()'s own
        // opportunistic re-dispatch of a still-dirty, already-computed-once slot.
        awaitCondition(
                () -> {
                    EmbeddingVector current = node.fieldVector("text");
                    return current != null && java.util.Arrays.equals(current.values(),
                            ConcurrencyTrackingEmbeddingProvider.embedDeterministic("second").values());
                },
                Duration.ofSeconds(5),
                "a read-triggered opportunistic retry must eventually converge with no further mutation");
    }

    // ---- failure handling: RETURN_NULL -----------------------------------------------------------

    @Test
    void returnNullModeBlockingFailureReturnsNullInsteadOfThrowing() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.RETURN_NULL);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider();
        provider.failNextCalls(1);
        JavAIRuntime.configureEmbeddingProvider(provider);

        TestNode node = new TestNode("first");
        assertNull(node.fieldVector("text"));

        EmbeddingVector recovered = node.fieldVector("text"); // retry, now unobstructed
        assertArrayEquals(ConcurrencyTrackingEmbeddingProvider.embedDeterministic("first").values(),
                recovered.values(), 1e-6f);
    }

    @Test
    void returnNullModeBackgroundFailureNullsOutTheServedValue() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY);
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.RETURN_NULL);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);

        TestNode node = new TestNode("first");
        node.fieldVector("text"); // blocks; succeeds

        provider.failNextCalls(1);
        node.setText("second");
        awaitCondition(() -> provider.totalCalls() >= 2, Duration.ofSeconds(5),
                "the induced background failure must actually run");
        Thread.sleep(50);

        assertNull(node.fieldVector("text"),
                "RETURN_NULL mode must null out the served value on background failure");
    }

    // ---- COALESCED_CONSISTENCY: blocking reads that join a shared in-flight computation -----------

    @Test
    void coalescedConsistencyReadBlocksUntilTheOutstandingComputationResolves() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.COALESCED_CONSISTENCY);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider(300);
        JavAIRuntime.configureEmbeddingProvider(provider);

        TestNode node = new TestNode("seed");
        node.fieldVector("text"); // land the first-ever (always-blocking) computation before timing anything

        node.setText("second"); // eagerly dispatches a background computation using the slow provider
        long start = System.nanoTime();
        EmbeddingVector result = node.fieldVector("text"); // must block for that same outstanding computation
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis >= 250,
                "a COALESCED_CONSISTENCY read of a dirty field must block for the outstanding computation, "
                        + "took only " + elapsedMillis + "ms");
        assertArrayEquals(ConcurrencyTrackingEmbeddingProvider.embedDeterministic("second").values(),
                result.values(), 1e-6f);
    }

    @Test
    void coalescedConsistencyManyConcurrentReadersShareOneComputation() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.COALESCED_CONSISTENCY);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider(200);
        JavAIRuntime.configureEmbeddingProvider(provider);

        TestNode node = new TestNode("seed");
        node.fieldVector("text"); // land the first-ever computation before timing/counting anything

        node.setText("second"); // eager background dispatch for the new generation
        int callsBeforeJoining = provider.totalCalls();

        int readerCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(readerCount);
        CountDownLatch ready = new CountDownLatch(readerCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<EmbeddingVector>> results = new ArrayList<>();
        for (int i = 0; i < readerCount; i++) {
            results.add(executor.submit(() -> {
                ready.countDown();
                go.await();
                return node.fieldVector("text");
            }));
        }
        ready.await();
        go.countDown();
        for (Future<EmbeddingVector> result : results) {
            EmbeddingVector value = result.get(5, TimeUnit.SECONDS);
            assertArrayEquals(ConcurrencyTrackingEmbeddingProvider.embedDeterministic("second").values(),
                    value.values(), 1e-6f);
        }
        executor.shutdown();

        assertEquals(callsBeforeJoining + 1, provider.totalCalls(),
                "many concurrent readers joining the same outstanding generation must share one embed() "
                        + "call, not fire one each");
    }

    @Test
    void coalescedConsistencyThrowModeBlockingFailurePropagatesToEveryJoiner() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.COALESCED_CONSISTENCY);
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.THROW);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider(150);
        JavAIRuntime.configureEmbeddingProvider(provider);

        TestNode node = new TestNode("seed");
        node.fieldVector("text"); // land the first-ever computation

        provider.failNextCalls(1);
        node.setText("second"); // the one real dispatch for this generation is doomed to fail

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<EmbeddingVector>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(executor.submit(() -> node.fieldVector("text")));
        }
        for (Future<EmbeddingVector> result : results) {
            try {
                result.get(5, TimeUnit.SECONDS);
                fail("every joiner must see the failure the owning computation produced under THROW mode");
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof RuntimeException);
            }
        }
        executor.shutdown();
    }

    @Test
    void coalescedConsistencyReturnNullModeBlockingFailureReturnsNullToEveryJoiner() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.COALESCED_CONSISTENCY);
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.RETURN_NULL);
        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider(150);
        JavAIRuntime.configureEmbeddingProvider(provider);

        TestNode node = new TestNode("seed");
        node.fieldVector("text");

        provider.failNextCalls(1);
        node.setText("second");

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<EmbeddingVector>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(executor.submit(() -> node.fieldVector("text")));
        }
        for (Future<EmbeddingVector> result : results) {
            assertNull(result.get(5, TimeUnit.SECONDS),
                    "every joiner must see null, matching the owning computation's RETURN_NULL outcome");
        }
        executor.shutdown();
    }

    // ---- IMMEDIATE_CONSISTENCY: object-level lock excludes concurrent setters ----------------------

    @Test
    void immediateConsistencySetterBlocksWhileAConcurrentReadIsComputing() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        TestNode node = new TestNode("seed");
        node.fieldVector("text"); // land the first-ever computation with a fast provider first

        ConcurrencyTrackingEmbeddingProvider provider = new ConcurrencyTrackingEmbeddingProvider(300);
        JavAIRuntime.configureEmbeddingProvider(provider); // switch to a slow provider for the timed part
        node.setText("second"); // marks dirty; IMMEDIATE_CONSISTENCY never computes eagerly on mutation

        CountDownLatch readerStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<EmbeddingVector> readerResult = executor.submit(() -> {
            readerStarted.countDown();
            return node.fieldVector("text"); // blocks ~300ms, holding the object lock the whole time
        });
        readerStarted.await();
        Thread.sleep(50); // give the reader a head start so it's genuinely inside the blocking compute

        long start = System.nanoTime();
        node.setText("third"); // must block until the reader's compute (and its lock hold) finishes
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        readerResult.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(elapsedMillis >= 150,
                "a concurrent setter must block while an IMMEDIATE_CONSISTENCY read is mid-computation, "
                        + "took only " + elapsedMillis + "ms");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static List<Float> valuesOf(EmbeddingVector vector) {
        List<Float> values = new ArrayList<>(vector.values().length);
        for (float value : vector.values()) {
            values.add(value);
        }
        return values;
    }

    private static void awaitCondition(BooleanSupplier condition, Duration timeout, String message)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}
