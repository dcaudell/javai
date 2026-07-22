package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.collections.KnowledgeGraph;
import dev.xtrafe.javai.collections.SubgraphResult;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Value;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real container, not hermetic: there's no meaningful way to fake whether Neo4j's native vector index
 * actually ranks correctly. See {@link RepositoryBackendHibernatePostgresTest}'s javadoc for why
 * {@link FakeEmbeddingProvider} is still fine for the embeddings themselves.
 */
@Testcontainers
class RepositoryBackendNeo4jTest {

    private static final String NEO4J_PASSWORD = "javai-test-password";

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community"))
            .withAdminPassword(NEO4J_PASSWORD);

    private static JavAIPersistenceConfig config;
    private static TestArticleRepository repository;
    private static TestOwnerWithGraphRepository graphOwnerRepository;
    private static TestAccountRepository accountRepository;
    private static TestAccountNestedRepository nestedAccountRepository;

    @BeforeAll
    static void configurePersistenceAndProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(neo4j.getBoltUrl())
                .neo4jUsername("neo4j")
                .neo4jPassword(NEO4J_PASSWORD)
                .build();
        repository = JavAIPI.repository(TestArticleRepository.class, config);
        JavAIPI.repository(TestGraphNodeRepository.class, config);
        graphOwnerRepository = JavAIPI.repository(TestOwnerWithGraphRepository.class, config);
        accountRepository = JavAIPI.repository(TestAccountRepository.class, config);
        nestedAccountRepository = JavAIPI.repository(TestAccountNestedRepository.class, config);
        // Neo4j's registerEntityType doesn't recurse into related types, so TestProfile's label must be
        // registered explicitly before an account->profile relationship traversal needs to resolve it.
        JavAIPI.repository(TestProfileRepository.class, config);
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
        assertThrows(IllegalArgumentException.class, () -> JavAIPI.repository(BogusTestArticleRepository.class, config));
    }

    /**
     * Proves the fix for a real, previously-confirmed gap: {@code hydrateRelationshipField} used to only
     * handle {@code Collection}-typed relationship fields, and {@code saveNode} never persisted a
     * {@code Map} field's keys at all -- so a {@code Map}-typed field couldn't correctly round-trip through
     * Neo4j (it would either throw trying to assign a related node directly into a {@code Map}-typed field
     * slot, or -- before that fix -- have no way to know what key to reconstruct it under even if hydration
     * were fixed in isolation). {@code TestArticleWithTags.tagsByCode} is a real
     * {@code Map<String, TestTag>}, not a {@code Collection}; both entries' keys must survive the round trip
     * exactly, not just their values.
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
        float[] stored = readVectorProperty(id, "titleVector__" + ModelIds.sanitize(expected.modelId()));
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
        float[] stored = readVectorProperty(id, "titleVector__" + ModelIds.sanitize(expected.modelId()));
        assertArrayEquals(expected.values(), stored, 1e-4f,
                "EVENTUAL_CONSISTENCY's slow background dispatch must never be allowed to leave a stale "
                        + "vector in the database -- the flush itself must have forced an accurate, "
                        + "blocking recompute");
    }

    /**
     * The core round-trip: a {@code KnowledgeGraph}-typed field with several nodes and edges, plus one
     * isolated node with no edges at all -- proving the {@code <FIELD>_MEMBER} relationship (not just
     * {@code <FIELD>_EDGE}) is what makes an edge-less node still round-trip, per
     * {@code RepositoryBackendNeo4j#saveKnowledgeGraphField}'s javadoc.
     */
    @Test
    void knowledgeGraphFieldRoundTripsNodesAndEdges() {
        TestOwnerWithGraph owner = new TestOwnerWithGraph("Graph owner");
        TestGraphNode a = new TestGraphNode("node A");
        TestGraphNode b = new TestGraphNode("node B");
        TestGraphNode c = new TestGraphNode("node C");
        TestGraphNode isolated = new TestGraphNode("isolated node");
        owner.getGraph().addNode(isolated);
        owner.getGraph().addEdge(a, b, new TestGraphEdge("A connects to B"));
        owner.getGraph().addEdge(b, c, new TestGraphEdge("B connects to C"));

        TestOwnerWithGraph saved = graphOwnerRepository.save(owner);
        TestOwnerWithGraph reloaded = graphOwnerRepository.findById(saved.getId()).orElseThrow();

        KnowledgeGraph<TestGraphNode, TestGraphEdge> graph = reloaded.getGraph();
        assertEquals(4, graph.nodes().size());
        assertTrue(graph.nodes().stream().anyMatch(n -> n.getName().equals("isolated node")),
                "an isolated node with no edges must still round-trip via the graph's MEMBER relationship");

        TestGraphNode reloadedA = findByName(graph, "node A");
        TestGraphNode reloadedB = findByName(graph, "node B");
        TestGraphNode reloadedC = findByName(graph, "node C");
        assertEquals(1, graph.neighbors(reloadedA).size());
        assertTrue(graph.neighbors(reloadedA).contains(reloadedB));
        assertEquals(1, graph.edges(reloadedA, reloadedB).size());
        assertEquals("A connects to B", graph.edges(reloadedA, reloadedB).iterator().next().reason());
        assertEquals(1, graph.edges(reloadedB, reloadedC).size());
        assertEquals("B connects to C", graph.edges(reloadedB, reloadedC).iterator().next().reason());
    }

    /**
     * Proves the {@code Set<E> edges(from, to)} contract survives persistence: two distinct edges (different
     * property values) between the same node pair must MERGE into two distinct relationships, not collapse
     * into one -- contrast {@code TaggingBackendNeo4j}'s deliberate "zero or one" bare-pattern MERGE, which
     * is the wrong shape for a {@code KnowledgeGraph} edge (see {@code RepositoryBackendNeo4j#saveGraphEdge}'s
     * javadoc).
     */
    @Test
    void multipleDistinctEdgesBetweenTheSamePairAllPersist() {
        TestOwnerWithGraph owner = new TestOwnerWithGraph("Multi-edge owner");
        TestGraphNode a = new TestGraphNode("multi A");
        TestGraphNode b = new TestGraphNode("multi B");
        owner.getGraph().addEdge(a, b, new TestGraphEdge("reason one"));
        owner.getGraph().addEdge(a, b, new TestGraphEdge("reason two"));

        TestOwnerWithGraph saved = graphOwnerRepository.save(owner);
        TestOwnerWithGraph reloaded = graphOwnerRepository.findById(saved.getId()).orElseThrow();

        KnowledgeGraph<TestGraphNode, TestGraphEdge> graph = reloaded.getGraph();
        TestGraphNode reloadedA = findByName(graph, "multi A");
        TestGraphNode reloadedB = findByName(graph, "multi B");
        var reasons = graph.edges(reloadedA, reloadedB).stream().map(TestGraphEdge::reason).toList();
        assertEquals(2, reasons.size());
        assertTrue(reasons.contains("reason one"));
        assertTrue(reasons.contains("reason two"));
    }

    /**
     * Proves the full-property MERGE is idempotent: saving the same owner twice (e.g. an ordinary re-save of
     * an already-persisted entity) must not duplicate relationships.
     */
    @Test
    void savingTheSameGraphTwiceDoesNotDuplicateRelationships() {
        TestOwnerWithGraph owner = new TestOwnerWithGraph("Resave owner");
        TestGraphNode a = new TestGraphNode("resave A");
        TestGraphNode b = new TestGraphNode("resave B");
        owner.getGraph().addEdge(a, b, new TestGraphEdge("stable reason"));

        TestOwnerWithGraph saved = graphOwnerRepository.save(owner);
        graphOwnerRepository.save(saved); // deliberate re-save of the same graph contents

        TestOwnerWithGraph reloaded = graphOwnerRepository.findById(saved.getId()).orElseThrow();
        KnowledgeGraph<TestGraphNode, TestGraphEdge> graph = reloaded.getGraph();
        assertEquals(2, graph.nodes().size());
        TestGraphNode reloadedA = findByName(graph, "resave A");
        TestGraphNode reloadedB = findByName(graph, "resave B");
        assertEquals(1, graph.edges(reloadedA, reloadedB).size(),
                "re-saving the same owner must not duplicate the relationship -- MERGE must be idempotent");
    }

    /** An owner whose graph has no nodes at all must still round-trip cleanly (no error, empty graph back). */
    @Test
    void emptyKnowledgeGraphFieldRoundTrips() {
        TestOwnerWithGraph owner = new TestOwnerWithGraph("Empty graph owner");

        TestOwnerWithGraph saved = graphOwnerRepository.save(owner);
        TestOwnerWithGraph reloaded = graphOwnerRepository.findById(saved.getId()).orElseThrow();

        assertTrue(reloaded.getGraph().nodes().isEmpty());
    }

    /** {@code nearestSubgraph()} must work correctly on the graph as rehydrated from Neo4j, not just in-memory. */
    @Test
    void nearestSubgraphWorksOnARehydratedGraph() {
        TestOwnerWithGraph owner = new TestOwnerWithGraph("Subgraph owner");
        TestGraphNode security = new TestGraphNode("zero-day vulnerability disclosed");
        TestGraphNode cooking = new TestGraphNode("weeknight pasta recipes");
        TestGraphNode sports = new TestGraphNode("championship game recap");
        owner.getGraph().addEdge(security, cooking, new TestGraphEdge("published same day"));
        owner.getGraph().addNode(sports);

        TestOwnerWithGraph saved = graphOwnerRepository.save(owner);
        TestOwnerWithGraph reloaded = graphOwnerRepository.findById(saved.getId()).orElseThrow();
        KnowledgeGraph<TestGraphNode, TestGraphEdge> graph = reloaded.getGraph();
        TestGraphNode reloadedSecurity = findByName(graph, "zero-day vulnerability disclosed");

        SubgraphResult<TestGraphNode, TestGraphEdge> subgraph =
                graph.nearestSubgraph(reloadedSecurity.fieldVector("name"), 1, 1);

        assertEquals(1.0, subgraph.scoreOf(reloadedSecurity), 1e-6,
                "the node whose own field produced the reference vector must score as an exact match to itself");
        assertTrue(subgraph.nodes().stream().anyMatch(n -> n.getName().equals("weeknight pasta recipes")),
                "the 1-hop neighbor reached via the graph's own edge must be included in the subgraph");
        assertTrue(subgraph.nodes().stream().noneMatch(n -> n.getName().equals("championship game recap")),
                "a node with no edge to the origin must not appear in a 1-hop subgraph");
    }

    // ---- ordinary (non-vector) derived finders, OMI-138 ----------------------------------------

    @Test
    void ordinaryDerivedFindersWorkAcrossOperatorsProjectionsAndPaging() {
        DerivedFinderTestSupport.seed(accountRepository);
        DerivedFinderTestSupport.assertSimpleDerivedFinders(accountRepository);
    }

    @Test
    void nestedAssociationDerivedFindersTraverseTheSingularRelationship() {
        DerivedFinderTestSupport.seed(accountRepository);
        DerivedFinderTestSupport.assertNestedDerivedFinders(nestedAccountRepository);
    }

    @Test
    void unknownPropertyDerivedFinderFailsFastAtRepositoryCreation() {
        assertThrows(IllegalArgumentException.class, () -> JavAIPI.repository(TestBadPropertyRepository.class, config));
    }

    private static TestGraphNode findByName(KnowledgeGraph<TestGraphNode, TestGraphEdge> graph, String name) {
        return graph.nodes().stream().filter(n -> n.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("expected a node named '" + name + "' in " + graph.nodes()));
    }

    private static float[] readVectorProperty(UUID id, String property) {
        try (Driver driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J_PASSWORD));
                var session = driver.session()) {
            Value value = session.run("MATCH (n:TestArticle {id: $id}) RETURN n[$property] AS v",
                            org.neo4j.driver.Values.parameters("id", id.toString(), "property", property))
                    .single().get("v");
            assertTrue(!value.isNull(), "expected property " + property + " to be set on node " + id);
            List<Float> values = value.asList(Value::asFloat);
            float[] result = new float[values.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = values.get(i);
            }
            return result;
        }
    }
}
