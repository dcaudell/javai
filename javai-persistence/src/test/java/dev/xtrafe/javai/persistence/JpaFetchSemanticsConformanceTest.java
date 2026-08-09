package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where JavAI's fetch behaviour does and does not match plain JPA (OMI-271 follow-up).
 *
 * <p>OMI-271 made natively-mapped associations behave like any other JPA association. It did not make
 * <em>everything</em> behave that way, and the difference is worth measuring rather than assuming, because
 * the remaining gap is not "eager where JPA would be lazy" -- which is merely a cost -- but a field whose
 * contents depend on how its owner was reached.
 */
@Testcontainers
class JpaFetchSemanticsConformanceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestVenueRepository venues;
    private static TestVenueGroupRepository groups;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        venues = JavAIPI.repository(TestVenueRepository.class, config);
        groups = JavAIPI.repository(TestVenueGroupRepository.class, config);
    }

    /**
     * The same field, the same row, two access paths.
     *
     * <p>{@code TestVenue.reviews} is a JavAI collection with no association annotation, so it is stored
     * out-of-band in {@code javai_collection_members} rather than mapped by Hibernate. {@code hydrateLoaded}
     * fills it for the entity a repository call returns -- and only for that entity, since it does not
     * recurse. Reached through an association instead, nothing fills it, and it is indistinguishable from a
     * venue that genuinely has no reviews.
     */
    @Test
    void aSideTableCollectionIsPopulatedAsARootAndEmptyThroughAnAssociation() {
        TestVenue venue = new TestVenue("conformance-venue", null,
                List.of(new TestReview("a", 5), new TestReview("b", 4), new TestReview("c", 3)));
        TestVenueGroup group = new TestVenueGroup("conformance-group");
        group.getVenues().add(venue);
        groups.save(group);

        int asRoot = venues.findById(venue.getId()).orElseThrow().getReviews().size();

        int throughAssociation = JavAIPI.inTransaction(config, () -> {
            TestVenueGroup loaded = groups.findById(group.getId()).orElseThrow();
            Hibernate.initialize(loaded.getVenues());
            return loaded.getVenues().get(0).getReviews().size();
        });

        assertEquals(3, asRoot, "as the root of a repository call the side table is read");
        assertEquals(asRoot, throughAssociation,
                "the same field on the same row must not depend on how its owner was reached");
    }

    /** The natively-mapped counterpart, for contrast: an ordinary lazy JPA association, behaving ordinarily. */
    @Test
    void aNativelyMappedAssociationIsLazyLikeAnyOtherJpaAssociation() {
        TestVenueGroup group = new TestVenueGroup("native-mapping-group");
        group.getVenues().add(new TestVenue("native-mapping-venue", null, List.of()));
        groups.save(group);

        TestVenueGroup detached = groups.findById(group.getId()).orElseThrow();
        assertFalse(Hibernate.isInitialized(detached.getVenues()), "@OneToMany defaults to LAZY");

        List<String> names = JavAIPI.inTransaction(config, () -> groups.findById(group.getId()).orElseThrow()
                .getVenues().stream().map(TestVenue::getName).toList());
        assertEquals(List.of("native-mapping-venue"), names, "...and initializes normally inside a session");
    }

    /**
     * The same question of a {@code Point}, the other out-of-band mapping -- and the answer is the same,
     * which was not what this test was written expecting (OMI-276).
     *
     * <p>{@code hydrateGeoPoints} <em>does</em> recurse, unlike the collection hydration above, so this
     * looked like the case that would pass. It fails because the recursion runs during {@code hydrateLoaded},
     * before the caller has initialized anything, and OMI-271 correctly stopped the walk at uninitialized
     * associations. On 0.1.9 it passed, because the walk force-initialized the whole graph and always got
     * there. So this one is a regression where the collection case is pre-existing -- verified by running
     * this class against 0.1.9 in a worktree.
     */
    @Test
    void aGeoPointIsHydratedThroughAnAssociationAsWellAsAtTheRoot() {
        TestVenue venue = new TestVenue("geo-venue", new org.springframework.data.geo.Point(4.5, 5.5), List.of());
        TestVenueGroup group = new TestVenueGroup("geo-group");
        group.getVenues().add(venue);
        groups.save(group);

        boolean throughAssociation = JavAIPI.inTransaction(config, () -> {
            TestVenueGroup loaded = groups.findById(group.getId()).orElseThrow();
            Hibernate.initialize(loaded.getVenues());
            return loaded.getVenues().get(0).getLocation() != null;
        });

        assertTrue(throughAssociation, "a Point reached through an association is still read from its side table");
    }

    /**
     * Separates the two halves of the collection defect, which otherwise hide inside one another: the test
     * above cannot tell "the rows were never written" from "the rows are never read".
     *
     * <p>Saving as a root first guarantees the rows exist; reading as a root afterwards confirms they are
     * still there. Only the read <em>through an association</em> comes back empty. Measured:
     * {@code afterRootSave=2, rootReadAfterGroupSave=2, throughAssociation=0}.
     */
    @Test
    void theSideTableReadIsRootOnlyEvenWhenTheRowsCertainlyExist() {
        // Saved as ROOT first, so the side-table rows definitely exist.
        TestVenue venue = new TestVenue("isolation-venue", null,
                List.of(new TestReview("x", 5), new TestReview("y", 4)));
        venues.save(venue);
        int afterRootSave = venues.findById(venue.getId()).orElseThrow().getReviews().size();

        // Now referenced by a group and saved again through it.
        TestVenueGroup group = new TestVenueGroup("isolation-group");
        group.getVenues().add(venue);
        groups.save(group);

        int rootReadAfterGroupSave = venues.findById(venue.getId()).orElseThrow().getReviews().size();
        int throughAssociation = JavAIPI.inTransaction(config, () -> {
            TestVenueGroup loaded = groups.findById(group.getId()).orElseThrow();
            Hibernate.initialize(loaded.getVenues());
            return loaded.getVenues().get(0).getReviews().size();
        });

        assertEquals(2, afterRootSave, "a root save writes the side table");
        assertEquals(2, rootReadAfterGroupSave, "and the rows are still there afterwards");
        assertEquals(2, throughAssociation,
                "so an empty result through an association is a read defect, not a missing write");
    }
}
