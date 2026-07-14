package dev.xtrafe.javai.model.benchmark;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.EmbeddingFailureMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Hand-run comparative profiling harness for all three {@link EmbeddingConsistencyMode}s -- not a
 * correctness test (see {@code EmbeddingConcurrencyTest} for that), and not gated on any pass/fail
 * performance threshold, since absolute numbers are entirely machine-dependent. Lives in this dedicated
 * {@code benchmark} package (own fixtures, own reporting utilities) precisely so performance-checking code
 * stays visibly segregated from the correctness-test suite it sits alongside, not just tag-excluded from
 * it. Tagged {@code "performance"} and excluded from the default {@code mvn test}/{@code mvn install} run
 * repo-wide (see the root {@code pom.xml}'s surefire configuration) because a realistic simulated network
 * delay makes this much slower than the rest of the suite by design. Run it explicitly:
 *
 * <pre>mvn -pl javai-model -am test -Dtest=EmbeddingConsistencyBenchmark -Djavai.excludedTestGroups=</pre>
 *
 * <p>Simulates a representative concurrent workload -- many threads sharing a pool of vectorizable
 * objects, mostly reading, occasionally mutating -- against a provider with a fixed artificial network
 * delay ({@link SimulatedNetworkEmbeddingProvider}), and reports throughput and read-latency percentiles
 * for each mode side by side on stdout, followed by a before/after comparison against whatever this same
 * benchmark recorded the last time it ran ({@link BenchmarkHistory}).
 *
 * <p>Both {@link BenchmarkResult}/{@link BenchmarkHistory} are deliberately generic -- see their own
 * javadoc -- so a future persistence-performance benchmark ({@code javai-persistence}) or a
 * substrate-weaving-optimization benchmark ({@code javai-substrate}) can follow the exact same pattern
 * (own {@code benchmark} package, own fixtures, {@code @Tag("performance")}, results recorded via
 * {@code BenchmarkHistory.recordAndCompare}) without needing to invent new comparison/reporting machinery.
 */
@Tag("performance")
class EmbeddingConsistencyBenchmark {

    private static final int OBJECT_POOL_SIZE = 20;
    private static final int WORKER_THREADS = 16;
    private static final int OPERATIONS_PER_WORKER = 200;
    private static final double MUTATION_PROBABILITY = 0.1;
    private static final long SIMULATED_NETWORK_DELAY_MILLIS = 15;

    @AfterEach
    void resetGlobalConfig() {
        JavAIRuntime.configureEmbeddingProvider(new SimulatedNetworkEmbeddingProvider(0));
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureFailureMode(EmbeddingFailureMode.THROW);
        JavAIRuntime.configureMaxConcurrentEmbeddingCalls(JavAIRuntime.DEFAULT_MAX_CONCURRENT_EMBEDDING_CALLS);
    }

    @Test
    void compareAllConsistencyModesUnderTheSameWorkload() throws InterruptedException {
        Report immediate = run(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        Report coalesced = run(EmbeddingConsistencyMode.COALESCED_CONSISTENCY);
        Report eventual = run(EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY);

        System.out.println();
        System.out.println("==== Embedding consistency mode comparison ====");
        System.out.printf("workload: %d threads x %d ops (%.0f%% mutate / %.0f%% read), %dms simulated network delay%n",
                WORKER_THREADS, OPERATIONS_PER_WORKER, MUTATION_PROBABILITY * 100,
                (1 - MUTATION_PROBABILITY) * 100, SIMULATED_NETWORK_DELAY_MILLIS);
        System.out.println();
        immediate.printRow("IMMEDIATE_CONSISTENCY");
        coalesced.printRow("COALESCED_CONSISTENCY");
        eventual.printRow("EVENTUAL_CONSISTENCY");
        System.out.println();

        BenchmarkHistory.recordAndCompare("embedding-consistency", List.of(
                immediate.toBenchmarkResult("IMMEDIATE_CONSISTENCY"),
                coalesced.toBenchmarkResult("COALESCED_CONSISTENCY"),
                eventual.toBenchmarkResult("EVENTUAL_CONSISTENCY")));

        // The only hard assertions this harness makes: all three runs actually completed the full workload
        // with no thread left short -- everything else is a printed comparison for human review, not a
        // pass/fail performance gate, since absolute timings are entirely machine-dependent.
        assertEquals((long) WORKER_THREADS * OPERATIONS_PER_WORKER, immediate.totalOps);
        assertEquals((long) WORKER_THREADS * OPERATIONS_PER_WORKER, coalesced.totalOps);
        assertEquals((long) WORKER_THREADS * OPERATIONS_PER_WORKER, eventual.totalOps);
    }

    private Report run(EmbeddingConsistencyMode mode) throws InterruptedException {
        JavAIRuntime.configureConsistencyMode(mode);
        JavAIRuntime.configureEmbeddingProvider(
                new SimulatedNetworkEmbeddingProvider(SIMULATED_NETWORK_DELAY_MILLIS));

        List<BenchmarkVectorizableNode> pool = new ArrayList<>(OBJECT_POOL_SIZE);
        for (int i = 0; i < OBJECT_POOL_SIZE; i++) {
            BenchmarkVectorizableNode node = new BenchmarkVectorizableNode("seed-" + i);
            node.vector(); // land the first-ever (always-blocking) computation before the timed window starts
            pool.add(node);
        }

        List<Long> readLatenciesNanos = Collections.synchronizedList(new ArrayList<>());
        AtomicLong totalOps = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREADS);
        CountDownLatch ready = new CountDownLatch(WORKER_THREADS);
        CountDownLatch go = new CountDownLatch(1);

        for (int w = 0; w < WORKER_THREADS; w++) {
            long seed = w;
            executor.submit(() -> {
                Random random = new Random(seed);
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < OPERATIONS_PER_WORKER; i++) {
                    BenchmarkVectorizableNode node = pool.get(random.nextInt(pool.size()));
                    if (random.nextDouble() < MUTATION_PROBABILITY) {
                        node.setText("mutated-" + seed + "-" + i);
                    } else {
                        long start = System.nanoTime();
                        node.vector();
                        readLatenciesNanos.add(System.nanoTime() - start);
                    }
                    totalOps.incrementAndGet();
                }
            });
        }

        ready.await();
        long startWall = System.nanoTime();
        go.countDown();
        executor.shutdown();
        if (!executor.awaitTermination(2, TimeUnit.MINUTES)) {
            throw new IllegalStateException("benchmark workload did not finish within the allotted time");
        }
        long elapsedNanos = System.nanoTime() - startWall;

        return new Report(totalOps.get(), elapsedNanos, readLatenciesNanos);
    }

    private record Report(long totalOps, long elapsedNanos, List<Long> readLatenciesNanos) {

        void printRow(String label) {
            List<Long> sorted = new ArrayList<>(readLatenciesNanos);
            Collections.sort(sorted);
            double throughput = throughputOpsPerSec();
            System.out.printf(
                    "%-24s total=%dms throughput=%.1f ops/sec  read p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms%n",
                    label, elapsedNanos / 1_000_000, throughput,
                    percentileMillis(sorted, 0.50), percentileMillis(sorted, 0.95),
                    percentileMillis(sorted, 0.99), percentileMillis(sorted, 1.0));
        }

        /** Converts to the generic {@link BenchmarkResult} shape {@link BenchmarkHistory} compares across
         *  runs -- kept as separate named metrics (not a single opaque score) so a future reader can see
         *  exactly which aspect of performance moved. */
        BenchmarkResult toBenchmarkResult(String label) {
            List<Long> sorted = new ArrayList<>(readLatenciesNanos);
            Collections.sort(sorted);
            return BenchmarkResult.builder(label)
                    .metric("totalMillis", elapsedNanos / 1_000_000.0)
                    .metric("throughputOpsPerSec", throughputOpsPerSec())
                    .metric("readP50Millis", percentileMillis(sorted, 0.50))
                    .metric("readP95Millis", percentileMillis(sorted, 0.95))
                    .metric("readP99Millis", percentileMillis(sorted, 0.99))
                    .metric("readMaxMillis", percentileMillis(sorted, 1.0))
                    .build();
        }

        private double throughputOpsPerSec() {
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            return totalOps / elapsedSeconds;
        }

        private static double percentileMillis(List<Long> sortedNanos, double percentile) {
            if (sortedNanos.isEmpty()) {
                return 0.0;
            }
            int index = Math.min(sortedNanos.size() - 1, (int) Math.ceil(percentile * sortedNanos.size()) - 1);
            return sortedNanos.get(Math.max(0, index)) / 1_000_000.0;
        }
    }
}
