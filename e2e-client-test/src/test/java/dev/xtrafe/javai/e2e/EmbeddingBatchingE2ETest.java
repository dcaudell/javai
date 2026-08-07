package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.vector.EmbeddingBatchLimits;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-266's batching against a <b>real embedding model</b>: the texts a write needs arrive at the provider in
 * one HTTP request rather than one each, on all three backends, and the request that carries them is one a
 * real server actually accepts.
 *
 * <h2>Why this has to exist here and not only in the unit tests</h2>
 *
 * The unit tests measure batching through {@link RecordingEmbeddingProvider} wrapping a <em>fake</em>
 * provider, where "one request" is a method call that never leaves the JVM. That proves JavAI groups the
 * texts. It cannot prove the grouped request is one a real embedding server will take — which is a separate
 * claim, and the one with a real failure mode behind it: a batch is bounded per input but was, until this
 * ticket, unbounded in aggregate, so a hundred individually-legal texts could add up to roughly 9.4 MiB in a
 * single body. No amount of fake-provider testing can tell you whether that gets a 200.
 *
 * <p>There is a second reason, discovered rather than anticipated. Until OMI-266,
 * {@code RecordingEmbeddingProvider} did not override {@code embedAll}, so it inherited the looping default
 * and silently un-batched every provider it wrapped. Since {@link AssociationGraphEmbeddingCostE2ETest} is
 * the only e2e test that wraps the real provider, <b>no test in this repository had ever sent a genuinely
 * batched embeddings request to a live model.</b> The wire shape was covered against a stub HTTP server, and
 * the live path was not covered at all. This class is that coverage.
 *
 * <p>Cost in <em>texts</em> is {@link AssociationGraphEmbeddingCostE2ETest}'s subject (OMI-187) and is not
 * restated here beyond the exactly-once check each test needs to make its round-trip number meaningful.
 */
class EmbeddingBatchingE2ETest {

    private static final AtomicLong UNIQUE = new AtomicLong();

    /** Enough entities that one-request-per-entity and one-request-for-all are unmistakably different, while
     *  staying quick against a real model. */
    private static final int FLEET = 12;

    /** More articles than one default batch can carry, at two {@code @Vectorize} fields each -- so a split is
     *  forced rather than merely allowed. */
    private static final int ARTICLES_EXCEEDING_A_BATCH = EmbeddingBatchLimits.DEFAULT_MAX_BATCH_SIZE / 2 + 6;

    private static RecordingEmbeddingProvider provider;
    private static EmbeddingConsistencyMode originalMode;

    @BeforeAll
    static void wrapTheRealProviderInARecorder() {
        JavAIEnvironment.ensureRunning();
        originalMode = JavAIRuntime.consistencyMode();
        provider = new RecordingEmbeddingProvider(existingProvider());
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    /** Same recovery trick as {@link AssociationGraphEmbeddingCostE2ETest}: re-create what
     *  {@code JavAIEnvironment} installed, since {@code JavAIRuntime.embeddingProvider()} is package-private. */
    private static JavAIEmbeddingProvider existingProvider() {
        return dev.xtrafe.javai.vector.LocalEmbeddingDefaults.create(
                dev.xtrafe.javai.e2e.environment.MonolithicContainer.embeddingEndpoint());
    }

    @AfterAll
    static void restore() {
        JavAIRuntime.configureConsistencyMode(originalMode);
        JavAIRuntime.configureEmbeddingProvider(existingProvider());
    }

    // ---- saveAll, on every backend ----------------------------------------------------------------

    @Test
    @DisplayName("Postgres: saveAll sends every entity's text in one real request")
    void saveAllBatchesOnPostgres() {
        assertSaveAllBatches(JavAIEnvironment.postgresArticleRepository(), "pg");
    }

    @Test
    @DisplayName("Neo4j: saveAll sends every entity's text in one real request")
    void saveAllBatchesOnNeo4j() {
        assertSaveAllBatches(JavAIEnvironment.neo4jArticleRepository(), "neo");
    }

    @Test
    @DisplayName("MongoDB: saveAll sends every entity's text in one real request")
    void saveAllBatchesOnMongo() {
        assertSaveAllBatches(JavAIEnvironment.mongoArticleRepository(), "mongo");
    }

    /**
     * One round trip for the whole batch, and every text still embedded exactly once.
     *
     * <p>Both halves matter together: round trips alone could be driven to one by embedding less than
     * correctness requires, and texts alone are what the pre-OMI-266 code already satisfied while making
     * {@code FLEET} separate requests.
     */
    private void assertSaveAllBatches(JavAIRepository<Article> repository, String label) {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        List<Article> fleet = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < FLEET; i++) {
            String title = unique(label + "-bulk-title");
            String body = unique(label + "-bulk-body");
            expected.add(title);
            expected.add(body);
            fleet.add(new Article(title, body));
        }

        provider.reset();
        List<Article> saved = repository.saveAll(fleet);
        provider.ledger().awaitQuiescence();

        assertEquals(FLEET, saved.size(), "saveAll must return every entity it saved");
        provider.ledger().assertEmbeddedExactlyOnce(expected);
        assertEquals(1, provider.ledger().roundTrips(),
                "all " + expected.size() + " texts should reach the real model in one request\n\n"
                        + provider.ledger().report());
    }

