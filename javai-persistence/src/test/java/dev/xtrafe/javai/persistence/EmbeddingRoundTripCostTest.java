package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.EmbeddingLedger;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How many <b>provider round trips</b> each persistence flow costs, as against how many <b>texts</b> it
 * embeds -- OMI-266's measurement, and the characterization of the behaviour that ticket exists to change.
 *
 * <h2>Why this is a different measurement from the ones already here</h2>
 *
 * {@link SummaryDrainEmbeddingCostTest} and {@code AssociationGraphEmbeddingCostE2ETest} both count
 * <em>texts</em>, which is OMI-187's question: is JavAI embedding anything it needn't? That question is
 * settled and those tests keep it settled. It is also completely blind to the question here. Ten texts that
 * all genuinely need embedding cost ten sequential HTTP round trips or one batched one, and every existing
 * instrument in this repository reports "ten" for both.
 *
 * <p>So the assertions below deliberately do <b>not</b> pin absolute counts -- the texts are already
 * guarded elsewhere, and duplicating that here would only produce a second thing to update. Each test pins
 * the <em>ratio</em>: today every persistence flow issues exactly one round trip per text, which is the
 * finding. {@link #precomputeVectorsAlreadyBatches()} is the control, and proves the instrument can see
 * batching when batching is genuinely happening -- without it, "one trip per text" everywhere would be
 * indistinguishable from a broken measurement.
 */
@Testcontainers
class EmbeddingRoundTripCostTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** How many entities the multi-entity flows use. Large enough that a batched implementation would be
     *  visibly different, small enough to stay fast. */
    private static final int FLEET = 12;

    private static JavAIPersistenceConfig config;
    private static TestArticleRepository articles;
    private static TestShelfRepository shelves;

    /** Every measurement taken, printed once at the end -- this class's real output is the table, the
     *  assertions merely stop it going quietly stale. */
    private static final Map<String, String> MEASUREMENTS = new LinkedHashMap<>();

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
        articles = JavAIPI.repository(TestArticleRepository.class, config);
        shelves = JavAIPI.repository(TestShelfRepository.class, config);
        JavAIPI.repository(TestBookRepository.class, config);
    }

    @BeforeEach
    void recordEveryEmbedding() {
        provider = new RecordingEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    @AfterAll
    static void printMeasurements() {
        StringBuilder table = new StringBuilder("\n=== OMI-266: embedding round trips per flow ===\n");
        MEASUREMENTS.forEach((flow, result) -> table.append("  ").append(flow).append(": ").append(result).append('\n'));
        System.out.println(table);
    }

    @Test
    @DisplayName("one save of one entity: every @Vectorize field in one round trip")
    void singleEntitySave() {
        TestArticle article = new TestArticle(unique("title"), unique("body"));

        provider.ledger().reset();
        articles.save(article);

        assertRoundTrips("save(1 entity, 2 @Vectorize fields)", 1);
    }

    /**
     * The whole reachable subgraph in one round trip -- the case the per-save pre-pass exists for, since a
     * container's members are discovered by the flush one at a time and cannot be batched by any caller who
     * only has the root in hand.
     */
    @Test
    @DisplayName("one save of a subgraph: container and every member in one round trip")
    void subgraphSave() {
        TestShelf shelf = new TestShelf(unique("shelf"));
        for (int i = 0; i < FLEET; i++) {
            shelf.getBooks().add(new TestBook(unique("book-" + i)));
        }

        provider.ledger().reset();
        shelves.save(shelf);
        provider.ledger().awaitQuiescence();

        assertRoundTrips("save(1 container + " + FLEET + " members)", 1);
    }

    /**
     * {@code saveAll} is the only thing that can batch <em>across</em> entities: each entity's own save sees
     * only its own subgraph, so a loop of saves stays one round trip per entity however well each batches
     * internally.
     */
    @Test
    @DisplayName("saveAll: every entity in the batch shares one round trip")
    void saveAllOfManyEntities() {
        List<TestArticle> fleet = new ArrayList<>();
        for (int i = 0; i < FLEET; i++) {
            fleet.add(new TestArticle(unique("bulk-title-" + i), unique("bulk-body-" + i)));
        }

        provider.ledger().reset();
        List<TestArticle> saved = articles.saveAll(fleet);
        provider.ledger().awaitQuiescence();

        assertEquals(FLEET, saved.size(), "saveAll returns every entity it saved");
        assertRoundTrips("saveAll(" + FLEET + " entities)", 1);
    }

    /**
     * And the flow that is deliberately <em>not</em> addressed: a caller looping {@code save} inside a
     * transaction still pays one round trip per entity, because nothing can see the batch until the body has
     * already run. Pinned rather than left unstated -- transaction-scoped batching was considered and
     * deferred, and this is the number that says what deferring it costs. {@link #saveAllOfManyEntities()} is
     * the answer for this shape today.
     */
    @Test
    @DisplayName("save-in-a-loop inside a transaction: still one round trip per entity (deferred)")
    void transactionOfManySaves() {
        List<TestArticle> fleet = new ArrayList<>();
        for (int i = 0; i < FLEET; i++) {
            fleet.add(new TestArticle(unique("txn-title-" + i), unique("txn-body-" + i)));
        }

        provider.ledger().reset();
        JavAIPI.inTransaction(config, () -> fleet.forEach(articles::save));

        assertRoundTrips("inTransaction(" + FLEET + " saves in a loop)", FLEET);
    }

    /**
     * A re-index under a newly-configured model, which is the largest bulk embedding operation this library
     * performs: by construction every entity in the store must be re-embedded, and by construction none of
     * their stored vectors can be reused, so there is no waste to remove and nothing but round trips left to
     * pay for.
     */
    @Test
    @DisplayName("reindex under a new model: one round trip per text, store-wide")
    void reindexUnderNewModel() {
        for (int i = 0; i < FLEET; i++) {
            articles.save(new TestArticle(unique("reindex-title-" + i), unique("reindex-body-" + i)));
        }

        // A different model id is what makes the stored vectors non-reusable, so the reindex genuinely
        // re-embeds rather than finding everything already on file.
        provider = new RecordingEmbeddingProvider(new RenamedModelProvider());
        JavAIRuntime.configureEmbeddingProvider(provider);

        provider.ledger().reset();
        articles.reindex();
        provider.ledger().awaitQuiescence();

        // One per chunk. Every article this class has saved lives in the same database, and the count is
        // therefore not fixed -- but it is far below the chunk size, so the whole table is one warm.
        assertRoundTrips("reindex(whole table, new model)", 1);
    }

    /**
     * The control. {@code precomputeVectors} is the one path in this codebase that already batches, so it is
     * what distinguishes "nothing batches" from "the instrument cannot see batching" -- and, since nothing in
     * production calls it (OMI-266's finding), it is also exactly the capability the flows above are missing.
     */
    @Test
    @DisplayName("control: precomputeVectors already collapses the same texts into one round trip")
    void precomputeVectorsAlreadyBatches() {
        List<TestArticle> fleet = new ArrayList<>();
        for (int i = 0; i < FLEET; i++) {
            fleet.add(new TestArticle(unique("warm-title-" + i), unique("warm-body-" + i)));
        }

        provider.ledger().reset();
        JavAIRuntime.precomputeVectors(fleet);

        EmbeddingLedger ledger = provider.ledger();
        assertEquals(FLEET * 2, ledger.totalCalls(), "every field still embedded exactly once");
        assertEquals(1, ledger.roundTrips(),
                "all " + FLEET * 2 + " texts fit one chunk, so they should cost one round trip:\n" + ledger.report());
        MEASUREMENTS.put("precomputeVectors(" + FLEET + " entities) [control]",
                ledger.totalCalls() + " texts / " + ledger.roundTrips() + " round trips");
    }

    /**
     * And the same batched path immediately followed by the saves it was meant to warm -- the shape
     * {@code JavAIRuntime.precomputeVectors}' own javadoc recommends. Recorded because it is the upper bound
     * on what OMI-266 can achieve for this flow: it is what the library can already do today, if the caller
     * knows to ask.
     */
    @Test
    @DisplayName("control: precompute-then-save costs the batched trips and no more")
    void precomputeThenSaveIsTheUpperBound() {
        List<TestArticle> fleet = new ArrayList<>();
        for (int i = 0; i < FLEET; i++) {
            fleet.add(new TestArticle(unique("bound-title-" + i), unique("bound-body-" + i)));
        }

        provider.ledger().reset();
        JavAIRuntime.precomputeVectors(fleet);
        JavAIPI.inTransaction(config, () -> fleet.forEach(articles::save));
        provider.ledger().awaitQuiescence();

        EmbeddingLedger ledger = provider.ledger();
        assertTrue(ledger.roundTrips() < ledger.totalCalls(),
                "the warmed saves should add no round trips of their own:\n" + ledger.report());
        MEASUREMENTS.put("precomputeVectors + inTransaction(" + FLEET + " saves) [control]",
                ledger.totalCalls() + " texts / " + ledger.roundTrips() + " round trips");
    }

    /**
     * Pins how many provider round trips a flow costs -- OMI-266's deliverable, stated as the number it is
     * rather than as an inequality, so a regression to one-call-per-text fails here loudly.
     *
     * <p>Deliberately says nothing about the <em>text</em> count. That is OMI-187's question and is already
     * pinned by {@link SummaryDrainEmbeddingCostTest}; restating it would add a second place to update and
     * would fail for reasons that have nothing to do with batching. The one thing asserted about texts is
     * that there were more of them than round trips, which is what makes the round-trip number meaningful.
     */
    private void assertRoundTrips(String flow, int expected) {
        EmbeddingLedger ledger = provider.ledger();
        assertTrue(ledger.totalCalls() > expected,
                flow + " must embed more texts than it makes round trips, or there is nothing being batched "
                        + "and this measurement proves nothing\n" + ledger.report());
        assertEquals(expected, ledger.roundTrips(), flow + "\n" + ledger.report());
        MEASUREMENTS.put(flow, ledger.totalCalls() + " texts / " + ledger.roundTrips() + " round trips");
    }

    /** Globally unique, so a text identifies (object, field) unambiguously -- see {@link EmbeddingLedger}. */
    private static String unique(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID();
    }

    /** A provider identical to the fake except for its model id, so previously-stored vectors are not
     *  reusable and a reindex has to embed for real. */
    private static final class RenamedModelProvider extends FakeEmbeddingProvider {

        private static final String MODEL_ID = "fake-test-model-v2";

        @Override
        public EmbeddingVector embed(String text) {
            EmbeddingVector base = super.embed(text);
            return new EmbeddingVector(base.values(), MODEL_ID, base.dims(), Instant.now());
        }

        @Override
        public String modelId() {
            return MODEL_ID;
        }
    }
}
