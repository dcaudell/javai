package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attachment state, asserted at every node a caller can reach rather than only at the root (OMI-275, Topic 1).
 *
 * <h2>The question this class exists to answer first</h2>
 *
 * Not "does JavAI detach?" but <b>"can a caller ever hold a graph that is attached in one place and detached
 * in another?"</b> A uniformly detached graph is workable — every traversal fails the same way, and the fix
 * is one idiom. A uniformly attached one is workable for the same reason. A <em>mixed</em> graph is the bad
 * outcome, because nothing about the object tells the caller which kind of node they are holding: the same
 * traversal works or throws depending on where they landed, and no rule they could learn covers it.
 *
 * <p>So this is written before the rest of the matrix. If a mixed graph is reachable at all, that is a
 * structural finding about how repositories return results, and it is far cheaper to learn now than after
 * (relationship type × fetch mode × transaction context) has been enumerated against an answer that moves.
 *
 * <p>Attachment is read from Hibernate directly ({@code Session.contains}), not inferred from whether a
 * traversal happens to throw. This test lives in JavAI's own package, so it can reach the ambient session
 * through {@link JavAITransactionScope} exactly as the backend does — no Spring, and no new API opened up
 * merely to be observable.
 */
@Testcontainers
class AttachmentConformanceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestVenueGroupRepository groups;
    private static TestVenueRepository venues;
    private static TestLazyAssocOwnerRepository lazyOwners;
    private static TestEagerAssocOwnerRepository eagerOwners;
    private static TestAssocTargetRepository assocTargets;
    private static TestTeamRepository teams;
    private static TestChapterRepository chapters;
    private static TestShelfRepository shelves;
    private static TestAlbumRepository albums;
    private static TestImageCoverRepository imageCovers;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        groups = JavAIPI.repository(TestVenueGroupRepository.class, config);
        venues = JavAIPI.repository(TestVenueRepository.class, config);
        lazyOwners = JavAIPI.repository(TestLazyAssocOwnerRepository.class, config);
        eagerOwners = JavAIPI.repository(TestEagerAssocOwnerRepository.class, config);
        assocTargets = JavAIPI.repository(TestAssocTargetRepository.class, config);
        teams = JavAIPI.repository(TestTeamRepository.class, config);
        chapters = JavAIPI.repository(TestChapterRepository.class, config);
        shelves = JavAIPI.repository(TestShelfRepository.class, config);
        JavAIPI.repository(TestBookRepository.class, config);
        albums = JavAIPI.repository(TestAlbumRepository.class, config);
        imageCovers = JavAIPI.repository(TestImageCoverRepository.class, config);
    }

    /** Outside any unit of work: the root is detached, and so is everything reachable from it. */
    @Test
    void outsideATransactionTheWholeReturnedGraphIsDetached() {
        UUID id = savedGroup();

        TestVenueGroup loaded = groups.findById(id).orElseThrow();

        assertFalse(Hibernate.isInitialized(loaded.getVenues()),
                "an untouched association is uninitialized, which is the detached case's whole shape");
        // Nothing else to check: with the session closed there is no persistence context for any node of
        // this graph to belong to, so uniformity is structural rather than something to measure.
    }

    /** Inside one: the root is managed, and so is every node reached through it. */
    @Test
    void insideATransactionTheWholeReachedGraphIsManaged() {
        UUID id = savedGroup();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestVenueGroup loaded = groups.findById(id).orElseThrow();

            assertTrue(session.contains(loaded), "the root a repository returns must be managed");

            Hibernate.initialize(loaded.getVenues());
            TestVenue venue = loaded.getVenues().get(0);
            assertTrue(session.contains(venue), "...and so must a child reached through it");

            Hibernate.initialize(venue.getReviews());
            assertTrue(session.contains(venue.getReviews().get(0)),
                    "...and a grandchild, two hops out");
            return null;
        });
    }

    /**
     * {@code save()} returns the <b>managed</b> instance, as Spring Data JPA's does (OMI-275, Topic 1).
     *
     * <p>It used to return the caller's own instance, which was never managed. That was deliberate --
     * {@code merge()} left {@code @Transient} JavAI collection fields empty on the managed copy, so
     * returning it would have handed back an object whose collections looked wrong immediately after a save
     * -- and OMI-277 removed the reason by making JavAI collections native associations that {@code merge()}
     * carries across. Only {@code Point} fields are still transient, and {@code save} now copies those onto
     * the managed copy explicitly.
     *
     * <p>The consequence a caller feels: mutating what {@code save} returned, inside the unit of work, is
     * dirty-checked. Before, it was silently discarded.
     */
    @Test
    void saveReturnsTheManagedInstance() {
        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestVenueGroup group = new TestVenueGroup("save-returns-" + UUID.randomUUID());

            TestVenueGroup returned = (TestVenueGroup) groups.save(group);

            assertTrue(session.contains(returned),
                    "the instance save() returns must be managed, so mutating it is dirty-checked");
            return null;
        });
    }

    /** A {@code Point} survives on the returned instance, which is what made returning the managed copy
     *  safe: Hibernate does not carry a {@code <transient>} field across {@code merge()}. */
    @Test
    void aPointSetBeforeSaveIsStillOnTheInstanceSaveReturns() {
        TestVenue venue = new TestVenue("point-after-save-" + UUID.randomUUID(),
                new org.springframework.data.geo.Point(7.5, 8.5), java.util.List.of());

        TestVenue returned = (TestVenue) venues.save(venue);

        assertEquals(7.5, returned.getLocation().getX(),
                "a Point set before save must still be readable on what save returned");
        assertEquals(8.5, returned.getLocation().getY());
    }

    /**
     * The mixed-graph probe, and the reason this class leads with it.
     *
     * <p>Take the one shape that can produce an unmanaged object while a session is open -- {@code save}'s
     * return -- and give it a child that <em>is</em> managed, by loading that child first in the same unit of
     * work. If the result is an unmanaged root holding a managed child, a mixed graph is reachable.
     */
    @Test
    void aSavedRootHoldingAPreviouslyLoadedChildIsAMixedGraph() {
        UUID venueId = savedVenueAlone();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestVenue managedVenue = venues.findById(venueId).orElseThrow();
            assertTrue(session.contains(managedVenue), "loaded in this unit of work, so managed");

            TestVenueGroup group = new TestVenueGroup("mixed-graph-" + UUID.randomUUID());
            group.getVenues().add(managedVenue);
            TestVenueGroup returned = (TestVenueGroup) groups.save(group);

            boolean rootManaged = session.contains(returned);
            boolean childManaged = session.contains(returned.getVenues().get(0));

            assertEquals(rootManaged, childManaged,
                    "⚠️ a caller must not be handed a graph that is attached in one place and detached in "
                            + "another: root managed=" + rootManaged + ", child managed=" + childManaged);
            return null;
        });
    }

    /** JPA guarantees one instance per row per persistence context. Two reads, one unit of work, same object. */
    @Test
    void twoReadsInOneUnitOfWorkReturnTheSameInstance() {
        UUID id = savedGroup();

        JavAIPI.inTransaction(config, () -> {
            assertSame(groups.findById(id).orElseThrow(), groups.findById(id).orElseThrow(),
                    "the first-level cache must serve the second read");
            return null;
        });
    }

    /** ...and two reads in two units of work must not, or the cache is outliving its context. */
    @Test
    void twoReadsInSeparateUnitsOfWorkReturnDifferentInstances() {
        UUID id = savedGroup();

        TestVenueGroup first = groups.findById(id).orElseThrow();
        TestVenueGroup second = groups.findById(id).orElseThrow();

        assertEquals(first.getId(), second.getId());
        assertFalse(first == second, "a closed session's identity map must not leak into the next read");
    }

    // ---- the matrix: every relationship shape, both fetch modes -------------------------------

    /**
     * A lazy {@code @ManyToOne} comes back as an uninitialized proxy that answers its {@code @Id} without
     * touching the database, and is managed inside a unit of work like any other node.
     *
     * <p>The id-without-a-round-trip half is what makes the detached case usable at all: a caller can wire
     * associations by identity without initializing anything, which is the property
     * {@code AssociationGraphE2ETest} leans on.
     *
     * <p>⚠️ <b>It requires a public identifier getter</b>, which is worth recording because nothing says so
     * and the failure is a surprise. Hibernate serves the id from a proxy by overriding that getter, and it
     * cannot override a package-private one -- the call falls through to the uninitialized instance and
     * triggers a load instead, which outside a session means {@code LazyInitializationException} on what
     * looks like a free read. {@link TestAssocTarget#getId()} is public for exactly this reason; it was
     * package-private when this test was written, and this is how that was found.
     */
    @Test
    void aLazyManyToOneIsAnUninitializedProxyThatStillAnswersItsId() {
        TestAssocTarget target = (TestAssocTarget) assocTargets.save(new TestAssocTarget("lazy-target"));
        TestLazyAssocOwner owner = new TestLazyAssocOwner("lazy-owner", target);
        UUID id = ((TestLazyAssocOwner) lazyOwners.save(owner)).getId();

        TestLazyAssocOwner detached = lazyOwners.findById(id).orElseThrow();

        assertFalse(Hibernate.isInitialized(detached.getTarget()), "LAZY means an uninitialized proxy");
        assertEquals(target.getId(), detached.getTarget().getId(), "...whose id is free");
        assertThrows(LazyInitializationException.class, () -> detached.getTarget().getLabel(),
                "...and which cannot be initialized once the session is gone");
    }

    /** The same association inside a unit of work: managed, and initializable. */
    @Test
    void aLazyManyToOneIsManagedAndInitializableInsideAUnitOfWork() {
        TestAssocTarget target = (TestAssocTarget) assocTargets.save(new TestAssocTarget("lazy-in-session"));
        UUID id = ((TestLazyAssocOwner) lazyOwners.save(new TestLazyAssocOwner("owner", target))).getId();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestLazyAssocOwner loaded = lazyOwners.findById(id).orElseThrow();

            assertTrue(session.contains(loaded), "the root is managed");
            assertEquals("lazy-in-session", loaded.getTarget().getLabel(), "the proxy initializes here");
            assertTrue(session.contains(Hibernate.unproxy(loaded.getTarget())),
                    "...and the resolved target is managed too -- no mixed graph through a lazy hop");
            return null;
        });
    }

    /** An eager {@code @ManyToOne} is loaded, not proxied, and is managed alongside its owner. */
    @Test
    void anEagerManyToOneIsLoadedAndManagedWithItsOwner() {
        TestAssocTarget target = (TestAssocTarget) assocTargets.save(new TestAssocTarget("eager-target"));
        UUID id = ((TestEagerAssocOwner) eagerOwners.save(new TestEagerAssocOwner("eager-owner", target))).getId();

        TestEagerAssocOwner detached = eagerOwners.findById(id).orElseThrow();
        assertTrue(Hibernate.isInitialized(detached.getTarget()), "EAGER means loaded, outside a session too");

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestEagerAssocOwner loaded = eagerOwners.findById(id).orElseThrow();
            assertTrue(session.contains(loaded) && session.contains(Hibernate.unproxy(loaded.getTarget())),
                    "owner and eagerly-fetched target must share one attachment state");
            return null;
        });
    }

    /** A cascading {@code @OneToOne}, two hops down a {@code @OneToMany}: every node, one attachment state. */
    @Test
    void aOneToOneOwnerAndItsTargetShareOneAttachmentState() {
        TestMember member = new TestMember("one-to-one-member");
        member.setProfile(new TestProfile("one-to-one-handle", "one-to-one-city"));
        TestTeam team = new TestTeam("one-to-one-team");
        team.getMembers().add(member);
        UUID id = ((TestTeam) teams.save(team)).getId();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestTeam loaded = teams.findById(id).orElseThrow();
            Hibernate.initialize(loaded.getMembers());
            TestMember loadedMember = loaded.getMembers().get(0);

            assertTrue(session.contains(loadedMember), "the @OneToOne owner is managed");
            assertTrue(session.contains(Hibernate.unproxy(loadedMember.getProfile())),
                    "...and so is the @OneToOne target");
            return null;
        });
    }

    /** A self-referential association -- the shape most likely to make a walk visit a node twice. */
    @Test
    void aSelfReferentialAssociationIsUniformlyAttached() {
        TestChapter continuation = new TestChapter("continuation", "prose two");
        TestChapter first = new TestChapter("first", "prose one");
        first.setContinuation(continuation);
        UUID id = ((TestChapter) chapters.save(first)).getId();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestChapter loaded = chapters.findById(id).orElseThrow();
            assertTrue(session.contains(loaded));
            assertTrue(session.contains(Hibernate.unproxy(loaded.getContinuation())),
                    "a self-referential hop must not produce a mixed graph either");
            return null;
        });
    }

    /** {@code @ManyToMany}, and the JavAI collection that rides it. */
    @Test
    void aManyToManyCollectionAndItsMembersShareOneAttachmentState() {
        TestShelf shelf = new TestShelf("attachment-shelf-" + UUID.randomUUID());
        shelf.getBooks().add(new TestBook("attachment-book"));
        UUID id = ((TestShelf) shelves.save(shelf)).getId();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestShelf loaded = shelves.findById(id).orElseThrow();
            Hibernate.initialize(loaded.getBooks());
            assertTrue(session.contains(loaded) && session.contains(loaded.getBooks().get(0)),
                    "a @ManyToMany member must share its owner's attachment state");
            return null;
        });
    }

    /**
     * {@code @Any} -- the polymorphic to-one, whose target may be any of several unrelated entities.
     *
     * <p>Worth its own case rather than assuming it behaves like {@code @ManyToOne}: its target is resolved
     * through a discriminator rather than a foreign key, and JavAI's own walks classify fields by declared
     * type, which for {@code @Any} is deliberately an interface.
     */
    @Test
    void anAnyAssociationTargetSharesItsOwnersAttachmentState() {
        TestImageCover cover = (TestImageCover) imageCovers.save(new TestImageCover("any-cover"));
        UUID id = ((TestAlbum) albums.save(new TestAlbum("any-album", cover))).getId();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestAlbum loaded = albums.findById(id).orElseThrow();
            assertTrue(session.contains(loaded), "the @Any owner is managed");
            assertTrue(session.contains(Hibernate.unproxy(loaded.getCover())),
                    "...and so is whatever the discriminator resolved to");
            return null;
        });
    }

    private static Session ambient() {
        Session session = JavAITransactionScope.current(JavAIPI.sessionFactory(config));
        if (session == null) {
            throw new IllegalStateException("no ambient session -- this must run inside JavAIPI.inTransaction");
        }
        return session;
    }

    private static UUID savedGroup() {
        TestVenue venue = new TestVenue("attachment-venue-" + UUID.randomUUID(), null,
                java.util.List.of(new TestReview("r", 5)));
        TestVenueGroup group = new TestVenueGroup("attachment-group-" + UUID.randomUUID());
        group.getVenues().add(venue);
        groups.save(group);
        return group.getId();
    }

    private static UUID savedVenueAlone() {
        TestVenue venue = new TestVenue("standalone-venue-" + UUID.randomUUID(), null, java.util.List.of());
        venues.save(venue);
        return venue.getId();
    }
}
