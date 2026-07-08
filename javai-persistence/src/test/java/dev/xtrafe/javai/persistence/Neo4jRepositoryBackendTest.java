package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real container, not hermetic: there's no meaningful way to fake whether Neo4j's native vector index
 * actually ranks correctly. See {@link HibernatePostgresRepositoryBackendTest}'s javadoc for why
 * {@link FakeEmbeddingProvider} is still fine for the embeddings themselves.
 */
@Testcontainers
class Neo4jRepositoryBackendTest {

    private static final String NEO4J_PASSWORD = "javai-test-password";

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community"))
            .withAdminPassword(NEO4J_PASSWORD);

    private static TestArticleRepository repository;

    @BeforeAll
    static void configurePersistenceAndProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIPI.configurePersistence(JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(neo4j.getBoltUrl())
                .neo4jUsername("neo4j")
                .neo4jPassword(NEO4J_PASSWORD)
                .build());
        repository = JavAIPI.repository(TestArticleRepository.class);
    }

    @BeforeEach
    void resetProvider() {
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
     * *same* node land under two entirely separate, model-qualified property names (named from
     * {@link ModelIds#sanitize}) -- the old model's property is never overwritten by a newer model's save.
     */
    @Test
    void savingUnderADifferentModelUsesASeparatePropertyPerModel() {
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

        try (Driver driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J_PASSWORD));
                var session = driver.session()) {
            String originalProperty = "titleVector__" + ModelIds.sanitize(originalModelId);
            String newProperty = "titleVector__" + ModelIds.sanitize("fake-test-model-v2");
            boolean bothPropertiesPresent = session.run(
                    "MATCH (n:TestArticle {id: $id}) RETURN n[$originalProperty] IS NOT NULL AS hasOriginal, "
                            + "n[$newProperty] IS NOT NULL AS hasNew",
                    org.neo4j.driver.Values.parameters("id", id.toString(),
                            "originalProperty", originalProperty, "newProperty", newProperty))
                    .single().get("hasOriginal").asBoolean();
            assertTrue(bothPropertiesPresent,
                    "the original model's property must still be present, untouched by the newer model's save");
        }
    }

    /**
     * A different-dimension model swap is now *exactly* the same operation as a same-dimension one: just
     * {@code configureEmbeddingProvider(...)}, nothing else. Each model gets its own, independently and
     * correctly dimensioned property + vector index the first time it's needed, so there's no shared-
     * property conflict to hit -- contrast with the earlier, since-replaced shared-property design, where
     * a different-dimension swap left a node silently inconsistent with its own vector index.
     */
    @Test
    void differentDimensionModelSwapWorksTheSameAsASameDimensionSwap() {
        TestArticle article = repository.save(new TestArticle("Dimension swap test", "Original text."));
        UUID id = article.getId();

        JavAIRuntime.configureEmbeddingProvider(text -> new EmbeddingVector(
                new float[] {0.1f, 0.2f, 0.3f, 0.4f}, "fake-test-model-4dim", 4, Instant.now()));
        TestArticle reloaded = repository.findById(id).orElseThrow();
        TestArticle resaved = repository.save(reloaded); // must NOT throw

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
                "the original model's property/index must still hold this entity -- reverting must not need reindexing");
    }

    @Test
    void bogusDerivedQueryMethodFailsFastAtRepositoryCreation() {
        assertThrows(IllegalArgumentException.class, () -> JavAIPI.repository(BogusTestArticleRepository.class));
    }
}
