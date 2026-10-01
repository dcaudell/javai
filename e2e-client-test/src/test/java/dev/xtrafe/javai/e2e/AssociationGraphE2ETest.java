package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.assoc.AssocAnyPlain;
import dev.xtrafe.javai.e2e.domain.assoc.AssocAnyTarget;
import dev.xtrafe.javai.e2e.domain.assoc.AssocAnyVectorizable;
import dev.xtrafe.javai.e2e.domain.assoc.AssocBiChild;
import dev.xtrafe.javai.e2e.domain.assoc.AssocBiParent;
import dev.xtrafe.javai.e2e.domain.assoc.AssocChainMiddle;
import dev.xtrafe.javai.e2e.domain.assoc.AssocChainTop;
import dev.xtrafe.javai.e2e.domain.assoc.AssocHub;
import dev.xtrafe.javai.e2e.domain.assoc.AssocLeaf;
import dev.xtrafe.javai.e2e.domain.assoc.AssocNestedAny;
import dev.xtrafe.javai.e2e.domain.assoc.AssocSelfNode;
import dev.xtrafe.javai.e2e.domain.assoc.PlainLeaf;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.e2e.environment.MonolithicContainer;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.EmbeddingVector;
import org.junit.jupiter.api.BeforeAll;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-161, end to end: every JPA association shape a {@code @JavAIVectorizable} entity can declare, against
 * the real Postgres and real embeddings, with the entities <b>woven at load time</b> exactly as a
 * downstream consumer's own are. See {@link AssocHub} for the matrix and why each shape is in it.
 *
 * <p><b>The regression.</b> A {@code FetchType.LAZY} singular association to another vectorizable made
 * {@code save()} fail with {@code null value in column "owner_id" ... violates not-null constraint}:
 * {@code save()} merges the caller's detached instance, the merged copy holds an uninitialized Hibernate
 * proxy for that association, and the proxy satisfies {@code instanceof JavAIVectorizable} while holding no
 * field state -- so the id read reflectively off it was {@code null}. Every eager shape here is the matched
 * control that always worked, so a failure localizes to fetch mode rather than to associations generally.
 *
 * <p><b>Three layers of assertion</b>, because "it didn't throw" is the weakest possible claim about a
 * persistence bug and was never the thing at risk:
 * <ol>
 *   <li><b>Runtime behavior</b> -- saves succeed, vectors compute, summary propagation still works through a
 *       lazy association, and an untouched lazy association is <em>not</em> silently initialized by the
 *       vector walk (a fix that "worked" by initializing everything would be a per-save SELECT, and an
 *       embedding call, for entities nobody asked for).</li>
 *   <li><b>Database structure</b> -- the FK columns and join tables each annotation is supposed to generate
 *       actually exist, and no vector row is ever keyed by a proxy's generated class name.</li>
 *   <li><b>Persisted and retrieved values</b> -- every vectorizable in the graph has exactly one row per
 *       field with a non-null {@code owner_id}, the stored vector matches the recomputed one, and the whole
 *       graph reloads with its field values and association targets intact.</li>
 * </ol>
 */
class AssociationGraphE2ETest {

    private static AssocHub savedHub;
    private static AssocLeaf mandatoryLeaf;
    private static AssocLeaf lazyManyToOneLeaf;
    private static AssocLeaf summaryLeaf;
    private static PlainLeaf plainLeaf;
    private static AssocAnyVectorizable anyVectorizableTarget;
    private static AssocAnyPlain anyPlainTarget;
    private static AssocNestedAny nestedAnyTarget;

