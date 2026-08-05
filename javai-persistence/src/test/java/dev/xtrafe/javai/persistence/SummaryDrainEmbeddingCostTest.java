package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.AfterEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * What a save actually costs in embedding calls, split by phase (OMI-255).
 *
 * <h2>Why this exists as its own test</h2>
 *
 * OMI-187 established the invariant that persisting an object graph embeds each distinct
 * {@code @Vectorize} field value exactly once, and {@code AssociationGraphEmbeddingCostE2ETest} guards it
 * end to end against a real model. That test began failing when OMI-255 moved summary recomputation out of
 * the caller's transaction: the recomputation reloads the container in a fresh session, and a reloaded
 * entity that fails to serve its <em>stored</em> vectors re-embeds them for real.
 *
 * <p>That e2e test takes ~85 seconds and needs a live model container, which is far too slow to diagnose
 * against. This reproduces the same accounting in seconds, with a fake provider, so the question "which
 * object re-embeds, and why" can actually be iterated on.
 *
 * <p>It also splits the measurement the way the architecture now splits the work, which the e2e test cannot:
 * the <b>write</b> phase and the <b>recomputation</b> phase have different costs and different invariants,
 * and lumping them together only says that the total moved.
 */
@Testcontainers
class SummaryDrainEmbeddingCostTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestShelfRepository shelves;

    private RecordingEmbeddingProvider provider;

    @BeforeAll
    static void configurePersistence() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        shelves = JavAIPI.repository(TestShelfRepository.class, config);
        JavAIPI.repository(TestBookRepository.class, config);
        JavAIPI.repository(TestLibraryRepository.class, config);
        JavAIPI.repository(TestCabinetRepository.class, config);
        JavAIPI.repository(TestDossierRepository.class, config);
    }

    @BeforeEach
    void recordEveryEmbedding() {
        provider = new RecordingEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    /**
     * The write phase alone, with the recomputation deliberately deferred. This is OMI-187's original
     * invariant on the path OMI-187 actually measured, and it must hold exactly -- no budget, no tolerance.
     */
    @Test
    @DisplayName("QUEUE_ONLY: each distinct @Vectorize value is embedded exactly once")
    void writePhaseEmbedsEachValueExactlyOnce() {
        TestShelf shelf = new TestShelf("cost-shelf");
        shelf.getBooks().add(new TestBook("cost-book-one"));
        shelf.getBooks().add(new TestBook("cost-book-two"));

        provider.ledger().reset();
        shelves.save(shelf, SummaryPolicy.QUEUE_ONLY);

        provider.ledger().assertEmbeddedExactlyOnce("cost-shelf", "cost-book-one", "cost-book-two");
    }

    /**
     * The same save with the recomputation included -- the default every existing caller gets.
     *
     * <p>⚠️ The recomputation reads only <em>stored</em> vectors, so it must cost <b>nothing</b>. Any
     * embedding call here is a reloaded entity failing to serve a vector the database already holds, which is
     * pure waste and scales with how much the container holds: on an album of fifty assets it is fifty model
     * calls per upload, not a rounding error.
     */
    @Test
    @DisplayName("RECOMPUTE_AFTER_COMMIT: the recomputation adds no embedding calls at all")
    void recomputationAddsNoEmbeddingCalls() {
        TestShelf shelf = new TestShelf("drain-shelf");
        shelf.getBooks().add(new TestBook("drain-book-one"));
        shelf.getBooks().add(new TestBook("drain-book-two"));

        provider.ledger().reset();
        shelves.save(shelf);

        provider.ledger().assertEmbeddedExactlyOnce("drain-shelf", "drain-book-one", "drain-book-two");
    }

    /** The same, one level deeper -- a container inside a container, which is where the recomputation walks
     *  furthest and so has the most opportunity to re-embed. */
    @Test
    @DisplayName("RECOMPUTE_AFTER_COMMIT: a nested container costs nothing extra either")
    void nestedRecomputationAddsNoEmbeddingCalls() {
        TestShelf shelf = new TestShelf("nested-shelf");
        shelf.getBooks().add(new TestBook("nested-book"));
        TestLibrary library = new TestLibrary("nested-library");
        library.getShelves().add(shelf);

        provider.ledger().reset();
        JavAIPI.repository(TestLibraryRepository.class, config).save(library);

        provider.ledger().assertEmbeddedExactlyOnce("nested-library", "nested-shelf", "nested-book");
    }

    /**
     * The shape the e2e failure pointed at: a {@code @Summary} child behind a <b>lazy singular</b>
     * association. Distinct from the collection cases above because it reads back as an uninitialized proxy.
     */
    @Test
    @DisplayName("RECOMPUTE_AFTER_COMMIT: a lazy singular @Summary child is not re-embedded")
    void lazySingularSummaryChildIsNotReEmbedded() {
        TestCabinet cabinet = new TestCabinet("cabinet-label", new TestDossier("dossier-title"));

        provider.ledger().reset();
        JavAIPI.repository(TestCabinetRepository.class, config).save(cabinet);

        provider.ledger().assertEmbeddedExactlyOnce("cabinet-label", "dossier-title");
    }

    /**
     * The dimension the e2e test parameterises over and the cases above do not: the ambient
     * {@link EmbeddingConsistencyMode}. Under the two non-blocking modes a read of a stale slot <em>dispatches
     * a background recomputation</em> rather than computing inline, so the recomputation's reads can generate
     * embedding calls that arrive after the save has returned -- which is also why the e2e failure count moved
     * between runs of identical code.
     */
    @ParameterizedTest
    @EnumSource(EmbeddingConsistencyMode.class)
    @DisplayName("RECOMPUTE_AFTER_COMMIT costs nothing extra under every consistency mode")
    void recomputationAddsNoEmbeddingCallsUnderEveryMode(EmbeddingConsistencyMode mode) {
        JavAIRuntime.configureConsistencyMode(mode);
        TestShelf shelf = new TestShelf("mode-shelf-" + mode);
        shelf.getBooks().add(new TestBook("mode-book-" + mode));

        provider.ledger().reset();
        shelves.save(shelf);
        provider.ledger().awaitQuiescence();

        provider.ledger().assertEmbeddedExactlyOnce("mode-shelf-" + mode, "mode-book-" + mode);
    }

    @AfterEach
    void restoreImmediateConsistency() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
    }

    /**
     * The same lazy singular {@code @Summary} shape, but with the child persisted <b>independently</b> rather
     * than cascaded through its container -- which is how {@code AssocHub} declares it, and the one case that
     * survived removing the redundant leaf recomputation.
     */
    @Test
    @DisplayName("RECOMPUTE_AFTER_COMMIT: an independently-saved lazy @Summary child is not re-embedded")
    void independentlySavedLazySummaryChildIsNotReEmbedded() {
        TestDossier dossier = JavAIPI.repository(TestDossierRepository.class, config)
                .save(new TestDossier("independent-dossier"));
        TestCabinet cabinet = new TestCabinet("independent-cabinet", dossier);

        provider.ledger().reset();
        JavAIPI.repository(TestCabinetRepository.class, config).save(cabinet);

        provider.ledger().assertEmbeddedExactlyOnce("independent-cabinet");
    }

    /**
     * Re-saving an unchanged container embeds <b>nothing</b>: every value involved is already stored (OMI-256).
     *
     * <p>This test used to pin the opposite, asserting exactly one wasted call. {@code findById} hydrated the
     * stored vectors of the <em>root</em> only, so the members Hibernate loads behind a natively-mapped
     * association arrived with empty cache slots and the ensuing save recomputed each one for real -- one
     * model call per member, scaling with how much the container holds. {@code hydrateAssociatedVectors} now
     * serves those members their stored vectors on the load path, and the count it pinned is zero.
     *
     * <p>It was never an OMI-255 regression, which was verified rather than assumed at the time: the identical
     * assertion failed the same way on a worktree at the pre-OMI-255 commit ({@code 032dadf}).
     */
    @Test
    @DisplayName("re-saving an unchanged container embeds nothing")
    void resavingUnchangedEmbedsNothing() {
        TestShelf shelf = new TestShelf("stable-shelf");
        shelf.getBooks().add(new TestBook("stable-book"));
        shelves.save(shelf);

        provider.ledger().reset();
        shelves.save(shelves.findById(shelf.getId()).orElseThrow());

        provider.ledger().assertEmbeddedExactlyOnce();
    }

    /**
     * The same invariant where it actually costs something: a container holding several members, all of them
     * unchanged. The single-member case above cannot tell "hydrates the member" apart from "happens not to
     * walk that far," and it is the per-member scaling that made this worth fixing -- an album of fifty assets
     * re-saved to change its title paid fifty embeddings.
     */
    @Test
    @DisplayName("re-saving a multi-member container embeds nothing, per member")
    void resavingUnchangedMultiMemberContainerEmbedsNothing() {
        TestShelf shelf = new TestShelf("bulk-shelf");
        for (int i = 0; i < 5; i++) {
            shelf.getBooks().add(new TestBook("bulk-book-" + i));
        }
        shelves.save(shelf);

        provider.ledger().reset();
        shelves.save(shelves.findById(shelf.getId()).orElseThrow());

        provider.ledger().assertEmbeddedExactlyOnce();
    }

    /**
     * The other half of the invariant, and the one that would catch hydration going too far: what genuinely
     * is new must still be embedded. Hydration only fills slots that are empty -- it never overwrites one a
     * setter has marked dirty (see {@code JavAIRuntime.hydrateFieldVector}), and a member that was never
     * stored has nothing to serve it in the first place.
     *
     * <p>Asserted together in one save so the two cannot pass for each other: the changed root and the added
     * member are embedded exactly once each, and the untouched member -- the one hydration is responsible
     * for -- is not embedded at all.
     */
    @Test
    @DisplayName("a changed root and an added member are still embedded; the untouched member is not")
    void genuinelyNewValuesAreStillEmbedded() {
        TestShelf shelf = new TestShelf("changing-shelf");
        shelf.getBooks().add(new TestBook("untouched-book"));
        shelves.save(shelf);

        TestShelf reloaded = shelves.findById(shelf.getId()).orElseThrow();
        provider.ledger().reset();
        reloaded.setLabel("changing-shelf, wholly different subject matter");
        reloaded.getBooks().add(new TestBook("added-book"));
        shelves.save(reloaded);

        provider.ledger().assertEmbeddedExactlyOnce(
                "changing-shelf, wholly different subject matter", "added-book");
    }
}
