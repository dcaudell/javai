package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Version} through a {@link JavAIRepository}, end to end (OMI-254).
 *
 * <h2>Why this exists as its own test</h2>
 *
 * Optimistic locking through this backend used to <em>detect</em> correctly and be <em>unusable</em> anyway,
 * which is a combination no single assertion catches. Two concurrent writers produced one winner and one
 * failure exactly as they should; but {@code save()} returned the caller's own detached instance without the
 * version the write had just assigned to it, so saving that instance a second time -- no concurrency, no
 * second thread, no second transaction -- collided with the row the first save had written. Adding
 * {@code @Version} to an entity therefore broke ordinary provisioning code that saves, mutates and saves
 * again, and the failure surfaced as what looked like a concurrency bug somewhere else entirely.
 *
 * <p>So the two halves are asserted together and deliberately kept adjacent: <b>consecutive saves of one
 * instance must succeed</b>, and <b>concurrent writers must still collide</b>. Fixing the first by weakening
 * the second would be a considerably worse defect than the one being fixed, and only the pair says so.
 */
@Testcontainers
class OptimisticLockingTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestVersionedAlbumRepository albums;
    private static TestVersionedAssetRepository assets;
    private static TestVersionedCounterRepository counters;

    @BeforeAll
    static void configurePersistence() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        albums = JavAIPI.repository(TestVersionedAlbumRepository.class, config);
        assets = JavAIPI.repository(TestVersionedAssetRepository.class, config);
        counters = JavAIPI.repository(TestVersionedCounterRepository.class, config);
    }

    /**
     * The reported reproduction, reduced to its essentials: save, mutate what you got back, save it again.
     * This threw {@code OptimisticLockException} on the second call, with nothing concurrent anywhere near it.
     */
    @Test
    @DisplayName("consecutive saves of the instance save() returned succeed")
    void consecutiveSavesOfTheReturnedInstanceSucceed() {
        TestVersionedAlbum album = new TestVersionedAlbum("first description");

        TestVersionedAlbum once = albums.save(album);
        once.setDescription("second description");
        TestVersionedAlbum twice = albums.save(once);
        twice.setDescription("third description");
        albums.save(twice);

        assertEquals("third description", albums.findById(album.getId()).orElseThrow().getDescription(),
                "the last write must be the one that stuck");
    }

    /** The mechanism behind the test above, asserted directly: the version on the returned instance is the
     *  version in the database, not the one it had before the write. */
    @Test
    @DisplayName("the returned instance carries the version the write assigned")
    void returnedInstanceCarriesTheWrittenVersion() {
        TestVersionedAlbum saved = albums.save(new TestVersionedAlbum("versioned once"));
        long afterInsert = saved.getVersion();

        saved.setDescription("versioned twice");
        long afterUpdate = albums.save(saved).getVersion();

        assertTrue(afterUpdate > afterInsert,
                "an update must advance the version on the instance save() hands back, but it went from "
                        + afterInsert + " to " + afterUpdate);
        assertEquals(afterUpdate, albums.findById(saved.getId()).orElseThrow().getVersion(),
                "and it must match what a fresh load reads back");
    }

    /** Refreshing the root alone would leave the identical defect one hop down, so the cascaded member's
     *  version is asserted separately rather than assumed to follow. */
    @Test
    @DisplayName("a cascaded member's version is refreshed too, not just the root's")
    void cascadedMemberVersionIsRefreshed() {
        TestVersionedAlbum album = new TestVersionedAlbum("album with a member");
        TestVersionedAsset asset = new TestVersionedAsset("member caption");
        album.getAssets().add(asset);

        TestVersionedAlbum saved = albums.save(album);
        TestVersionedAsset savedAsset = saved.getAssets().get(0);
        assertEquals(savedAsset.getVersion(), assets.findById(asset.getId()).orElseThrow().getVersion(),
                "the member's in-memory version must match the row the same save wrote");

        // ...and the graph as a whole is re-savable, which is what the version refresh is for.
        saved.setDescription("album with a member, renamed");
        savedAsset.setCaption("member caption, rewritten");
        albums.save(saved);

        assertEquals("member caption, rewritten",
                assets.findById(asset.getId()).orElseThrow().getCaption());
    }

    /** Optimistic locking is orthogonal to embedding: an entity with nothing to vectorize must work exactly
     *  the same way. This is the case the vector-state machinery cannot carry by accident. */
    @Test
    @DisplayName("a non-vectorized @Version entity is re-savable too")
    void nonVectorizedVersionedEntityIsResavable() {
        TestVersionedCounter counter = counters.save(new TestVersionedCounter(1));

        counter.setTally(2);
        TestVersionedCounter twice = counters.save(counter);
        twice.setTally(3);
        counters.save(twice);

        assertEquals(3, counters.findById(counter.getId()).orElseThrow().getTally());
    }

    /**
     * The other half, and the one that must not have been bought by the first: a genuinely stale instance is
     * still refused. Two independently-loaded copies stand for two writers that both read version <i>n</i>;
     * the second to write is the loser whether or not the two overlapped in time.
     *
     * <p>Deterministic on purpose -- no threads, no latch, no scheduler. It asserts exactly what the
     * concurrent test below asserts, without the concurrent test's ability to pass by accident.
     */
    @Test
    @DisplayName("a stale instance is still refused")
    void staleInstanceIsStillRefused() {
        TestVersionedAlbum album = albums.save(new TestVersionedAlbum("contended album"));

        TestVersionedAlbum first = albums.findById(album.getId()).orElseThrow();
        TestVersionedAlbum second = albums.findById(album.getId()).orElseThrow();

        first.setDescription("written by the winner");
        albums.save(first);

        second.setDescription("written by the loser");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> albums.save(second));
        assertTrue(isOptimisticLockFailure(failure),
                "the loser must fail with an optimistic-lock failure, but got: " + describe(failure));

        assertEquals("written by the winner",
                albums.findById(album.getId()).orElseThrow().getDescription(),
                "and the loser's write must not have landed");
    }

    /**
     * The reported concurrent reproduction, kept because it is the acceptance criterion: two threads whose
     * read-modify-write windows are forced to overlap by a latch, at READ COMMITTED, so the database cannot
     * take the credit for the refusal. Exactly one wins.
     */
    @Test
    @DisplayName("two concurrent writers produce one winner and one optimistic-lock failure")
    void concurrentWritersProduceOneWinnerAndOneFailure() throws Exception {
        TestVersionedAlbum album = albums.save(new TestVersionedAlbum("concurrently contended"));
        CountDownLatch bothLoaded = new CountDownLatch(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> attempts = pool.invokeAll(List.of(
                    writerThatOverlaps(album.getId(), "alpha", bothLoaded),
                    writerThatOverlaps(album.getId(), "beta", bothLoaded)));

            Throwable first = attempts.get(0).get(60, TimeUnit.SECONDS);
            Throwable second = attempts.get(1).get(60, TimeUnit.SECONDS);

            long failures = List.of(java.util.Optional.ofNullable(first), java.util.Optional.ofNullable(second))
                    .stream().filter(java.util.Optional::isPresent).count();
            assertEquals(1, failures, "exactly one writer must fail, but got: first=" + describe(first)
                    + ", second=" + describe(second));

            Throwable loser = first != null ? first : second;
            assertNotNull(loser);
            assertTrue(isOptimisticLockFailure(loser),
                    "the loser must fail with an optimistic-lock failure, but got: " + describe(loser));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Loads, waits for its counterpart to have loaded too, then writes -- so both writers are holding the
     *  same version by the time either of them flushes. Returns the failure, or null for the winner. */
    private static java.util.concurrent.Callable<Throwable> writerThatOverlaps(
            java.util.UUID id, String name, CountDownLatch bothLoaded) {
        return () -> {
            try {
                JavAIPI.inTransaction(config, () -> {
                    TestVersionedAlbum held = albums.findById(id).orElseThrow();
                    held.setDescription("renamed by " + name);
                    bothLoaded.countDown();
                    try {
                        bothLoaded.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    albums.save(held);
                });
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        };
    }

    /**
     * Accepts either the JPA exception or Hibernate's own, anywhere in the cause chain.
     *
     * <p>Which of the two surfaces is a function of how the session was obtained, not of whether detection
     * worked -- Hibernate raises {@code StaleObjectStateException} and converts it to
     * {@code jakarta.persistence.OptimisticLockException} on the JPA-facing paths. Pinning one would make
     * this test assert the plumbing rather than the behavior, and the behavior is that the write was refused.
     */
    private static boolean isOptimisticLockFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof jakarta.persistence.OptimisticLockException
                    || current instanceof org.hibernate.StaleStateException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static String describe(Throwable failure) {
        return failure == null ? "no failure" : failure.getClass().getName() + ": " + failure.getMessage();
    }
}