    @BeforeAll
    static void saveTheWholeMatrix() {
        JavAIEnvironment.ensureRunning();

        mandatoryLeaf = JavAIEnvironment.postgresAssocLeafRepository().save(new AssocLeaf("mandatory target"));
        lazyManyToOneLeaf = JavAIEnvironment.postgresAssocLeafRepository().save(new AssocLeaf("lazy many-to-one target"));
        // Built with its own nested @Any before being saved, so the nested target is two hops from any
        // repository anyone asks for: hub -> summaryLeaf -> discriminator (OMI-212).
        summaryLeaf = new AssocLeaf("summary target");
        nestedAnyTarget = new AssocNestedAny("nested any target");
        summaryLeaf.setNestedAny(nestedAnyTarget);
        summaryLeaf = JavAIEnvironment.postgresAssocLeafRepository().save(summaryLeaf);
        AssocLeaf eagerManyToOneLeaf =
                JavAIEnvironment.postgresAssocLeafRepository().save(new AssocLeaf("eager many-to-one target"));
        plainLeaf = JavAIEnvironment.postgresPlainLeafRepository().save(new PlainLeaf("plain target"));

        AssocHub hub = new AssocHub("association matrix hub", mandatoryLeaf);
        hub.setLazyManyToOne(lazyManyToOneLeaf);
        hub.setEagerManyToOne(eagerManyToOneLeaf);
        hub.setSummaryLazyManyToOne(summaryLeaf);
        hub.setLazyPlainTarget(plainLeaf);
        hub.setLazyOneToOne(new AssocLeaf("lazy one-to-one target"));
        hub.setEagerOneToOne(new AssocLeaf("eager one-to-one target"));
        hub.getLazyOneToMany().add(new AssocLeaf("lazy one-to-many member"));
        hub.getLazyManyToMany().add(new AssocLeaf("lazy many-to-many member"));
        hub.getSummaryJavAICollection().add(new AssocLeaf("javai collection member"));

        // @Any (OMI-212). Deliberately mixed: the eager reference resolves to a vectorizable target and the
        // lazy one to a plain target, so a single save exercises both sides of the polymorphism at once and
        // neither is only ever seen in isolation.
        anyVectorizableTarget = new AssocAnyVectorizable("eager any vectorizable target");
        anyPlainTarget = new AssocAnyPlain("lazy any plain target");
        hub.setEagerAny(anyVectorizableTarget);
        hub.setEagerAnyPlain(anyPlainTarget);
        hub.setLazyAny(new AssocAnyVectorizable("lazy any target"));
        hub.setSummaryLazyAny(new AssocAnyVectorizable("summary any target"));
        hub.getManyToAny().add(new AssocAnyVectorizable("many-to-any vectorizable member"));
        hub.getManyToAny().add(new AssocAnyPlain("many-to-any plain member"));

        savedHub = JavAIEnvironment.postgresAssocHubRepository().save(hub);
    }

    // ---- 1. runtime behavior ----------------------------------------------------------------------

    /** The headline regression: this exact save threw before the fix. */
    @Test
    void theWholeAssociationMatrixSaves() {
        assertNotNull(savedHub.getId());
        assertNotNull(savedHub.getLazyManyToOne().getId());
        assertNotNull(savedHub.getMandatoryLazyManyToOne().getId());
    }

    /** A lazy singular association to a vectorizable, saved on its own, with nothing else in the graph --
     *  the ticket's minimal two-class repro, reduced as far as it goes. */
    @Test
    void minimalLazyAssociationToAnotherVectorizableSaves() {
        AssocLeaf target = JavAIEnvironment.postgresAssocLeafRepository().save(new AssocLeaf("minimal target"));
        AssocHub minimal = new AssocHub("minimal owner", target);
        minimal.setLazyManyToOne(target);

        AssocHub saved = JavAIEnvironment.postgresAssocHubRepository().save(minimal);
        assertNotNull(saved.getId());
        assertEquals(1, vectorRowCount(saved.getId(), AssocHub.class, "label"));
    }

    /** Every vectorizable in the graph must still produce vectors after the round trip. The associated
     *  leaf is loaded through its own repository rather than dereferenced off the detached hub -- see
     *  {@link #touchingAnUninitializedLazyAssociationOutsideASessionThrows} for why that distinction is
     *  real and deliberate. */
    @Test
    void everyVectorizableInTheGraphComputesItsVectors() {
        AssocHub reloaded = JavAIEnvironment.postgresAssocHubRepository().findById(savedHub.getId()).orElseThrow();

        EmbeddingVector hubVector = ((JavAIVectorizable) reloaded).vector();
        assertNotNull(hubVector);
        assertTrue(hubVector.dims() > 0);

        AssocLeaf leaf = JavAIEnvironment.postgresAssocLeafRepository()
                .findById(lazyManyToOneLeaf.getId()).orElseThrow();
        assertNotNull(((JavAIVectorizable) leaf).vector());

        // summaryVector() on an instance whose @Summary children are real objects -- the ordinary case.
        assertNotNull(((JavAIVectorizable) savedHub).summaryVector());
    }

