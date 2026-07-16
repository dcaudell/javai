package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.collections.KnowledgeGraph;
import dev.xtrafe.javai.collections.SubgraphResult;
import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.ArticleClusterRepository;
import dev.xtrafe.javai.e2e.domain.ArticleCluster;
import dev.xtrafe.javai.e2e.domain.RelatesTo;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.e2e.environment.MonolithicContainer;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIVectorizable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real end-to-end proof that a {@code KnowledgeGraph<Article, RelatesTo>} <em>field</em> (not a bare local
 * variable, unlike {@link ArticleGraphEmbeddingE2ETest}'s own in-memory-only {@code KnowledgeGraph} usage)
 * persists correctly against a real Neo4j container -- real weaving, real embeddings, {@link ArticleCluster}
 * plumbed through {@code JavAIEnvironment} exactly like every other e2e-owned domain type.
 *
 * <p>Also proves the negative half of the same story: {@link ArticleClusterRepository} is deliberately
 * Neo4j-only (see {@link ArticleCluster}'s own javadoc) -- registering it against fresh Postgres/MongoDB
 * configs (built directly here, not through {@code JavAIEnvironment}'s already-initialized shared ones, so
 * a failure here can never poison those) must fail clearly, not confusingly, per
 * {@code RepositoryBackendHibernatePostgres}/{@code RepositoryBackendSpringDataMongo}'s own
 * {@code validateNoKnowledgeGraphFields} guards.
 */
class KnowledgeGraphPersistenceE2ETest {

    private static ArticleClusterRepository repository;

    @BeforeAll
    static void configureRepository() {
        JavAIEnvironment.ensureRunning();
        repository = JavAIEnvironment.neo4jArticleClusterRepository();
    }

    @Test
    void knowledgeGraphFieldRoundTripsThroughARealNeo4jContainer() {
        Article security = new Article("Zero-day disclosed",
                "A critical vulnerability affecting a widely used TLS library was patched today.");
        Article cooking = new Article("Weeknight pasta recipes",
                "Quick, easy pasta dishes you can make in under thirty minutes.");
        Article sports = new Article("Championship game recap",
                "The local team secured a dramatic overtime victory last night.");

        ArticleCluster cluster = new ArticleCluster("Today's roundup");
        cluster.getGraph().addEdge(security, cooking, new RelatesTo("published same day"));
        cluster.getGraph().addNode(sports); // isolated -- no edge at all

        ArticleCluster saved = repository.save(cluster);
        ArticleCluster reloaded = repository.findById(saved.getId()).orElseThrow();

        KnowledgeGraph<Article, RelatesTo> graph = reloaded.getGraph();
        assertEquals(3, graph.nodes().size());
        Article reloadedSecurity = findByTitle(graph, "Zero-day disclosed");
        Article reloadedCooking = findByTitle(graph, "Weeknight pasta recipes");
        assertTrue(graph.nodes().stream().anyMatch(a -> a.getTitle().equals("Championship game recap")),
                "an isolated node with no edges must still round-trip");

        assertEquals(1, graph.edges(reloadedSecurity, reloadedCooking).size());
        assertEquals("published same day", graph.edges(reloadedSecurity, reloadedCooking).iterator().next().reason());

        // The Article nodes this graph reached are real, independently-persisted Articles -- findable and
        // vector-searchable through their own ArticleRepository, exactly like any other entity Neo4j's
        // reflective mapper saves as a relationship target.
        Article independentlyFound = JavAIEnvironment.neo4jArticleRepository().findById(reloadedSecurity.getId())
                .orElseThrow();
        assertEquals("Zero-day disclosed", independentlyFound.getTitle());
    }

    @Test
    void nearestSubgraphWorksOnARehydratedGraphField() {
        Article security = new Article("Ransomware attack disclosed",
                "A ransomware attack affecting a widely used TLS library was patched today.");
        Article cooking = new Article("Sunday roast recipes",
                "Slow-cooked, comforting Sunday roast dishes for the whole family.");
        Article sports = new Article("League standings update",
                "The regular season standings shifted after last night's results.");

        ArticleCluster cluster = new ArticleCluster("Subgraph roundup");
        cluster.getGraph().addEdge(security, cooking, new RelatesTo("published same day"));
        cluster.getGraph().addNode(sports);

        ArticleCluster saved = repository.save(cluster);
        ArticleCluster reloaded = repository.findById(saved.getId()).orElseThrow();
        KnowledgeGraph<Article, RelatesTo> graph = reloaded.getGraph();
        Article reloadedSecurity = findByTitle(graph, "Ransomware attack disclosed");

        EmbeddingVector reference = ((JavAIVectorizable) reloadedSecurity).fieldVector("title");
        SubgraphResult<Article, RelatesTo> subgraph = graph.nearestSubgraph(reference, 1, 1);

        assertTrue(subgraph.nodes().stream().anyMatch(a -> a.getTitle().equals("Sunday roast recipes")),
                "the 1-hop neighbor reached via the graph's own persisted edge must be included");
        assertTrue(subgraph.nodes().stream().noneMatch(a -> a.getTitle().equals("League standings update")),
                "a node with no edge to the origin must not appear in a 1-hop subgraph");
    }

    /** Proves Postgres rejects an {@link ArticleCluster} repository clearly, at registration time. */
    @Test
    void postgresRejectsAKnowledgeGraphFieldClearly() {
        JavAIPersistenceConfig freshPostgresConfig = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(MonolithicContainer.postgresUrl())
                .postgresUsername(MonolithicContainer.POSTGRES_USERNAME)
                .postgresPassword(MonolithicContainer.POSTGRES_PASSWORD)
                .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(ArticleClusterRepository.class, freshPostgresConfig));
        assertTrue(thrown.getMessage().contains("KnowledgeGraph"));
        assertTrue(thrown.getMessage().contains("Neo4j"));
    }

    /** Proves MongoDB rejects an {@link ArticleCluster} repository clearly, at registration time. */
    @Test
    void mongoRejectsAKnowledgeGraphFieldClearly() {
        JavAIPersistenceConfig freshMongoConfig = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri(MonolithicContainer.mongoUri())
                .mongoDatabase("javai")
                .build();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(ArticleClusterRepository.class, freshMongoConfig));
        assertTrue(thrown.getMessage().contains("KnowledgeGraph"));
        assertTrue(thrown.getMessage().contains("Neo4j"));
    }

    private static Article findByTitle(KnowledgeGraph<Article, RelatesTo> graph, String title) {
        return graph.nodes().stream().filter(a -> a.getTitle().equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError("expected an article titled '" + title + "' in " + graph.nodes()));
    }
}
