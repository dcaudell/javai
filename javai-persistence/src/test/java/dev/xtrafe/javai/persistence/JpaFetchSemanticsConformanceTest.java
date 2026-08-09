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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
     * The shape that used to be silently root-only is refused outright (OMI-277).
     *
     * <p>A concrete-typed JavAI collection field was stored out-of-band in {@code javai_collection_members},
     * and that storage was only ever read and written for the entity a repository call returned: reached
     * through an association it came back empty, and saved through one its members were never written.
     * Neither failure announced itself, which is what made "still supported" the wrong answer.
     *
     * <p>Refusing it at registration is the whole fix. It cannot be made lazy where it stands -- the field
     * holds a {@code final} concrete instance the entity's own constructor created, and Hibernate manages a
     * collection by substituting its own, which a final class forbids. The message has to say that, because
     * the fix is a one-line change to the declaration and nothing about the failure suggests it.
     */
    @Test
    void aConcreteTypedJavAICollectionFieldIsRefusedAtRegistration() {
        // Its own configuration, so the refusal is the mapping validation rather than the
        // already-built-the-factory refusal this class's shared config would hit by now.
        JavAIPersistenceConfig freshConfig = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(TestConcreteJavAICollectionRepository.class, freshConfig));

        assertTrue(refused.getMessage().contains("JavAIList"),
                "the message must name the interface to declare instead; got: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("members"),
                "...and the offending field; got: " + refused.getMessage());
    }

    /**
     * And the supported shape behaves like any other association through the same access path the refused
     * one got wrong -- which is the point of refusing it rather than repairing it.
     */
    @Test
    void aJavAICollectionIsReadableThroughAnAssociationLikeAnyOtherAssociation() {
        TestVenue venue = new TestVenue("through-association-venue", null,
                List.of(new TestReview("a", 5), new TestReview("b", 4), new TestReview("c", 3)));
        TestVenueGroup group = new TestVenueGroup("through-association-group");
        group.getVenues().add(venue);
        groups.save(group);

        int asRoot = JavAIPI.inTransaction(config, () ->
                venues.findById(venue.getId()).orElseThrow().getReviews().size());

        int throughAssociation = JavAIPI.inTransaction(config, () -> {
            TestVenueGroup loaded = groups.findById(group.getId()).orElseThrow();
            Hibernate.initialize(loaded.getVenues());
            return loaded.getVenues().get(0).getReviews().size();
        });

        assertEquals(3, asRoot);
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
     * The write side of the same question, which used to lose members entirely when an owner was saved
     * through an association rather than as a root.
     */
    @Test
    void aJavAICollectionIsWrittenWhenItsOwnerIsSavedThroughAnAssociation() {
        TestVenue venue = new TestVenue("written-through-venue", null,
                List.of(new TestReview("x", 5), new TestReview("y", 4)));
        TestVenueGroup group = new TestVenueGroup("written-through-group");
        group.getVenues().add(venue);
        groups.save(group);

        int reviews = JavAIPI.inTransaction(config, () ->
                venues.findById(venue.getId()).orElseThrow().getReviews().size());

        assertEquals(2, reviews, "members saved through an association must actually be written");
    }
}
