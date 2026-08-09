package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.hibernate.Hibernate;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What a read actually loads (OMI-271).
 *
 * <h2>The defect these pin</h2>
 *
 * Every load path funnels through {@code hydrateLoaded}, which walked the loaded root's whole reachable
 * graph via {@code reachableRelated} -- reading every field and, on hitting a {@code Collection}, calling
 * {@code addAll} on it. Iterating an uninitialized {@code PersistentCollection} <em>is</em> initializing it,
 * so reading one scalar off one entity loaded its entire reachable collection graph, recursively, plus a
 * side-table SELECT per entity in it.
 *
 * <p>The walk existed to serve those entities their stored vectors (OMI-256). {@link
 * JavAIPostLoadVectorListener} now does that from Hibernate's own load event instead, which is both cheaper
 * (one SELECT per entity actually loaded) and strictly more complete -- see
 * {@link #aMemberInitializedByTheCallerIsStillServedItsStoredVectors}.
 *
 * <p>It went unnoticed because the same walk enforces laziness correctly on <em>singular</em> associations,
 * which {@code AssociationGraphE2ETest.touchingAnUninitializedLazyAssociationOutsideASessionThrows} pins --
 * but by accident rather than by design: an uninitialized singular proxy is a generated subclass and
 * {@code @Entity} is not {@code @Inherited}, so {@code reachableRelated}'s {@code isAnnotationPresent} test
 * rejects it. A lazy {@code @OneToMany}/{@code @ManyToMany} has no such accident protecting it. These tests
 * are the collection-shaped counterpart of that one.
 *
 * <h2>Why the fixtures are what they are</h2>
 *
 * {@link TestPlainOwner} exists solely so the first test can rule a hypothesis out rather than merely
 * observe an effect: nothing in that graph is vectorized in any way, so no vector-hydration explanation can
 * survive it loading anyway. {@link TestLibrary} then supplies magnitude on the shape a real consumer has --
 * three lazy levels, every one {@code JavAIVectorizable} and {@code @Summary}-marked.
 */
@Testcontainers
class LoadPathOverFetchTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestPlainOwnerRepository plainOwners;
    private static TestVenueRepository venues;
    private static TestLibraryRepository libraries;
    private static Statistics statistics;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        plainOwners = JavAIPI.repository(TestPlainOwnerRepository.class, config);
        venues = JavAIPI.repository(TestVenueRepository.class, config);
        libraries = JavAIPI.repository(TestLibraryRepository.class, config);
        // Hibernate's own load counters, rather than SQL counting: they distinguish "loaded an entity" from
        // "issued a statement", and it is the first that this ticket is about.
        statistics = JavAIPI.sessionFactory(config).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    /** The discriminator: nothing here is vectorized, so nothing about vectors can explain a graph load. */
    @Test
    void readingOneScalarOffAnEntirelyUnvectorizedGraphLoadsOnlyTheRoot() {
        TestPlainOwner owner = new TestPlainOwner("over-fetch-control");
        for (int i = 0; i < 5; i++) {
            owner.getChildren().add(new TestPlainChild("child-" + i));
        }
        UUID id = plainOwners.save(owner).getId();

        statistics.clear();
        TestPlainOwner loaded = plainOwners.findById(id).orElseThrow();

        assertEquals("over-fetch-control", loaded.getLabel());
        assertFalse(Hibernate.isInitialized(loaded.getChildren()),
                "reading one scalar must not initialize an untouched lazy @OneToMany");
        assertEquals(1, statistics.getEntityLoadCount(),
                "loading one entity must load exactly one entity, not 1 + its children");
    }

    /** The magnitude, on the shape a real consumer has: 1 + 3 + 12 entities exist, one string was asked for. */
    @Test
    void readingOneScalarOffAVectorizedRootLoadsOnlyThatEntity() {
        TestLibrary library = new TestLibrary("over-fetch-vectorized");
        for (int s = 0; s < 3; s++) {
            TestShelf shelf = new TestShelf("shelf-" + s);
            for (int b = 0; b < 4; b++) {
                shelf.getBooks().add(new TestBook("book-" + s + "-" + b));
            }
            library.getShelves().add(shelf);
        }
        libraries.save(library);

        statistics.clear();
        TestLibrary loaded = libraries.findById(library.getId()).orElseThrow();

        assertEquals("over-fetch-vectorized", loaded.getName());
        assertFalse(Hibernate.isInitialized(loaded.getShelves()),
                "a lazy JavAI collection is as untouchable as any other lazy collection");
        assertEquals(1, statistics.getEntityLoadCount(),
                "loading one entity must not load the 15 entities reachable from it");
    }

    /**
     * The supported way to get the graph, now that reading one entity no longer hands it over unasked: read
     * inside a unit of work, where the entity is still managed and ordinary JPA laziness applies.
     *
     * <p>Worth pinning rather than leaving implied, because it is the whole answer to "so how do I traverse
     * it then?" -- and because it is the shape a consumer's own read has to move to. The cost is then the
     * caller's to choose: this traverses one level and pays for one level.
     */
    @Test
    void theGraphIsStillTraversableInsideAUnitOfWork() {
        TestLibrary library = new TestLibrary("traversable-in-session");
        TestShelf shelf = new TestShelf("in-session-shelf");
        shelf.getBooks().add(new TestBook("in-session-book"));
        library.getShelves().add(shelf);
        libraries.save(library);

        List<String> shelfLabels = JavAIPI.inTransaction(config, () -> {
            TestLibrary loaded = libraries.findById(library.getId()).orElseThrow();
            return loaded.getShelves().stream().map(TestShelf::getLabel).toList();
        });

        assertEquals(List.of("in-session-shelf"), shelfLabels);
    }

    /**
     * The other half of removing the walk: an entity is still served its stored vectors, now at the moment
     * Hibernate materializes it rather than because something went looking.
     *
     * <p>This is the case no load-time walk could ever have covered, and the reason
     * {@link JavAIPostLoadVectorListener} exists rather than a smaller walk: the member is initialized by the
     * <em>caller</em>, after any walk at {@code findById} time has finished. Served cold, it would be
     * re-embedded on the next save -- one model call per member, for content that has not changed, which is
     * exactly the waste OMI-256 removed and OMI-271 must not reintroduce.
     */
    @Test
    void aMemberInitializedByTheCallerIsStillServedItsStoredVectors() {
        TestLibrary library = new TestLibrary("post-load-hydration");
        TestShelf shelf = new TestShelf("hydrated-shelf");
        library.getShelves().add(shelf);
        libraries.save(library);

        RecordingEmbeddingProvider recorder = new RecordingEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureEmbeddingProvider(recorder);
        try {
            TestLibrary loaded = JavAIPI.inTransaction(config, () -> {
                TestLibrary reloaded = libraries.findById(library.getId()).orElseThrow();
                Hibernate.initialize(reloaded.getShelves());
                return reloaded;
            });
            libraries.save(loaded);
        } finally {
            JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        }

        recorder.ledger().assertEmbeddedExactlyOnce();
    }

    /**
     * What used to be this class's "known residual" is gone (OMI-277).
     *
     * <p>A JavAI collection field carrying no association annotation was mapped out-of-band and hydrated
     * eagerly for the root of a repository call -- the one mapping OMI-271 could not make lazy, because the
     * field held a final concrete instance with no Hibernate collection behind it. That shape is refused at
     * registration now, so every JavAI collection is a native association and every one of them is lazy.
     */
    @Test
    void everyJavAICollectionIsNowLazyIncludingTheOnesThatUsedToBeEager() {
        TestVenue venue = new TestVenue("no-longer-eager", null, List.of(
                new TestReview("a", 5), new TestReview("b", 4)));
        venues.save(venue);

        TestVenue detached = venues.findById(venue.getId()).orElseThrow();
        assertFalse(Hibernate.isInitialized(detached.getReviews()),
                "a JavAI collection is lazy like any other association now");

        int inSession = JavAIPI.inTransaction(config, () ->
                venues.findById(venue.getId()).orElseThrow().getReviews().size());
        assertEquals(2, inSession, "...and initializes normally inside a unit of work");
    }
}