    /**
     * A real constraint of combining {@code @Summary} with {@code FetchType.LAZY}, newly reachable now that
     * the save itself works: {@code summaryVector()} has to read each {@code @Summary} child's own summary,
     * so on a <em>detached</em> entity whose summary child is still an uninitialized proxy, it fails with
     * {@code LazyInitializationException} like any other lazy dereference.
     *
     * <p>Asserted rather than "fixed", deliberately. Silently treating an unloadable child as contributing
     * nothing would make {@code summaryVector()} depend on session state -- the identical object graph would
     * summarize differently depending on whether it happened to be loaded eagerly -- which is a worse
     * failure than a loud one, and would violate the decay-weighted formula in doc/spec/vector-core.md.
     * A consumer that wants summaries off detached instances should fetch that association eagerly, or read
     * the summary while the entity is still managed.
     */
    @Test
    void summaryVectorOnADetachedEntityWithALazySummaryChildFailsLoudly() {
        AssocHub reloaded = JavAIEnvironment.postgresAssocHubRepository().findById(savedHub.getId()).orElseThrow();

        assertThrows(LazyInitializationException.class, () -> ((JavAIVectorizable) reloaded).summaryVector(),
                "a @Summary child behind an uninitialized proxy must fail loudly rather than be silently "
                        + "dropped from the summary");
    }

    /**
     * The flip side of the fix, and a genuine consumer-facing constraint rather than a defect: a repository
     * returns a <em>detached</em> entity, so an association still sitting behind an uninitialized proxy
     * cannot be dereferenced afterwards -- ordinary {@code LazyInitializationException}, exactly as any
     * Spring Data JPA user would see.
     *
     * <p>Asserted rather than avoided because it is what makes "skip uninitialized proxies" the only
     * available strategy: at vector-write time JavAI is inside the session and could initialize, but doing
     * so would load and re-embed entities the caller never asked for; by the time the caller could observe
     * the association, the session is gone and nothing could initialize it anyway. The identifier stays
     * readable throughout -- Hibernate serves it from the proxy without a database round trip -- which is
     * why {@link #theWholeGraphRoundTripsWithEveryAssociationTargetIntact} can assert the wiring by id.
     */
    @Test
    void touchingAnUninitializedLazyAssociationOutsideASessionThrows() {
        AssocHub reloaded = JavAIEnvironment.postgresAssocHubRepository().findById(savedHub.getId()).orElseThrow();

        // The id is free -- no initialization required.
        assertEquals(lazyManyToOneLeaf.getId(), reloaded.getLazyManyToOne().getId());

        assertThrows(LazyInitializationException.class, () -> reloaded.getLazyManyToOne().getLabel(),
                "dereferencing an uninitialized lazy association on a detached entity is a JPA error, "
                        + "not something JavAI should paper over by eagerly loading everything it walks");
    }

    /** ...and the values themselves do round-trip, loaded the way a consumer actually loads them. */
    @Test
    void associationTargetValuesRoundTripWhenLoadedThroughTheirOwnRepository() {
        assertEquals("lazy many-to-one target", JavAIEnvironment.postgresAssocLeafRepository()
                .findById(lazyManyToOneLeaf.getId()).orElseThrow().getLabel());
        assertEquals("mandatory target", JavAIEnvironment.postgresAssocLeafRepository()
                .findById(mandatoryLeaf.getId()).orElseThrow().getLabel());
        assertEquals("summary target", JavAIEnvironment.postgresAssocLeafRepository()
                .findById(summaryLeaf.getId()).orElseThrow().getLabel());
    }

    /**
     * The fix's load-bearing behavioral choice, asserted rather than assumed: an untouched lazy association
     * is skipped by the vector walk, not initialized by it. Initializing "just to be safe" would mean a
     * SELECT -- and, since a freshly-loaded entity recomputes lazily, potentially a real embedding call --
     * on every save, for every association, for entities the caller never looked at.
     */
    @Test
    void savingDoesNotForceUninitializedLazyAssociationsToLoad() {
        AssocHub reloaded = JavAIEnvironment.postgresAssocHubRepository().findById(savedHub.getId()).orElseThrow();
        JavAIEnvironment.postgresAssocHubRepository().save(reloaded);

        // Still saves cleanly, and the target's row is untouched and still singular -- no duplicate written
        // by a second, redundant pass over an association nobody modified.
        assertEquals(1, vectorRowCount(lazyManyToOneLeaf.getId(), AssocLeaf.class, "label"));
    }

