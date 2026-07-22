package dev.xtrafe.javai.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real container, not hermetic -- there's no meaningful way to fake whether MongoDB's own
 * {@code $vectorSearch} actually ranks correctly. See {@link RepositoryBackendHibernatePostgresTest}'s
 * javadoc for why {@link FakeEmbeddingProvider} is still fine for the embeddings themselves.
 *
 * <p>Uses {@code mongodb/mongodb-atlas-local}, not this module's own {@code MongoDBContainer} module --
 * that image bundles both {@code mongod} and {@code mongot} (the separate search process real
 * {@code $vectorSearch} needs) as a single-node replica set, with functional parity to Atlas Vector Search
 * and no Atlas account required; {@code MongoDBContainer} doesn't know about that bundled-search layout.
 * {@code directConnection=true} on the connection string is required because the replica set advertises
 * its own container hostname (confirmed empirically against a real running container, not assumed), which
 * isn't reachable from the test JVM through Testcontainers' mapped port -- without it, the driver's normal
 * replica-set topology discovery tries to connect to that unreachable hostname directly.
 *
 * <p>MongoDB Search indexing is near-real-time, not synchronous -- confirmed empirically, not assumed: a
 * just-written document doesn't necessarily appear in a {@code $vectorSearch} result the instant the write
 * that produced it returns, even once the index itself is confirmed {@code queryable} (a separate concern
 * {@link RepositoryBackendSpringDataMongo#awaitIndexQueryable} already handles). {@link #awaitContainsId}
 * polls past this specific propagation lag wherever a test writes then immediately queries for that same
 * document -- a real difference from Postgres's/Neo4j's synchronous index updates, not a flake to paper over.
 */
@Testcontainers
class RepositoryBackendSpringDataMongoTest {

    private static final String DATABASE = "javai_test";

    @Container
    static final GenericContainer<?> mongo =
            new GenericContainer<>(DockerImageName.parse("mongodb/mongodb-atlas-local:8.2"))
                    .withExposedPorts(27017)
                    .withStartupTimeout(Duration.ofMinutes(3));

    private static JavAIPersistenceConfig config;
    private static TestArticleRepository repository;
    private static TestAccountRepository accountRepository;
    private static TestAccountNestedRepository nestedAccountRepository;
    private static TestVenueRepository venueRepository;

    @BeforeAll
    static void configurePersistenceAndProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri(mongoUri())
                .mongoDatabase(DATABASE)
                .build();
        repository = JavAIPI.repository(TestArticleRepository.class, config);
        accountRepository = JavAIPI.repository(TestAccountRepository.class, config);
        nestedAccountRepository = JavAIPI.repository(TestAccountNestedRepository.class, config);
        venueRepository = JavAIPI.repository(TestVenueRepository.class, config);
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
        repository.save(new TestArticle("Weeknight pasta recipes",
                "Quick, easy pasta dishes you can make in under thirty minutes."));
        repository.save(new TestArticle("Championship game recap",
                "The local team secured a dramatic overtime victory last night."));

        EmbeddingVector reference = security.fieldVector("title");
        List<TestArticle> nearest =
                awaitContainsId(() -> repository.findNearestByTitleVector(reference, 1), security.getId());

        assertEquals(1, nearest.size());
        assertEquals(security.getId(), nearest.get(0).getId(),
                "the article whose own title produced the reference vector must rank nearest to itself");
    }

    @Test
    void findNearestByVectorAndSummaryVectorAlsoWork() {
        TestArticle article = repository.save(new TestArticle("Combined and summary vector test",
                "Proves the whole-object vector() and summaryVector() query variants, not just per-field."));

        List<TestArticle> byVector =
                awaitContainsId(() -> repository.findNearestByVector(article.vector(), 5), article.getId());
        assertTrue(byVector.stream().anyMatch(a -> a.getId().equals(article.getId())));

        List<TestArticle> bySummary =
                awaitContainsId(() -> repository.findNearestBySummaryVector(article.summaryVector(), 5), article.getId());
        assertTrue(bySummary.stream().anyMatch(a -> a.getId().equals(article.getId())));
    }

    /**
     * Structural proof, not just behavioral: two different models' vectors for the *same* field of the
     * *same* document land under two entirely separate, model-qualified field names (from
     * {@link ModelIds#sanitize}) -- the old model's field is never overwritten by a newer model's save,
     * since every write goes through {@code $set}, never a whole-document replace.
     */
    @Test
    void savingUnderADifferentModelUsesASeparateFieldPerModel() {
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

        Document doc = readDocument(id);
        String originalField = "titleVector__" + ModelIds.sanitize(originalModelId);
        String newField = "titleVector__" + ModelIds.sanitize("fake-test-model-v2");
        assertTrue(doc.containsKey(originalField),
                "the original model's field must still be present, untouched by the newer model's save");
        assertTrue(doc.containsKey(newField), "the newer model's field must also be present");
    }

    /**
     * A different-dimension model swap is now *exactly* the same operation as a same-dimension one: just
     * {@code configureEmbeddingProvider(...)}, nothing else. Each model gets its own, independently and
     * correctly dimensioned field + vector search index the first time it's needed, so there's no
     * shared-field conflict to hit.
     */
    @Test
    void differentDimensionModelSwapWorksTheSameAsASameDimensionSwap() {
        TestArticle article = repository.save(new TestArticle("Dimension swap test", "Original text."));
        UUID id = article.getId();

        JavAIRuntime.configureEmbeddingProvider(text -> new EmbeddingVector(
                new float[] {0.1f, 0.2f, 0.3f, 0.4f}, "fake-test-model-4dim", 4, Instant.now()));
        TestArticle reloaded = repository.findById(id).orElseThrow();
        TestArticle resaved = repository.save(reloaded); // must NOT throw

        List<TestArticle> found =
                awaitContainsId(() -> repository.findNearestByTitleVector(resaved.fieldVector("title"), 5), id);
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
        List<TestArticle> found =
                awaitContainsId(() -> repository.findNearestByTitleVector(reloaded.fieldVector("title"), 5), id);
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

        List<TestArticle> found = awaitContainsId(() -> repository.findNearestByTitleVector(originalReference, 5), id);
        assertTrue(found.stream().anyMatch(a -> a.getId().equals(id)),
                "the original model's field/index must still hold this entity -- reverting must not need reindexing");
    }

    @Test
    void bogusDerivedQueryMethodFailsFastAtRepositoryCreation() {
        assertThrows(IllegalArgumentException.class, () -> JavAIPI.repository(BogusTestArticleRepository.class, config));
    }

    /**
     * {@code KnowledgeGraph} persistence is Neo4j-only (see {@link RepositoryBackendNeo4jTest}'s own
     * KnowledgeGraph round-trip tests) -- proves the MongoDB backend rejects a {@code KnowledgeGraph}-typed
     * field clearly, at registration time, rather than misidentifying it as an ordinary referenceable entity
     * (since {@code KnowledgeGraph extends JavAIVectorizable}) and failing confusingly deep inside
     * {@code EntityReflection.readId} the first time a document needed to reference it.
     */
    @Test
    void knowledgeGraphFieldFailsFastAtRegistrationInsteadOfMisidentifyingItAsAReferenceableEntity() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(TestOwnerWithGraphRepository.class,
                        JavAIPersistenceConfig.builder()
                                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                                .mongoUri(mongoUri())
                                .mongoDatabase(DATABASE)
                                .build()));
        assertTrue(thrown.getMessage().contains("KnowledgeGraph"));
        assertTrue(thrown.getMessage().contains("Neo4j"));
    }

    /**
     * Proves {@code Map}-typed reference fields round-trip both keys and values -- see
     * {@link RepositoryBackendNeo4jTest#mapFieldRoundTripsWithKeysPreserved()} for the equivalent proof
     * against Neo4j, and this backend's own {@code referenceValue}/{@code hydrateReferenceField} for the
     * mechanism ({@code key} stored alongside {@code type}/{@code id}/{@code ordinal} on each array member).
     */
    @Test
    void mapFieldRoundTripsWithKeysPreserved() {
        JavAIPI.repository(TestTagRepository.class, config);
        TestArticleWithTagsRepository repository = JavAIPI.repository(TestArticleWithTagsRepository.class, config);

        TestArticleWithTags article = new TestArticleWithTags("Map field test");
        article.getTagsByCode().put("first", new TestTag("alpha"));
        article.getTagsByCode().put("second", new TestTag("beta"));

        TestArticleWithTags saved = repository.save(article);
        TestArticleWithTags reloaded = repository.findById(saved.getId()).orElseThrow();

        assertEquals(2, reloaded.getTagsByCode().size());
        assertEquals("alpha", reloaded.getTagsByCode().get("first").getLabel());
        assertEquals("beta", reloaded.getTagsByCode().get("second").getLabel());
    }

    /**
     * See {@link RepositoryBackendHibernatePostgresTest#savedVectorIsAlwaysAccurateUnderImmediateConsistency}
     * -- same must-have, same reasoning, proven against this backend instead.
     */
    @Test
    void savedVectorIsAlwaysAccurateUnderImmediateConsistency() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        TestArticle article = repository.save(new TestArticle("first title", "first body"));
        UUID id = article.getId();

        article.setTitle("second title, mutated just before save");
        TestArticle resaved = repository.save(article);

        EmbeddingVector expected = resaved.fieldVector("title");
        float[] stored = readVectorField(id, "titleVector__" + ModelIds.sanitize(expected.modelId()));
        assertArrayEquals(expected.values(), stored, 1e-4f,
                "the persisted vector must match the field's value as of save(), not some earlier value");
    }

    /**
     * See {@link RepositoryBackendHibernatePostgresTest#savedVectorIsAlwaysAccurateUnderEventualConsistencyDespiteASlowBackgroundProvider}
     * -- same race, same reasoning, proven against this backend instead.
     */
    @Test
    void savedVectorIsAlwaysAccurateUnderEventualConsistencyDespiteASlowBackgroundProvider() {
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
        float[] stored = readVectorField(id, "titleVector__" + ModelIds.sanitize(expected.modelId()));
        assertArrayEquals(expected.values(), stored, 1e-4f,
                "EVENTUAL_CONSISTENCY's slow background dispatch must never be allowed to leave a stale "
                        + "vector in the database -- the flush itself must have forced an accurate, "
                        + "blocking recompute");
    }

    /** Polls {@code query} until its result contains {@code expectedId} or 15s elapses, returning whatever
     *  the last call produced either way -- see this class's own javadoc for why this is needed (mongot's
     *  near-real-time, not synchronous, document indexing) and why it's the caller's own subsequent
     *  assertion, not this helper, that reports the actual failure: a plain {@code fail()} here would lose
     *  the caller's specific, more informative assertion message. */
    private static List<TestArticle> awaitContainsId(Supplier<List<TestArticle>> query, UUID expectedId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        List<TestArticle> result;
        while (true) {
            result = query.get();
            if (result.stream().anyMatch(a -> a.getId().equals(expectedId)) || Instant.now().isAfter(deadline)) {
                return result;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
        }
    }

    // ---- ordinary (non-vector) derived finders, OMI-138 ----------------------------------------

    @Test
    void ordinaryDerivedFindersWorkAcrossOperatorsProjectionsAndPaging() {
        DerivedFinderTestSupport.seed(accountRepository);
        DerivedFinderTestSupport.assertSimpleDerivedFinders(accountRepository);
    }

    @Test
    void nestedAssociationDerivedFindersResolveThroughReferencePointers() {
        // References are {type, id} pointers, not embedded; OMI-141 resolves a nested predicate by matching
        // the referenced entities first, then owners whose <field>.id points at one of them.
        DerivedFinderTestSupport.seed(accountRepository);
        DerivedFinderTestSupport.assertNestedDerivedFinders(nestedAccountRepository);
    }

    @Test
    void nestedToManyEmptinessRegexAndGeoFindersWork() {
        DerivedFinderTestSupport.seedVenues(venueRepository);
        DerivedFinderTestSupport.assertNestedToManyEmptinessRegexAndGeoFinders(venueRepository);
    }

    @Test
    void unknownPropertyDerivedFinderFailsFastAtRepositoryCreation() {
        assertThrows(IllegalArgumentException.class, () -> JavAIPI.repository(TestBadPropertyRepository.class, config));
    }

    private static String mongoUri() {
        return "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?directConnection=true";
    }

    private static Document readDocument(UUID id) {
        try (MongoClient client = MongoClients.create(mongoUri())) {
            MongoCollection<Document> collection = client.getDatabase(DATABASE).getCollection("TestArticle");
            Document doc = collection.find(Filters.eq("_id", id.toString())).first();
            assertTrue(doc != null, "expected a document for " + id);
            return doc;
        }
    }

    @SuppressWarnings("unchecked")
    private static float[] readVectorField(UUID id, String field) {
        Document doc = readDocument(id);
        List<Double> values = (List<Double>) doc.get(field);
        assertTrue(values != null, "expected field " + field + " to be set on document " + id);
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i).floatValue();
        }
        return result;
    }
}
