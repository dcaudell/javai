package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
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
 * Every load path funnels through {@code hydrateLoaded}, whose two graph walks
 * ({@code hydrateGeoPoints}, {@code hydrateAssociatedVectors}) both traverse via {@code reachableRelated}.
 * That walk read every field and, on hitting a {@code Collection}, called {@code addAll} on it -- and
 * iterating an uninitialized {@code PersistentCollection} <em>is</em> initializing it. So reading one scalar
 * off one entity loaded its entire reachable collection graph, recursively, plus a side-table SELECT per
 * entity in it.
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
     * The residual, asserted so it is a known quantity rather than a surprise: a JavAI collection field
     * carrying <em>no</em> association annotation is mapped out-of-band through
     * {@code javai_collection_members} and hydrated eagerly by {@code hydrateCollectionMembers}, because it
     * has no Hibernate laziness to lean on -- {@code JavAIArrayList} is a plain field value the constructor
     * already created, so declining to fill it would hand the caller a silently-empty collection rather than
     * a lazy one.
     *
     * <p>Contrast {@link TestLibrary#getShelves()}, a JavAI collection that <em>does</em> carry
     * {@code @OneToMany}: Hibernate maps that natively and substitutes a {@link PersistentJavAIList}, so it
     * is genuinely lazy (see the test above). Making the out-of-band form lazy too means a lazy-initializing
     * JavAI collection, which is a feature rather than this fix.
     */
    @Test
    void aSideTableJavAICollectionIsStillHydratedEagerly() {
        TestVenue venue = new TestVenue("over-fetch-javai-collection", null, List.of(
                new TestReview("a", 5), new TestReview("b", 4), new TestReview("c", 3),
                new TestReview("d", 2), new TestReview("e", 1)));
        UUID id = venues.save(venue).getId();

        statistics.clear();
        TestVenue loaded = venues.findById(id).orElseThrow();

        assertEquals(5, loaded.getReviews().size());
        assertEquals(6, statistics.getEntityLoadCount(),
                "the owner plus its five members -- the known cost of an out-of-band collection mapping");
    }
}