    // ---- a single save, and the modes ------------------------------------------------------------

    /**
     * One {@code save()} of one entity batches its own fields — the half that needs no caller cooperation at
     * all, and therefore the half every existing consumer gets for free.
     */
    @Test
    @DisplayName("one save batches the entity's own fields into one request")
    void singleSaveBatchesItsOwnFields() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        String title = unique("single-title");
        String body = unique("single-body");

        provider.reset();
        JavAIEnvironment.postgresArticleRepository().save(new Article(title, body));
        provider.ledger().awaitQuiescence();

        provider.ledger().assertEmbeddedExactlyOnce(List.of(title, body));
        assertEquals(1, provider.ledger().roundTrips(),
                "two fields, one request\n\n" + provider.ledger().report());
    }

    /**
     * Batching must not depend on the ambient consistency mode. A persistence flush forces accurate reads
     * regardless of mode, so the warm ahead of it should behave identically under all three — and if it ever
     * does not, the failure would be a mode-dependent number of live model calls, which is exactly the kind
     * of thing that only shows up in production.
     */
    @ParameterizedTest
    @EnumSource(EmbeddingConsistencyMode.class)
    @DisplayName("saveAll batches identically under every consistency mode")
    void saveAllBatchesUnderEveryConsistencyMode(EmbeddingConsistencyMode mode) {
        JavAIRuntime.configureConsistencyMode(mode);
        List<Article> fleet = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < FLEET; i++) {
            String title = unique("mode-" + mode + "-title");
            String body = unique("mode-" + mode + "-body");
            expected.add(title);
            expected.add(body);
            fleet.add(new Article(title, body));
        }

        provider.reset();
        JavAIEnvironment.postgresArticleRepository().saveAll(fleet);
        provider.ledger().awaitQuiescence();

        provider.ledger().assertEmbeddedExactlyOnce(expected);
        assertEquals(1, provider.ledger().roundTrips(),
                "batching must not vary by consistency mode\n\n" + provider.ledger().report());
    }

    // ---- the request a real server has to actually accept -----------------------------------------

    /**
     * The batch-ceiling work (OMI-266), against a live server rather than a stub.
     *
     * <p>Deliberately sized to exceed the default count ceiling: {@code ARTICLES_EXCEEDING_A_BATCH} articles
     * of two {@code @Vectorize} fields each is more texts than one request may carry, so the split is
     * genuinely exercised rather than merely permitted. A fake provider can show that JavAI divides the list;
     * only this can show that each resulting request is one a real embedding server accepts and answers.
     *
     * <p>Bodies stay short on purpose. The e2e {@code Article} maps {@code body} to a default
     * {@code varchar(255)}, so a long-text fixture would fail on the column rather than on anything this test
     * is about — and text length is the wrong lever anyway, since it is the count ceiling that binds first
     * for ordinary field values.
     */
    @Test
    @DisplayName("a bulk write larger than one batch is split into requests the real model accepts")
    void aBulkWriteLargerThanOneBatchIsSplitIntoAcceptableRequests() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        int ceiling = existingProvider().maxBatchSize();

        List<Article> fleet = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < ARTICLES_EXCEEDING_A_BATCH; i++) {
            String title = unique("stress-title");
            String body = unique("stress-body") + " a short paragraph of ordinary article text.";
            expected.add(title);
            expected.add(body);
            fleet.add(new Article(title, body));
        }

        provider.reset();
        List<Article> saved = JavAIEnvironment.postgresArticleRepository().saveAll(fleet);
        provider.ledger().awaitQuiescence();

        assertEquals(ARTICLES_EXCEEDING_A_BATCH, saved.size());
        provider.ledger().assertEmbeddedExactlyOnce(expected);
        assertTrue(expected.size() > ceiling,
                "the fixture must be larger than one batch or this proves nothing: " + expected.size()
                        + " texts against a ceiling of " + ceiling);
        assertTrue(provider.ledger().roundTrips() > 1,
                "more texts than one request may carry must become more than one request\n\n"
                        + provider.ledger().report());
        assertTrue(provider.ledger().batchSizes().values().stream().allMatch(size -> size <= ceiling),
                "no request may exceed the provider's declared ceiling of " + ceiling + ": "
                        + provider.ledger().batchSizes());
    }

    /**
     * A ceiling the provider declares is honoured against the real server, whatever its value.
     *
     * <p>The test above exercises whatever ceiling this host's provider happens to declare, which is the
     * library default for Ollama. That leaves the interesting case untested: a provider declaring something
     * <em>smaller</em>, which is the real situation for Text Embeddings Inference, whose
     * {@code max_client_batch_size} defaults to 32 — below the 100 this library chunked at, so a default TEI
     * deployment refused a full batch outright. TEI is not the provider on this host
     * ({@code LocalEmbeddingDefaults} picks Ollama on macOS), so the way to cover the shape is to declare a
     * small ceiling over the real provider and check both that JavAI respects it and that the live server
     * answers every request it produces.
     */
    @Test
    @DisplayName("a small declared ceiling is honoured, and every resulting request is accepted")
    void aSmallDeclaredCeilingIsHonouredAgainstTheRealServer() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        int ceiling = 8;
        // Outermost, so precomputeVectors reads *this* ceiling; the recorder beneath it still sees, and
        // counts, every real request that results.
        JavAIRuntime.configureEmbeddingProvider(new CappedBatchProvider(ceiling, provider));
        try {
            List<Article> fleet = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < FLEET; i++) {
                String title = unique("capped-title");
                String body = unique("capped-body");
                expected.add(title);
                expected.add(body);
                fleet.add(new Article(title, body));
            }

            provider.reset();
            JavAIEnvironment.postgresArticleRepository().saveAll(fleet);
            provider.ledger().awaitQuiescence();

            provider.ledger().assertEmbeddedExactlyOnce(expected);
            assertTrue(provider.ledger().batchSizes().values().stream().allMatch(size -> size <= ceiling),
                    "a declared ceiling of " + ceiling + " must bound every request: "
                            + provider.ledger().batchSizes());
            assertEquals((expected.size() + ceiling - 1) / ceiling, provider.ledger().roundTrips(),
                    "the batch should be split into exactly as many requests as the ceiling requires\n\n"
                            + provider.ledger().report());
        } finally {
            JavAIRuntime.configureEmbeddingProvider(provider);
        }
    }

    /** Declares a batch ceiling over a real provider, delegating everything else -- so the ceiling under test
     *  is JavAI's to respect while the requests it produces still go to the live model. */
    private record CappedBatchProvider(int ceiling, JavAIEmbeddingProvider delegate)
            implements JavAIEmbeddingProvider {

        @Override
        public dev.xtrafe.javai.vector.EmbeddingVector embed(String text) {
            return delegate.embed(text);
        }

        @Override
        public List<dev.xtrafe.javai.vector.EmbeddingVector> embedAll(List<String> texts) {
            return delegate.embedAll(texts);
        }

        @Override
        public String modelId() {
            return delegate.modelId();
        }

        @Override
        public int maxInputTokens() {
            return delegate.maxInputTokens();
        }

        @Override
        public int maxBatchSize() {
            return ceiling;
        }
    }

    /**
     * And the vectors that came back are real, which is the claim a count cannot make. A batched response
     * that lined its rows up wrongly, or returned a placeholder for the tail of a large request, would
     * satisfy every assertion above and be silently wrong forever — the exact failure
     * {@code JavAIEmbeddingProvider.embedAll}'s contract warns about.
     */
    @Test
    @DisplayName("vectors from a batched request are real, distinct, and correctly dimensioned")
    void batchedVectorsAreRealAndDistinct() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        List<Article> fleet = new ArrayList<>();
        for (int i = 0; i < FLEET; i++) {
            fleet.add(new Article(unique("distinct-title"), unique("distinct-body")));
        }

        provider.reset();
        List<Article> saved = JavAIEnvironment.postgresArticleRepository().saveAll(fleet);
        provider.ledger().awaitQuiescence();

        int dims = ((dev.xtrafe.javai.model.JavAIVectorizable) saved.get(0)).fieldVector("title").dims();
        assertTrue(dims > 1, "a real model must return a real vector, not a placeholder: " + dims);

        List<String> seen = new ArrayList<>();
        for (Article article : saved) {
            var vectorizable = (dev.xtrafe.javai.model.JavAIVectorizable) article;
            var titleVector = vectorizable.fieldVector("title");
            assertEquals(dims, titleVector.dims(), "every vector in a batch must have the model's dimensions");
            seen.add(java.util.Arrays.toString(titleVector.values()));
        }
        assertEquals(seen.size(), seen.stream().distinct().count(),
                "distinct titles must yield distinct vectors -- identical ones would mean the batch's rows "
                        + "were misaligned or duplicated");
    }

    private static String unique(String label) {
        return label + "-" + UNIQUE.incrementAndGet();
    }
}