    /**
     * {@code @Summary} through a <em>lazy</em> association: mutating the target must still move the owner's
     * summary vector, which means the summary walk resolves the proxy too.
     *
     * <p>⚠️ Asserted on the caller's own {@code hub}, not on what {@code save} returned, and the difference
     * is the point. Since OMI-275 {@code save} returns Hibernate's <b>managed</b> instance, as Spring Data
     * JPA's does -- so the returned graph is a different object graph from the one passed in, and mutating
     * the {@code target} the caller still holds does not reach it. That is ordinary {@code merge} semantics
     * rather than anything JavAI invented, and it is why the rule for callers is: after a save, keep using
     * what save returned, or keep using your own instance, but do not mix the two and expect them to be the
     * same objects.
     */
    @Test
    void summaryVectorStillPropagatesThroughALazyAssociation() {
        AssocLeaf target = JavAIEnvironment.postgresAssocLeafRepository().save(new AssocLeaf("summary before"));
        AssocHub hub = new AssocHub("summary owner", target);
        hub.setSummaryLazyManyToOne(target);
        AssocHub saved = JavAIEnvironment.postgresAssocHubRepository().save(hub);

        assertNotSame(hub, saved, "save returns the managed instance, not the one it was given");

        float[] before = ((JavAIVectorizable) hub).summaryVector().values();
        target.setLabel("summary after, wholly different subject matter");
        float[] after = ((JavAIVectorizable) hub).summaryVector().values();

        assertNotEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(after),
                "mutating a @Summary child reached through a lazy association must move the owner's summary");
    }

    /** Depth: three vectorizable levels, every hop lazy. Hop two is past the explicit one-hop walk. */
    @Test
    void multiHopLazyChainPersistsEveryLevel() {
        AssocLeaf leaf = new AssocLeaf("chain leaf");
        AssocChainMiddle middle = new AssocChainMiddle("chain middle", leaf);
        AssocChainTop top = JavAIEnvironment.postgresAssocChainTopRepository()
                .save(new AssocChainTop("chain top", middle));

        assertEquals(1, vectorRowCount(top.getId(), AssocChainTop.class, "label"));
        assertEquals(1, vectorRowCount(middle.getId(), AssocChainMiddle.class, "label"), "hop two must get a row");
        assertEquals(1, vectorRowCount(leaf.getId(), AssocLeaf.class, "label"), "hop three must get a row");
    }

    /** Self-reference: the degenerate vectorizable-to-vectorizable case, and where a proxy-resolving fix
     *  that chose to initialize rather than skip could recurse without end. */
    @Test
    void selfReferencingLazyAssociationPersists() {
        AssocSelfNode parent = JavAIEnvironment.postgresAssocSelfNodeRepository().save(new AssocSelfNode("self parent"));
        AssocSelfNode child = new AssocSelfNode("self child");
        child.setParent(parent);

        AssocSelfNode saved = JavAIEnvironment.postgresAssocSelfNodeRepository().save(child);
        assertEquals(1, vectorRowCount(saved.getId(), AssocSelfNode.class, "label"));
        assertEquals(1, vectorRowCount(parent.getId(), AssocSelfNode.class, "label"));
    }

    /** Bidirectional, cyclic: the child's lazy back-reference is the failing shape, reached backwards. */
    @Test
    void bidirectionalCyclicGraphPersistsBothSidesExactlyOnce() {
        AssocBiParent parent = new AssocBiParent("bi parent");
        AssocBiChild child = new AssocBiChild("bi child");
        parent.addChild(child);

        AssocBiParent saved = JavAIEnvironment.postgresAssocBiParentRepository().save(parent);

        assertEquals(1, vectorRowCount(saved.getId(), AssocBiParent.class, "label"),
                "a cycle must not write the parent's row twice");
        assertEquals(1, vectorRowCount(child.getId(), AssocBiChild.class, "label"));
    }

    /** The control axis: a lazy association to a non-vectorizable target never broke, and must never
     *  acquire a vector row either. */
    @Test
    void nonVectorizableTargetPersistsWithoutAnyVectorRow() {
        assertEquals(0, vectorRowCountForOwnerType(PlainLeaf.class),
                "a non-@JavAIVectorizable entity must never get vector rows");
        assertEquals("plain target",
                JavAIEnvironment.postgresPlainLeafRepository().findById(plainLeaf.getId()).orElseThrow().getLabel());
    }

    // ---- 2. database structure --------------------------------------------------------------------

    /** Each singular association becomes an FK column on the owner's own table, named per the annotation. */
    @Test
    void singularAssociationsGenerateForeignKeyColumns() throws Exception {
        Set<String> columns = columns("assoc_hub");
        assertTrue(columns.containsAll(Set.of(
                        "lazy_many_to_one_id", "eager_many_to_one_id",
                        "lazy_one_to_one_id", "eager_one_to_one_id",
                        "mandatory_lazy_id", "summary_lazy_many_to_one_id", "lazy_plain_target_id")),
                "every singular association must be an FK column on the owner; found: " + columns);

        Set<String> keys = foreignKeys("assoc_hub");
        assertTrue(keys.contains("lazy_many_to_one_id -> assoc_leaf"), "got: " + keys);
        assertTrue(keys.contains("lazy_plain_target_id -> plain_leaf"), "got: " + keys);
    }

    /** {@code optional = false} + {@code nullable = false} must reach the database as a real NOT NULL. */
    @Test
    void mandatoryAssociationIsNotNullInTheSchema() throws Exception {
        assertEquals("NO", nullability("assoc_hub", "mandatory_lazy_id"),
                "optional=false with a nullable=false join column must generate NOT NULL");
        assertEquals("YES", nullability("assoc_hub", "lazy_many_to_one_id"),
                "an ordinary optional association must stay nullable -- the contrast is the point");
    }

    /** Collection associations become join tables, one per cardinality; the JavAI collection keeps its own. */
    @Test
    void collectionAssociationsGenerateJoinTables() throws Exception {
        Set<String> tables = tables();
        assertTrue(tables.contains("assoc_hub_one_to_many"), "found: " + tables);
        assertTrue(tables.contains("assoc_hub_many_to_many"), "found: " + tables);
        assertTrue(tables.contains("assoc_hub_javai_collection"),
                "the interface-typed JavAI collection with @OneToMany is natively mapped too; found: " + tables);

        assertFalse(columns("assoc_hub").contains("lazy_one_to_many"), "a collection is never a column");
    }

    /**
     * The structural heart of the regression. Before the fix the id was {@code null}; had it been written
     * from the proxy's own class instead, it would have landed under {@code AssocLeaf$HibernateProxy$xyz}
     * -- present, non-null, and invisible to every subsequent lookup. Both failure modes are excluded here.
     */
    @Test
    void noVectorRowIsEverKeyedByAProxyClassOrANullOwner() throws Exception {
        for (String table : vectorTables()) {
            assertEquals(0, countWhere("SELECT COUNT(*) FROM " + table + " WHERE owner_type LIKE '%HibernateProxy%'"),
                    table + " must not key any row by a Hibernate proxy class");
            assertEquals(0, countWhere("SELECT COUNT(*) FROM " + table + " WHERE owner_id IS NULL"),
                    table + " must not contain a null owner_id");
        }
    }

    // ---- 3. persisted and retrieved values --------------------------------------------------------

    /** Every vectorizable reachable from the hub has exactly one row, under its own real entity class. */
    @Test
    void everyVectorizableInTheGraphHasExactlyOneVectorRowUnderItsOwnClass() {
        assertEquals(1, vectorRowCount(savedHub.getId(), AssocHub.class, "label"));
        for (AssocLeaf leaf : List.of(mandatoryLeaf, lazyManyToOneLeaf, summaryLeaf)) {
            assertEquals(1, vectorRowCount(leaf.getId(), AssocLeaf.class, "label"),
                    "expected exactly one row for AssocLeaf " + leaf.getId());
        }
        assertEquals(AssocLeaf.class.getName(), ownerTypeOf(lazyManyToOneLeaf.getId()));
    }

    /** The stored vector must be the entity's real, current vector -- not a stale or fabricated one. */
    @Test
    void thePersistedVectorMatchesTheRecomputedOne() {
        AssocHub reloaded = JavAIEnvironment.postgresAssocHubRepository().findById(savedHub.getId()).orElseThrow();
        EmbeddingVector recomputed = ((JavAIVectorizable) reloaded).fieldVector("label");

        float[] stored = storedVector(savedHub.getId(), "label");
        assertEquals(recomputed.dims(), stored.length, "stored dimensionality must match the model's");
        for (int i = 0; i < stored.length; i++) {
            assertEquals(recomputed.values()[i], stored[i], 1e-4f, "stored vector diverges at index " + i);
        }
    }

    /** The whole graph reloads with its scalar values and its association targets intact. */
    @Test
    void theWholeGraphRoundTripsWithEveryAssociationTargetIntact() {
        AssocHub reloaded = JavAIEnvironment.postgresAssocHubRepository().findById(savedHub.getId()).orElseThrow();

        assertEquals("association matrix hub", reloaded.getLabel());
        assertEquals(mandatoryLeaf.getId(), reloaded.getMandatoryLazyManyToOne().getId());
        assertEquals(lazyManyToOneLeaf.getId(), reloaded.getLazyManyToOne().getId());
        assertEquals(summaryLeaf.getId(), reloaded.getSummaryLazyManyToOne().getId());
        assertEquals(plainLeaf.getId(), reloaded.getLazyPlainTarget().getId());
        assertNotNull(reloaded.getLazyOneToOne());
        assertNotNull(reloaded.getEagerOneToOne());

        // Eager associations are fully materialized, so their values are readable straight off the
        // detached instance -- the lazy ones' values are checked in
        // associationTargetValuesRoundTripWhenLoadedThroughTheirOwnRepository instead.
        assertEquals("eager many-to-one target", reloaded.getEagerManyToOne().getLabel());
        assertEquals("eager one-to-one target", reloaded.getEagerOneToOne().getLabel());

        // Collection membership is verified against the join tables, which is what actually persisted.
        assertEquals(1, joinTableRowCount("assoc_hub_one_to_many", savedHub.getId()));
        assertEquals(1, joinTableRowCount("assoc_hub_many_to_many", savedHub.getId()));
        assertEquals(1, joinTableRowCount("assoc_hub_javai_collection", savedHub.getId()));
    }

    /** Collection members are vectorizables too, and must each get their own row. */
    @Test
    void everyCollectionMemberGetsItsOwnVectorRow() {
        // savedHub is the caller's own instance (save() returns it, not the managed copy), so its
        // collections hold the real element objects -- no session needed, and no proxies involved.
        for (AssocLeaf member : savedHub.getLazyOneToMany()) {
            assertEquals(1, vectorRowCount(member.getId(), AssocLeaf.class, "label"));
        }
        for (AssocLeaf member : savedHub.getLazyManyToMany()) {
            assertEquals(1, vectorRowCount(member.getId(), AssocLeaf.class, "label"));
        }
        for (AssocLeaf member : savedHub.getSummaryJavAICollection()) {
            assertEquals(1, vectorRowCount(member.getId(), AssocLeaf.class, "label"));
        }
    }

    /** Membership rows for one owner in a Hibernate-generated join table. */
    private static int joinTableRowCount(String joinTable, UUID ownerId) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + joinTable + " WHERE assoc_hub_id = ?")) {
            statement.setObject(1, ownerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MonolithicContainer.postgresUrl(),
                MonolithicContainer.POSTGRES_USERNAME, MonolithicContainer.POSTGRES_PASSWORD);
    }

    /** Every per-model field-vector table -- the model id is part of the name, so it isn't known up front. */
    private static Set<String> vectorTables() throws Exception {
        Set<String> found = new LinkedHashSet<>();
        for (String table : tables()) {
            if (table.startsWith("javai_vectors__")) {
                found.add(table);
            }
        }
        assertFalse(found.isEmpty(), "expected at least one per-model vector table");
        return found;
    }

    private static int vectorRowCount(UUID ownerId, Class<?> ownerType, String fieldName) {
        try {
            int total = 0;
            for (String table : vectorTables()) {
                try (Connection connection = connection();
                     PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table
                             + " WHERE owner_id = ? AND owner_type = ? AND field_name = ?")) {
                    statement.setObject(1, ownerId);
                    statement.setString(2, ownerType.getName());
                    statement.setString(3, fieldName);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        total += resultSet.getInt(1);
                    }
                }
            }
            return total;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int vectorRowCountForOwnerType(Class<?> ownerType) {
        try {
            int total = 0;
            for (String table : vectorTables()) {
                try (Connection connection = connection();
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT COUNT(*) FROM " + table + " WHERE owner_type = ?")) {
                    statement.setString(1, ownerType.getName());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        total += resultSet.getInt(1);
                    }
                }
            }
            return total;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ownerTypeOf(UUID ownerId) {
        try {
            for (String table : vectorTables()) {
                try (Connection connection = connection();
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT DISTINCT owner_type FROM " + table + " WHERE owner_id = ?")) {
                    statement.setObject(1, ownerId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            return resultSet.getString(1);
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static float[] storedVector(UUID ownerId, String fieldName) {
        try {
            for (String table : vectorTables()) {
                try (Connection connection = connection();
                     PreparedStatement statement = connection.prepareStatement("SELECT vector::text FROM " + table
                             + " WHERE owner_id = ? AND field_name = ?")) {
                    statement.setObject(1, ownerId);
                    statement.setString(2, fieldName);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            String raw = resultSet.getString(1);
                            String[] parts = raw.substring(1, raw.length() - 1).split(",");
                            float[] values = new float[parts.length];
                            for (int i = 0; i < parts.length; i++) {
                                values[i] = Float.parseFloat(parts[i].trim());
                            }
                            return values;
                        }
                    }
                }
            }
            throw new IllegalStateException("no stored vector for " + ownerId + "." + fieldName);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int countWhere(String sql) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static Set<String> tables() throws Exception {
        Set<String> found = new LinkedHashSet<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                found.add(resultSet.getString(1));
            }
        }
        return found;
    }

    private static Set<String> columns(String table) throws Exception {
        Set<String> found = new LinkedHashSet<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_name FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    found.add(resultSet.getString(1));
                }
            }
        }
        return found;
    }

    private static String nullability(String table, String column) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT is_nullable FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "no such column: " + table + "." + column);
                return resultSet.getString(1);
            }
        }
    }

    private static Set<String> foreignKeys(String table) throws Exception {
        Set<String> found = new LinkedHashSet<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT kcu.column_name, ccu.table_name FROM information_schema.table_constraints tc "
                             + "JOIN information_schema.key_column_usage kcu "
                             + "  ON tc.constraint_name = kcu.constraint_name "
                             + "JOIN information_schema.constraint_column_usage ccu "
                             + "  ON tc.constraint_name = ccu.constraint_name "
                             + "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    found.add(resultSet.getString(1) + " -> " + resultSet.getString(2));
                }
            }
        }
        return found;
    }

    // ---- 4. @Any: polymorphic associations (OMI-212) ----------------------------------------------

    /**
     * The headline OMI-212 failure. Every {@code @Any} target above is named <em>only</em> by a
     * discriminator, and no repository is ever requested for one -- so before the fix this whole class
     * failed at {@code SessionFactory} boot with {@code UnknownEntityTypeException}, taking every unrelated
     * test in it down as well. That the fixture saved at all is the assertion.
     */
    @Test
    void theMatrixBootsAndSavesWithTargetsReachableOnlyByDiscriminator() {
        assertNotNull(savedHub.getId());
        assertNotNull(anyVectorizableTarget.getId(), "a cascaded @Any target must have been persisted");
        assertNotNull(anyPlainTarget.getId());
    }

    /** Eager polymorphic to-one, resolving to a <em>vectorizable</em> concrete type. */
    @Test
    void eagerAnyResolvesToItsConcreteVectorizableType() {
        AssocHub reloaded = JavAIEnvironment.postgresAssocHubRepository().findById(savedHub.getId()).orElseThrow();

        AssocAnyTarget target = reloaded.getEagerAny();
        assertInstanceOf(AssocAnyVectorizable.class, target,
                "the discriminator must resolve to the concrete type that was stored");
        assertEquals("eager any vectorizable target", target.label());
    }

    /**
     * The other axis: an eager polymorphic to-one resolving to a <em>non</em>-vectorizable concrete type.
     * Paired with {@link #eagerAnyResolvesToItsConcreteVectorizableType} so neither "eager" nor "target
     * happens to be vectorizable" can be the reason resolution works.
     */
    @Test
    void eagerAnyResolvesToItsConcreteNonVectorizableType() {
        AssocHub reloaded = JavAIEnvironment.postgresAssocHubRepository().findById(savedHub.getId()).orElseThrow();

        AssocAnyTarget target = reloaded.getEagerAnyPlain();
        assertInstanceOf(AssocAnyPlain.class, target);
        assertEquals("lazy any plain target", target.label());
    }

    /**
     * A <em>lazy</em> {@code @Any} behaves like any other lazy association on a detached entity: touching it
     * outside a session throws. Asserted rather than worked around, for the same reason
     * {@link #touchingAnUninitializedLazyAssociationOutsideASessionThrows} asserts it for the fixed-type
     * case -- being polymorphic changes what the proxy resolves to, not when it may be resolved.
     *
     * <p>The registration this ticket is about happens at boot and is already proven by every other test
     * here; that a lazy target cannot be dereferenced off a detached instance is orthogonal, and quietly
     * making these fields eager to dodge it would have removed real coverage of the lazy-@Any save path.
     */
    @Test
    void aLazyAnyOnADetachedEntityThrowsLikeAnyOtherLazyAssociation() {
        AssocHub detached = JavAIEnvironment.postgresAssocHubRepository().findById(savedHub.getId()).orElseThrow();

        assertThrows(LazyInitializationException.class, () -> detached.getLazyAny().label(),
                "a lazy polymorphic target is still a lazy target");
    }

    /**
     * A vectorizable {@code @Any} target gets its vectors written, exactly like a fixed-type association --
     * and a non-vectorizable one gets none. The vector-write walk cannot know which it is holding until it
     * has resolved the discriminator, which is what makes this worth asserting rather than assuming.
     */
    @Test
    void vectorsAreWrittenForVectorizableAnyTargetsAndOnlyThose() throws Exception {
        assertEquals(1, vectorRowCount(anyVectorizableTarget.getId(), AssocAnyVectorizable.class, "label"),
                "a polymorphic reference to a vectorizable target must still persist its vector");
        assertEquals(0, vectorRowCountForOwnerType(AssocAnyPlain.class),
                "and a non-vectorizable target must never acquire one, however it was reached");
    }

    /** {@code @Summary} through a polymorphic reference: the summary walk resolves the discriminator too. */
    @Test
    void summaryVectorIncludesAPolymorphicSummaryChild() {
        EmbeddingVector summary = ((JavAIVectorizable) savedHub).summaryVector();

        assertNotNull(summary);
        assertTrue(summary.dims() > 0,
                "a @Summary @Any child must contribute to its owner's summary, not break it");
    }

    /**
     * The to-many polymorphic form, holding both concrete kinds at once. Its declared type is a
     * {@code Collection} whose element type is the same non-entity interface, so neither the field type nor
     * the generic argument names anything registrable -- only the discriminator does.
     */
    @Test
    void manyToAnyRoundTripsAMixOfConcreteTypes() {
        AssocHub reloaded = JavAIEnvironment.postgresAssocHubRepository().findById(savedHub.getId()).orElseThrow();

        List<AssocAnyTarget> members = reloaded.getManyToAny();
        assertEquals(2, members.size(), "both members must come back: " + members);
        assertTrue(members.stream().anyMatch(AssocAnyVectorizable.class::isInstance),
                "one vectorizable member expected in: " + members);
        assertTrue(members.stream().anyMatch(AssocAnyPlain.class::isInstance),
                "one plain member expected in: " + members);
    }

    /**
     * Position, not configuration: this target is reachable only as {@code hub -> summaryLeaf ->
     * discriminator}. A fix that registered discriminator targets on the root entity without recursing
     * would pass every other test here and fail this one.
     */
    @Test
    void anAnyTargetTwoHopsFromTheRepositoryIsRegisteredAndRoundTrips() {
        AssocLeaf reloaded = JavAIEnvironment.postgresAssocLeafRepository()
                .findById(summaryLeaf.getId()).orElseThrow();

        AssocAnyTarget nested = reloaded.getNestedAny();
        assertInstanceOf(AssocNestedAny.class, nested,
                "a discriminator target on a nested entity must be registered by the recursive walk");
        assertEquals("nested any target", nested.label());
    }

    /** An unset polymorphic reference is ordinary, not an error -- and must not fabricate a target. */
    @Test
    void anUnsetAnyReferenceRoundTripsAsNull() {
        AssocHub bare = JavAIEnvironment.postgresAssocHubRepository()
                .save(new AssocHub("hub with no polymorphic references", mandatoryLeaf));

        AssocHub reloaded = JavAIEnvironment.postgresAssocHubRepository().findById(bare.getId()).orElseThrow();
        assertNull(reloaded.getEagerAny());
        assertNull(reloaded.getLazyAny());
        assertTrue(reloaded.getManyToAny().isEmpty());
    }

    /** The discriminator and FK columns each polymorphic field is supposed to generate actually exist. */
    @Test
    void anyAssociationsGenerateDiscriminatorAndKeyColumns() throws Exception {
        Set<String> columns = columns("assoc_hub");
        assertTrue(columns.containsAll(Set.of(
                        "eager_any_type", "eager_any_id",
                        "lazy_any_type", "lazy_any_id",
                        "summary_any_type", "summary_any_id")),
                "each @Any needs a discriminator column and a key column; found: " + columns);
        assertTrue(columns.containsAll(Set.of("eager_any_plain_type", "eager_any_plain_id")),
                "including the non-vectorizable-target pair; found: " + columns);

        Set<String> joinTable = columns("assoc_hub_many_to_any");
        assertTrue(joinTable.containsAll(Set.of("hub_id", "member_id", "member_type")),
                "@ManyToAny needs its own join table carrying the discriminator; found: " + joinTable);
    }

    /**
     * The trade {@code @Any} makes, asserted so nobody discovers it by surprise: the key column points into
     * several tables, so it <b>cannot</b> carry a foreign key. Referential integrity is given up in exchange
     * for the polymorphism -- documented in persistence-support-matrix.md, and pinned here because it is a
     * property of the schema JavAI produces, not merely of the annotation.
     */
    @Test
    void anAnyKeyColumnCarriesNoForeignKey() throws Exception {
        Set<String> keys = foreignKeys("assoc_hub");

        assertFalse(keys.contains("eager_any_id"),
                "@Any cannot be FK-constrained -- its key may point into any target table: " + keys);
        assertFalse(keys.contains("lazy_any_id"), "same for the lazy form: " + keys);
    }

}
