package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real container, not hermetic: there's no meaningful way to fake whether pgvector's {@code <=>} operator
 * actually ranks correctly. Uses {@link FakeEmbeddingProvider} for the embeddings themselves (only
 * genuinely-different-text-produces-genuinely-different-vectors matters here, not real semantic quality --
 * that's already proven against real embeddings in {@code e2e-client-test}).
 */
@Testcontainers
class RepositoryBackendHibernatePostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestArticleRepository repository;
    private static TestAccountRepository accountRepository;
    private static TestAccountNestedRepository nestedAccountRepository;

    @BeforeAll
    static void configurePersistenceAndProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        repository = JavAIPI.repository(TestArticleRepository.class, config);
        // Registered before first use, like every repository this SessionFactory must cover (TestProfile is
        // auto-registered via TestAccount's @OneToOne, so it needs no explicit repository call here).
        accountRepository = JavAIPI.repository(TestAccountRepository.class, config);
        nestedAccountRepository = JavAIPI.repository(TestAccountNestedRepository.class, config);
    }

    @BeforeEach
    void resetProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @AfterEach
    void resetConsistencyMode() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void saveAndFindByIdRoundTrips() {
        TestArticle article = new TestArticle("Zero-day disclosed", "A critical vulnerability was patched today.");
        TestArticle saved = repository.save(article);
        assertTrue(saved.getId() != null);

        Optional<TestArticle> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Zero-day disclosed", found.get().getTitle());
        assertEquals("A critical vulnerability was patched today.", found.get().getBody());
    }

    @Test
    void findNearestByFieldVectorRanksBySimilarity() {
        TestArticle security = repository.save(new TestArticle("Zero-day disclosed",
                "A critical vulnerability affecting a widely used TLS library was patched today."));
        TestArticle cooking = repository.save(new TestArticle("Weeknight pasta recipes",
                "Quick, easy pasta dishes you can make in under thirty minutes."));
        TestArticle sports = repository.save(new TestArticle("Championship game recap",
                "The local team secured a dramatic overtime victory last night."));

        EmbeddingVector reference = security.fieldVector("title");
        List<TestArticle> nearest = repository.findNearestByTitleVector(reference, 1);

        assertEquals(1, nearest.size());
        assertEquals(security.getId(), nearest.get(0).getId(),
                "the article whose own title produced the reference vector must rank nearest to itself");
    }

    @Test
    void findNearestByVectorAndSummaryVectorAlsoWork() {
        TestArticle article = repository.save(new TestArticle("Combined and summary vector test",
                "Proves the whole-object vector() and summaryVector() query variants, not just per-field."));

        List<TestArticle> byVector = repository.findNearestByVector(article.vector(), 5);
        assertTrue(byVector.stream().anyMatch(a -> a.getId().equals(article.getId())));

        List<TestArticle> bySummary = repository.findNearestBySummaryVector(article.summaryVector(), 5);
        assertTrue(bySummary.stream().anyMatch(a -> a.getId().equals(article.getId())));
    }

    /**
     * Structural proof, not just behavioral: two different models' vectors for the *same* field of the
     * *same* entity land in two entirely separate tables (named from {@link ModelIds#sanitize}), each
     * holding exactly that model's own row -- never a shared table filtered by a {@code model_id} column.
     */
    @Test
    void savingUnderADifferentModelUsesASeparateTablePerModel() throws Exception {
        TestArticle article = repository.save(new TestArticle("Model migration test", "Original text."));
        UUID id = article.getId();
        String originalModelId = article.fieldVector("title").modelId();

        FakeEmbeddingProvider baseProvider = new FakeEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(text -> {
            EmbeddingVector base = baseProvider.embed(text);
            return new EmbeddingVector(base.values(), "fake-test-model-v2", base.dims(), Instant.now());
        });
        TestArticle reloaded = repository.findById(id).orElseThrow();
        repository.save(reloaded);

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertEquals(1, countRows(connection, "javai_vectors__" + ModelIds.sanitize(originalModelId), id, "title"),
                    "the original model's table must still hold exactly this entity's row, untouched");
            assertEquals(1, countRows(connection, "javai_vectors__" + ModelIds.sanitize("fake-test-model-v2"), id, "title"),
                    "the new model must have its own, separate table");
        }
    }

    private static int countRows(Connection connection, String table, UUID id, String fieldName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE owner_id = ? AND field_name = ?")) {
            statement.setObject(1, id);
            statement.setString(2, fieldName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    /**
     * A different-dimension model swap is now *exactly* the same operation as a same-dimension one: just
     * {@code configureEmbeddingProvider(...)}, nothing else. Each model gets its own, independently and
     * correctly dimensioned table the first time it's needed, so there's no shared-column conflict to hit
     * -- contrast with the earlier, since-replaced shared-table design, where this used to fail outright
     * with a pgvector dimension-mismatch error.
     */
    @Test
    void differentDimensionModelSwapWorksTheSameAsASameDimensionSwap() {
        TestArticle article = repository.save(new TestArticle("Dimension swap test", "Original text."));
        UUID id = article.getId();

        JavAIRuntime.configureEmbeddingProvider(text -> new EmbeddingVector(
                new float[] {0.1f, 0.2f, 0.3f, 0.4f}, "fake-test-model-4dim", 4, Instant.now()));
        TestArticle reloaded = repository.findById(id).orElseThrow();
        TestArticle resaved = repository.save(reloaded); // must NOT throw, unlike the old shared-table design

        List<TestArticle> found = repository.findNearestByTitleVector(resaved.fieldVector("title"), 5);
        assertTrue(found.stream().anyMatch(a -> a.getId().equals(id)));
    }

    @Test
    void reindexAllReembedsExistingEntitiesUnderTheNewModel() {
        TestArticle article = repository.save(new TestArticle("Reindex test", "Some original text about topic A."));
        UUID id = article.getId();

        JavAIRuntime.configureEmbeddingProvider(text -> {
            EmbeddingVector base = new FakeEmbeddingProvider().embed(text);
            return new EmbeddingVector(base.values(), "fake-test-model-reindexed", base.dims(), Instant.now());
        });
        repository.reindexAll();

        TestArticle reloaded = repository.findById(id).orElseThrow();
        List<TestArticle> found = repository.findNearestByTitleVector(reloaded.fieldVector("title"), 5);
        assertTrue(found.stream().anyMatch(a -> a.getId().equals(id)),
                "reindexAll() must re-embed and persist every existing entity under the newly configured model");
    }

    @Test
    void revertingToAPreviousProviderNeedsNoReindexing() {
        TestArticle article = repository.save(new TestArticle("Revert test", "Some original text about topic B."));
        UUID id = article.getId();
        EmbeddingVector originalReference = article.fieldVector("title");

        JavAIRuntime.configureEmbeddingProvider(text -> {
            EmbeddingVector base = new FakeEmbeddingProvider().embed(text);
            return new EmbeddingVector(base.values(), "fake-test-model-temporary", base.dims(), Instant.now());
        });
        repository.reindexAll();

        // Revert -- deliberately no reindexAll() call here.
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());

        List<TestArticle> found = repository.findNearestByTitleVector(originalReference, 5);
        assertTrue(found.stream().anyMatch(a -> a.getId().equals(id)),
                "the original model's table must still hold this entity's vector -- reverting must not need reindexing");
    }

    @Test
    void bogusDerivedQueryMethodFailsFastAtRepositoryCreation() {
        assertThrows(IllegalArgumentException.class, () -> JavAIPI.repository(BogusTestArticleRepository.class, config));
    }

    // ---- ordinary (non-vector) derived finders, OMI-138 ----------------------------------------

    @Test
    void ordinaryDerivedFindersWorkAcrossOperatorsProjectionsAndPaging() {
        DerivedFinderTestSupport.seed(accountRepository);
        DerivedFinderTestSupport.assertSimpleDerivedFinders(accountRepository);
    }

    @Test
    void nestedAssociationDerivedFindersTraverseTheSingularOneToOne() {
        DerivedFinderTestSupport.seed(accountRepository);
        DerivedFinderTestSupport.assertNestedDerivedFinders(nestedAccountRepository);
    }

    @Test
    void unknownPropertyDerivedFinderFailsFastAtRepositoryCreation() {
        assertThrows(IllegalArgumentException.class, () -> JavAIPI.repository(TestBadPropertyRepository.class, config));
    }

    /**
     * {@code KnowledgeGraph} persistence is Neo4j-only (see {@link RepositoryBackendNeo4jTest}'s own
     * KnowledgeGraph round-trip tests) -- proves the Postgres backend rejects a {@code KnowledgeGraph}-typed
     * field clearly, at registration time, rather than failing confusingly deep inside Hibernate's boot-time
     * mapping once an unmappable field type is discovered.
     */
    @Test
    void knowledgeGraphFieldFailsFastAtRegistrationInsteadOfConfusingHibernateMappingFailure() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(TestOwnerWithGraphRepository.class,
                        JavAIPersistenceConfig.builder()
                                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                                .postgresUrl(postgres.getJdbcUrl())
                                .postgresUsername(postgres.getUsername())
                                .postgresPassword(postgres.getPassword())
                                .build()));
        assertTrue(thrown.getMessage().contains("KnowledgeGraph"));
        assertTrue(thrown.getMessage().contains("Neo4j"));
    }

    /**
     * The persistence must-have that doesn't depend on which {@link EmbeddingConsistencyMode} is active:
     * a save() immediately following a mutation, with no intervening explicit read, must still persist the
     * vector matching the field's *final* value -- IMMEDIATE_CONSISTENCY never eagerly computes on mutation
     * (only a subsequent read does), so without {@code writeVectors()}'s own forced-blocking read via
     * {@code fieldVector()}, this would either persist a stale value or fail outright.
     */
    @Test
    void savedVectorIsAlwaysAccurateUnderImmediateConsistency() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        TestArticle article = repository.save(new TestArticle("first title", "first body"));
        UUID id = article.getId();

        article.setTitle("second title, mutated just before save");
        TestArticle resaved = repository.save(article);

        EmbeddingVector expected = resaved.fieldVector("title");
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            float[] stored = readVectorLiteral(connection,
                    "javai_vectors__" + ModelIds.sanitize(expected.modelId()), id, "title");
            assertArrayEquals(expected.values(), stored, 1e-4f,
                    "the persisted vector must match the field's value as of save(), not some earlier value");
        }
    }

    /**
     * The race this whole locking mechanism (JavAIRuntime.runWithSubgraphLockedForPersistence) exists to
     * prevent: under EVENTUAL_CONSISTENCY with a deliberately slow provider, a mutation's own eager
     * background dispatch has *not* landed by the time save() is called immediately afterward -- if
     * writeVectors() merely read whatever {@code fieldVector()} would serve under the ambient (non-forced)
     * mode, it would persist the OLD, now-stale vector. Forcing accuracy for the duration of the flush is
     * what makes the persisted value correct regardless.
     */
    @Test
    void savedVectorIsAlwaysAccurateUnderEventualConsistencyDespiteASlowBackgroundProvider() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY);
        TestArticle article = repository.save(new TestArticle("first title", "first body"));
        UUID id = article.getId();

        FakeEmbeddingProvider fast = new FakeEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(text -> {
            try {
                Thread.sleep(300); // slower than this test's own save() call would otherwise wait for
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fast.embed(text);
        });

        article.setTitle("second title, mutated just before a slow-provider save");
        TestArticle resaved = repository.save(article); // must block on the slow provider, not skip ahead

        EmbeddingVector expected = resaved.fieldVector("title");
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            float[] stored = readVectorLiteral(connection,
                    "javai_vectors__" + ModelIds.sanitize(expected.modelId()), id, "title");
            assertArrayEquals(expected.values(), stored, 1e-4f,
                    "EVENTUAL_CONSISTENCY's slow background dispatch must never be allowed to leave a stale "
                            + "vector in the database -- the flush itself must have forced an accurate, "
                            + "blocking recompute");
        }
    }

    private static float[] readVectorLiteral(Connection connection, String table, UUID id, String fieldName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT vector::text FROM " + table + " WHERE owner_id = ? AND field_name = ?")) {
            statement.setObject(1, id);
            statement.setString(2, fieldName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "expected a row in " + table + " for " + id + "/" + fieldName);
                String literal = resultSet.getString(1); // pgvector's text form, e.g. "[0.1,0.2,0.3]"
                String[] parts = literal.substring(1, literal.length() - 1).split(",");
                float[] values = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    values[i] = Float.parseFloat(parts[i]);
                }
                return values;
            }
        }
    }
}
