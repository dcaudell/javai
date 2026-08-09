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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four JPA mapping shapes OMI-275 listed as unmeasured (Topic 1, closing it out).
 *
 * <p>{@code @Basic(fetch = LAZY)}, {@code @ElementCollection}, {@code @MapsId} and inheritance-hierarchy
 * fetching were named in the ticket as things nothing was claimed about in either direction. This class
 * makes a claim about each, from a measurement.
 *
 * <p>Two of them come out differently from the rest of the matrix, and both are recorded as they are rather
 * than filed as defects: a limitation that is written down is a supported answer, and one that is discovered
 * by a consumer is not. Which of the two they turn out to be is the point of measuring.
 */
@Testcontainers
class JpaMappingConformanceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestLazyBasicOwnerRepository lazyBasics;
    private static TestElementCollectionOwnerRepository elementCollections;
    private static TestVehicleRepository vehicles;
    private static TestPassportRepository passports;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                // ⚠️ The subclass, named explicitly. Registering a repository for the inheritance ROOT does
                // not bring its subclasses in: JavAI discovers related types by walking an entity's fields,
                // and a subclass is not reachable that way -- nor discoverable by reflection at all without
                // scanning. Without this, saving a TestTruck fails with Hibernate's "Unknown entity type",
                // which does not obviously point at the fix. entityPackages(...) covers it for a whole
                // package; this is the per-type form.
                .entityType(TestTruck.class)
                .build();
        lazyBasics = JavAIPI.repository(TestLazyBasicOwnerRepository.class, config);
        elementCollections = JavAIPI.repository(TestElementCollectionOwnerRepository.class, config);
        vehicles = JavAIPI.repository(TestVehicleRepository.class, config);
        passports = JavAIPI.repository(TestPassportRepository.class, config);
    }

    // ---- @ElementCollection ---------------------------------------------------------------------

    /** A collection of basic values round-trips, and is lazy like any other collection. */
    @Test
    void anElementCollectionRoundTripsAndIsLazy() {
        TestElementCollectionOwner owner = new TestElementCollectionOwner("aliases-" + UUID.randomUUID());
        owner.getAliases().add("first");
        owner.getAliases().add("second");
        UUID id = ((TestElementCollectionOwner) elementCollections.save(owner)).getId();

        TestElementCollectionOwner detached = elementCollections.findById(id).orElseThrow();
        assertEquals(false, Hibernate.isInitialized(detached.getAliases()),
                "@ElementCollection(LAZY) must be lazy like any other collection");

        List<String> aliases = JavAIPI.inTransaction(config, () ->
                List.copyOf(elementCollections.findById(id).orElseThrow().getAliases()));
        assertEquals(List.of("first", "second"), aliases, "...and round-trip its values in order");
    }

    // ---- inheritance ----------------------------------------------------------------------------

    /**
     * A {@code JOINED} subclass round-trips as itself, read through the root's repository.
     *
     * <p>⚠️ Requires the subclass to be registered explicitly -- see the configuration above. That is a real
     * constraint on any consumer with an inheritance hierarchy, and the failure without it names the subclass
     * as an unknown entity rather than saying it needed registering.
     */
    @Test
    void aJoinedSubclassRoundTripsPolymorphically() {
        TestTruck truck = new TestTruck("truck-" + UUID.randomUUID(), 3);
        UUID id = ((TestVehicle) vehicles.save(truck)).getId();

        TestVehicle loaded = vehicles.findById(id).orElseThrow();

        assertInstanceOf(TestTruck.class, loaded,
                "reading through the root repository must return the concrete subclass");
        assertEquals(3, ((TestTruck) loaded).getAxles(), "the subclass's own column comes back with it");
        assertEquals(truck.getLabel(), loaded.getLabel(), "...and the root's");
    }

    /** {@code findAll} over the root sees subclass rows, which is what makes the hierarchy a hierarchy. */
    @Test
    void findAllOverTheRootSeesSubclassRows() {
        TestTruck truck = new TestTruck("findall-truck-" + UUID.randomUUID(), 4);
        vehicles.save(truck);

        assertTrue(vehicles.findAll().stream().anyMatch(v -> v.getId().equals(truck.getId())),
                "a polymorphic findAll must include subclass instances");
    }

    // ---- @MapsId --------------------------------------------------------------------------------

    /**
     * ⚠️ {@code @MapsId} works, and the id it derives wins over JavAI's own assignment -- which is not the
     * obvious outcome and is the reason this was worth measuring.
     *
     * <p>The two rules are in direct tension. JavAI's identity contract is an application-assigned
     * {@code UUID}, and {@code save()} walks the graph assigning a random one to any null {@code @Id} before
     * Hibernate sees it. {@code @MapsId} says the id must instead be copied from the association. If JavAI's
     * assignment won, the child would be written under an id unrelated to its parent and the shared-primary-key
     * mapping would be silently broken -- rows that look fine and join to nothing.
     */
    @Test
    void mapsIdDerivesTheChildsIdFromItsParentRatherThanJavAIsAssignment() {
        TestVehicle vehicle = new TestVehicle("mapsid-vehicle-" + UUID.randomUUID());
        TestPassport passport = new TestPassport(vehicle, "serial-" + UUID.randomUUID());

        TestPassport saved = (TestPassport) passports.save(passport);

        assertNotNull(saved.getId(), "the child must have an id");
        assertEquals(vehicle.getId(), saved.getId(),
                "@MapsId means the child's primary key IS the parent's -- JavAI's own assignment must not win");

        TestPassport reloaded = passports.findById(vehicle.getId()).orElseThrow();
        assertEquals(passport.getSerial(), reloaded.getSerial(), "...and it round-trips under that id");
    }

    // ---- @Basic(fetch = LAZY) -------------------------------------------------------------------

    /**
     * ⚠️ A lazy basic attribute is <b>loaded eagerly</b>, and this is a documented limitation rather than a
     * defect to file.
     *
     * <p>Unlike a lazy association, a lazy basic has no proxy to stand in for it: Hibernate defers it only by
     * rewriting field access, which requires build-time or agent bytecode enhancement. JavAI builds its
     * {@code SessionFactory} without that enhancement, so {@code @Basic(fetch = LAZY)} degrades to eager --
     * the value is read with the row.
     *
     * <p>Recorded rather than fixed because the degradation is <em>safe</em>: the attribute is correct, just
     * fetched sooner than asked. That makes it unlike the two silent defects this stack removed, where the
     * value was missing rather than early. A consumer with a genuinely large column should split it into its
     * own entity behind a lazy {@code @OneToOne}, which works here today.
     */
    @Test
    void aLazyBasicAttributeIsFetchedEagerlyBecauseThereIsNoBytecodeEnhancement() {
        String bulk = "bulk-" + UUID.randomUUID();
        UUID id = ((TestLazyBasicOwner) lazyBasics.save(new TestLazyBasicOwner("lazy-basic", bulk))).getId();

        TestLazyBasicOwner detached = lazyBasics.findById(id).orElseThrow();

        assertEquals(bulk, detached.getBulk(),
                "without bytecode enhancement a lazy basic is simply loaded with its row -- readable on a "
                        + "detached entity, where a genuinely deferred one would not be");
    }
}
