package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.hibernate.Hibernate;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conformance cells OMI-275 named and never exercised (OMI-279).
 *
 * <p>{@link AttachmentConformanceTest} established the shape of the question and answered most of it: a
 * caller never holds a graph that is attached in one place and detached in another. What it did not cover
 * were the inverse side of a {@code @OneToOne}, a non-optional to-one, a bidirectional pair as a pair,
 * {@code @Any} under {@code LAZY}, and the eager variants of the two collection mappings.
 *
 * <p>None of those was suspected of being broken. They are measured because of the base rate: every gap
 * that got measured in OMI-275 turned out to contain something, and four of the five defects it fixed were
 * invisible until a number went on them.
 *
 * <p>Attachment is read from Hibernate directly ({@code Session.contains}), never inferred from whether a
 * traversal happens to throw — the same method {@link AttachmentConformanceTest} uses, and for the same
 * reason: a traversal that succeeds proves the object is reachable, not that it is managed.
 */
@Testcontainers
class RemainingFetchCellsConformanceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestBadgeHolderRepository holders;
    private static TestBadgeRepository badges;
    private static TestDepartmentRepository departments;
    private static TestEmployeeRepository employees;
    private static TestEagerHubRepository eagerHubs;
    private static TestLazyAnyOwnerRepository lazyAnyOwners;
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
        holders = JavAIPI.repository(TestBadgeHolderRepository.class, config);
        badges = JavAIPI.repository(TestBadgeRepository.class, config);
        departments = JavAIPI.repository(TestDepartmentRepository.class, config);
        employees = JavAIPI.repository(TestEmployeeRepository.class, config);
        eagerHubs = JavAIPI.repository(TestEagerHubRepository.class, config);
        JavAIPI.repository(TestBookRepository.class, config);
        lazyAnyOwners = JavAIPI.repository(TestLazyAnyOwnerRepository.class, config);
        imageCovers = JavAIPI.repository(TestImageCoverRepository.class, config);
    }

    // ---- cell 1: the inverse side of a @OneToOne ------------------------------------------------

    /**
     * Reading the inverse side as a root, and reaching the owning side back through it.
     *
     * <p>The foreign key lives on the owner's table, so this side has no column to proxy from. Whatever
     * Hibernate does about laziness here, the attachment rule must still hold: what is reachable inside a
     * unit of work is managed.
     */
    @Test
    void theInverseSideOfAOneToOneIsUniformlyAttached() {
        UUID badgeId = savedHolder().getBadge().getId();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestBadge badge = badges.findById(badgeId).orElseThrow();

            assertTrue(session.contains(badge), "the inverse side reads as a root like any entity");
            assertTrue(session.contains(Hibernate.unproxy(badge.getHolder())),
                    "...and the owning side reached back through it shares its attachment state");
            return null;
        });
    }

    /** What the inverse side actually does about {@code LAZY}, recorded rather than assumed. */
    @Test
    void theInverseSideOfAOneToOneIsFetchedEagerlyDespiteAskingForLazy() {
        UUID badgeId = savedHolder().getBadge().getId();

        TestBadge detached = badges.findById(badgeId).orElseThrow();

        assertTrue(Hibernate.isInitialized(detached.getHolder()),
                "an inverse @OneToOne has no foreign key of its own to proxy from, so Hibernate must look to "
                        + "know whether the other row exists -- LAZY is not honoured, and the value arrives "
                        + "with the row. Correct-but-early, like @Basic(fetch = LAZY); never missing");
    }

    // ---- cell 2: optional = false ---------------------------------------------------------------

    /**
     * ⚠️ {@code optional = false} on the <b>owning</b> side <b>is</b> honoured — the opposite of what this
     * test was written expecting, and worth recording as such.
     *
     * <p>The reasoning that predicted otherwise: a proxy stands in for a row the mapping promises exists, so
     * if it did not, the caller would learn only on first dereference — therefore Hibernate must fetch. That
     * is true of the <em>inverse</em> side and false here, because the owning side holds the foreign key: a
     * non-null FK column is itself the proof the row exists, so a proxy promises nothing it cannot keep.
     *
     * <p>Which is a good reminder that "Hibernate cannot proxy a non-optional to-one" is folklore stated
     * without its precondition, and repeating it would have put a wrong limitation in the guidance.
     */
    @Test
    void aNonOptionalOneToOneOnTheOwningSideIsStillLazy() {
        UUID id = savedHolder().getId();

        TestBadgeHolder detached = holders.findById(id).orElseThrow();

        assertFalse(Hibernate.isInitialized(detached.getSeal()),
                "the owning side holds the FK, so optional = false costs nothing and LAZY is honoured");
    }

    /** The optional one on the same entity, for contrast: also lazy, so optionality changes nothing here. */
    @Test
    void theOptionalOneToOneOnTheSameEntityIsAlsoLazy() {
        UUID id = savedHolder().getId();

        TestBadgeHolder detached = holders.findById(id).orElseThrow();

        assertFalse(Hibernate.isInitialized(detached.getBadge()),
                "optional and non-optional owning-side to-ones behave identically");
    }

    /**
     * ⚠️ The cell nobody listed, found by isolating the one above: <b>a to-one whose target class is
     * {@code final} cannot be lazy</b>, whatever the annotation says.
     *
     * <p>Hibernate builds a proxy by subclassing the entity, and a final class cannot be subclassed, so the
     * association is fetched with its owner. Silent — nothing in the mapping, the annotation or the log
     * suggests it, and the value is correct, merely early.
     *
     * <p>This is how the {@code optional = false} case above went wrong on the first run: every fixture in
     * this file was {@code final}, so <em>both</em> to-ones came back eager and finality was mistaken for
     * optionality. {@link TestSeal} and {@link TestWaxSeal} are otherwise identical targets of otherwise
     * identical mappings; the only difference is the keyword.
     */
    @Test
    void aToOneWhoseTargetClassIsFinalCannotBeLazy() {
        UUID id = savedHolder().getId();

        TestBadgeHolder detached = holders.findById(id).orElseThrow();

        assertTrue(Hibernate.isInitialized(detached.getWaxSeal()),
                "a final entity class cannot be proxied, so LAZY silently degrades to eager");
        assertFalse(Hibernate.isInitialized(detached.getSeal()),
                "...while its non-final twin, mapped identically, is proxied -- isolating the keyword as the "
                        + "only cause");
    }

    // ---- cell 3: a bidirectional pair, as a pair -------------------------------------------------

    /**
     * Both ends of one relationship, entered from the parent: the child reached through the collection and
     * the parent reached back from that child must be the same instance, managed together.
     *
     * <p>Identity is the real assertion. Two ends of a bidirectional mapping are two mappings over one
     * foreign key, and if they resolved to different instances a caller could mutate one and read the other.
     */
    @Test
    void bothEndsOfABidirectionalPairResolveToTheSameInstances() {
        UUID departmentId = savedDepartment();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestDepartment department = departments.findById(departmentId).orElseThrow();
            Hibernate.initialize(department.getEmployees());
            TestEmployee employee = department.getEmployees().get(0);

            assertSame(department, Hibernate.unproxy(employee.getDepartment()),
                    "the parent reached back from its own child must be the same instance");
            assertTrue(session.contains(employee) && session.contains(department),
                    "...and both ends share one attachment state");
            return null;
        });
    }

    /** The same pair entered from the child end, which is the owning side. */
    @Test
    void aBidirectionalPairEnteredFromTheChildResolvesToTheSameInstances() {
        UUID departmentId = savedDepartment();

        UUID employeeId = JavAIPI.inTransaction(config, () -> {
            TestDepartment department = departments.findById(departmentId).orElseThrow();
            Hibernate.initialize(department.getEmployees());
            return department.getEmployees().get(0).getId();
        });

        JavAIPI.inTransaction(config, () -> {
            TestEmployee employee = employees.findById(employeeId).orElseThrow();
            TestDepartment fromChild = (TestDepartment) Hibernate.unproxy(employee.getDepartment());
            Hibernate.initialize(fromChild.getEmployees());

            assertSame(employee, fromChild.getEmployees().get(0),
                    "entering from the owning side must land on the same objects as entering from the parent");
            return null;
        });
    }

    // ---- cell 4: @Any under LAZY -----------------------------------------------------------------

    /**
     * {@code @Any} resolves its target through a discriminator string plus a bare id, so the concrete class
     * is not known until the discriminator is read. Whether Hibernate can proxy something whose type it has
     * not resolved is the question, and the annotation does not answer it.
     */
    @Test
    void aLazyAnyAssociationIsUniformlyAttachedWhateverItsFetchMode() {
        TestImageCover cover = (TestImageCover) imageCovers.save(new TestImageCover("lazy-any-cover"));
        UUID id = ((TestLazyAnyOwner) lazyAnyOwners.save(
                new TestLazyAnyOwner("lazy-any-owner", cover))).getId();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestLazyAnyOwner loaded = lazyAnyOwners.findById(id).orElseThrow();

            assertTrue(session.contains(loaded), "the @Any owner is managed");
            assertTrue(session.contains(Hibernate.unproxy(loaded.getCover())),
                    "...and so is whatever the discriminator resolved to, under LAZY as under EAGER");
            return null;
        });
    }

    /** And the target it resolves to is the right one -- polymorphism intact under LAZY. */
    @Test
    void aLazyAnyAssociationResolvesToTheConcreteTargetType() {
        TestImageCover cover = (TestImageCover) imageCovers.save(new TestImageCover("lazy-any-typed"));
        UUID id = ((TestLazyAnyOwner) lazyAnyOwners.save(
                new TestLazyAnyOwner("lazy-any-typed-owner", cover))).getId();

        JavAIPI.inTransaction(config, () -> {
            TestLazyAnyOwner loaded = lazyAnyOwners.findById(id).orElseThrow();
            Object resolved = Hibernate.unproxy(loaded.getCover());
            assertInstanceOf(TestImageCover.class, resolved,
                    "the discriminator must still resolve to the concrete type it named");
            assertEquals(cover.getId(), ((TestImageCover) resolved).getId());
            return null;
        });
    }

    // ---- cell 5: EAGER collections ---------------------------------------------------------------

    /**
     * Eagerly-fetched collections arrive with their root whether the caller asked or not, which makes this
     * the shape where a half-attached graph would show up if one could.
     */
    @Test
    void eagerCollectionsAndTheirMembersShareOneAttachmentState() {
        UUID id = savedEagerHub();

        JavAIPI.inTransaction(config, () -> {
            Session session = ambient();
            TestEagerHub loaded = eagerHubs.findById(id).orElseThrow();

            assertTrue(Hibernate.isInitialized(loaded.getOwned()), "@OneToMany(EAGER) arrives loaded");
            assertTrue(Hibernate.isInitialized(loaded.getShared()), "@ManyToMany(EAGER) too");
            assertTrue(session.contains(loaded)
                            && session.contains(loaded.getOwned().get(0))
                            && session.contains(loaded.getShared().get(0)),
                    "root and eagerly-fetched members must share one attachment state");
            return null;
        });
    }

    /** ...and outside a unit of work they are loaded rather than lazy, which is what EAGER promises. */
    @Test
    void eagerCollectionsAreReadableOnADetachedRoot() {
        UUID id = savedEagerHub();

        TestEagerHub detached = eagerHubs.findById(id).orElseThrow();

        assertEquals(1, detached.getOwned().size(),
                "an EAGER collection is the one shape that IS traversable on a detached entity");
        assertEquals(1, detached.getShared().size());
    }

    private static Session ambient() {
        Session session = JavAITransactionScope.current(JavAIPI.sessionFactory(config));
        if (session == null) {
            throw new IllegalStateException("no ambient session -- this must run inside JavAIPI.inTransaction");
        }
        return session;
    }

    private static TestBadgeHolder savedHolder() {
        TestBadgeHolder holder = new TestBadgeHolder("holder-" + UUID.randomUUID(),
                new TestBadge("badge-" + UUID.randomUUID()), new TestSeal("seal-stamp"),
                new TestWaxSeal("wax-impression"));
        return (TestBadgeHolder) holders.save(holder);
    }

    private static UUID savedDepartment() {
        TestDepartment department = new TestDepartment("dept-" + UUID.randomUUID());
        department.hire(new TestEmployee("employee-" + UUID.randomUUID()));
        return ((TestDepartment) departments.save(department)).getId();
    }

    private static UUID savedEagerHub() {
        TestEagerHub hub = new TestEagerHub("eager-hub-" + UUID.randomUUID());
        hub.getOwned().add(new TestBook("eager-owned-" + UUID.randomUUID()));
        hub.getShared().add(new TestBook("eager-shared-" + UUID.randomUUID()));
        return ((TestEagerHub) eagerHubs.save(hub)).getId();
    }
}
